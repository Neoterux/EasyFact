/*
 * Copyright (C) 2026 the EasyFact contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package easyfact

import java.io.File
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Pure policy over the merged manifest text and the backup rule files it points at. No Gradle
 * types, so it runs in a JVM unit test.
 *
 * The posture is default-deny: every documented domain is excluded in full with `path="."` and
 * no allow-list element exists anywhere, so a file added in any domain by a later phase is
 * excluded without anyone remembering to add a line. Both rule files and the manifest are build
 * inputs, so they are parsed with DOCTYPE declarations disabled; a parser that honours one is an
 * entity-expansion hazard with no upside here.
 */
object BackupRulesCheck {

    /** Domains valid in `<cloud-backup>` and `<device-transfer>` of the Android 12+ rules file. */
    val EXTRACTION_DOMAINS: List<String> = listOf(
        "root",
        "file",
        "database",
        "sharedpref",
        "external",
        "device_root",
        "device_file",
        "device_database",
        "device_sharedpref",
    )

    /** Domains valid in the legacy `<full-backup-content>` file. The `device_` ones are not. */
    val LEGACY_DOMAINS: List<String> = listOf("root", "file", "database", "sharedpref", "external")

    const val EXTRACTION_ROOT = "data-extraction-rules"
    const val LEGACY_ROOT = "full-backup-content"

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val XML_RESOURCE_PREFIX = "@xml/"

    /**
     * Every file named `<name>.xml` under a resource-qualified `xml` directory of [resourceDirs].
     *
     * This is CR-03. The task used to open `<resdir>/xml/<name>.xml` and nothing else, so a
     * permissive `res/xml-v33/data_extraction_rules.xml` — which Android prefers over the
     * unqualified copy on every device at or above that API level — overrode the default-deny
     * rules without the gate ever opening it. Android resolves `@xml/<name>` against `xml` AND
     * every `xml-<qualifier>` directory, so the gate must check all of them: the shipped rules
     * are whichever copy the device picks, not whichever copy is unqualified.
     *
     * Returned in a stable path order so a violation message does not reorder between runs.
     */
    fun ruleFileCandidates(resourceDirs: Collection<File>, name: String): List<File> =
        resourceDirs
            .flatMap { resDir -> resDir.listFiles()?.asList().orEmpty() }
            .filter { it.isDirectory && (it.name == "xml" || it.name.startsWith("xml-")) }
            .map { File(it, "$name.xml") }
            .filter { it.isFile }
            .sortedBy { it.path }

    /** The `@xml/<name>` resource named by [attribute] on `<application>`, or null if absent. */
    fun ruleResourceName(manifestText: String, attribute: String): String? {
        val application = parseApplication(manifestText) ?: return null
        val value = application.getAttributeNS(ANDROID_NS, attribute)
        return if (value.startsWith(XML_RESOURCE_PREFIX)) value.removePrefix(XML_RESOURCE_PREFIX) else null
    }

    /**
     * Problems with the manifest itself: either rule attribute missing or not an `@xml/` reference,
     * or `android:allowBackup` not the expected literal.
     */
    fun manifestViolations(manifestText: String, expectedAllowBackup: String): List<String> {
        val application = parseApplication(manifestText)
            ?: return listOf("manifest has no readable <application> element")
        val result = ArrayList<String>()
        for (attribute in listOf("dataExtractionRules", "fullBackupContent")) {
            val value = application.getAttributeNS(ANDROID_NS, attribute)
            if (!value.startsWith(XML_RESOURCE_PREFIX)) {
                result += "manifest does not set android:$attribute to an @xml/ rule file"
            }
        }
        val allowBackup = application.getAttributeNS(ANDROID_NS, "allowBackup")
        if (allowBackup != expectedAllowBackup) {
            result += "android:allowBackup is '$allowBackup', expected '$expectedAllowBackup'"
        }
        return result
    }

    /**
     * Problems with one rule file. [rootName] picks the shape: [EXTRACTION_ROOT] needs both a
     * `<cloud-backup>` and a `<device-transfer>` block, each excluding every domain in
     * [extractionDomains]; [LEGACY_ROOT] excludes [legacyDomains] directly under its root.
     * Any `<include>` element anywhere is a violation, whether or not every exclude is present.
     */
    fun ruleFileViolations(
        ruleText: String,
        label: String,
        rootName: String,
        extractionDomains: List<String> = EXTRACTION_DOMAINS,
        legacyDomains: List<String> = LEGACY_DOMAINS,
    ): List<String> {
        val document = parse(ruleText) ?: return listOf("$label: not well-formed XML or declares a DOCTYPE")
        val root = document.documentElement
        if (root.tagName != rootName) {
            return listOf("$label: root element is <${root.tagName}>, expected <$rootName>")
        }
        val result = ArrayList<String>()
        if (document.getElementsByTagName("include").length > 0) {
            result += "$label: contains an <include> element; default-deny allows none"
        }
        if (rootName == EXTRACTION_ROOT) {
            for (block in listOf("cloud-backup", "device-transfer")) {
                result += missingExcludes(label, "<$block>", childBlock(root, block), extractionDomains)
            }
        } else {
            result += missingExcludes(label, "<$rootName>", root, legacyDomains)
        }
        return result
    }

    private fun childBlock(root: Element, name: String): Element? {
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element && node.tagName == name) return node
        }
        return null
    }

    private fun missingExcludes(label: String, blockName: String, block: Element?, expected: List<String>): List<String> {
        val excluded = HashSet<String>()
        if (block != null) {
            val children = block.childNodes
            for (i in 0 until children.length) {
                val node = children.item(i)
                if (node is Element && node.tagName == "exclude" && node.getAttribute("path") == ".") {
                    excluded += node.getAttribute("domain")
                }
            }
        }
        val missing = expected.filter { it !in excluded }
        return if (missing.isEmpty()) emptyList() else listOf("$label: $blockName does not exclude domains: $missing")
    }

    private fun parseApplication(manifestText: String): Element? {
        val document = parse(manifestText) ?: return null
        val applications = document.getElementsByTagName("application")
        return if (applications.length == 0) null else applications.item(0) as Element
    }

    private fun parse(text: String): Document? = try {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder().parse(InputSource(StringReader(text)))
    } catch (_: Exception) {
        null
    }
}
