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

import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Pure policy over a variant's **merged** manifest text. No Gradle types, so it runs in a JVM unit
 * test.
 *
 * A library can add a permission, a service or an exported component to an app that never asked
 * for one, and only the merged manifest shows it. The policy reports:
 *
 * - `UNEXPECTED PERMISSION <name>`: a `<uses-permission>` or `<uses-permission-sdk-23>` not allowlisted.
 * - `UNEXPECTED DECLARED PERMISSION <name>`: a `<permission>` the app declares itself, not allowlisted.
 * - `UNEXPECTED EXPORTED COMPONENT <name>`: an `<activity>`, `<activity-alias>`, `<service>`,
 *   `<receiver>` or `<provider>` with `android:exported="true"` that is not allowlisted.
 * - `UNEXPECTED SERVICE <name>`: any `<service>` at all that is not allowlisted, whether exported
 *   or not. Phase 6 requires that no foreground service is used, and this is the cheapest place to
 *   start catching one.
 *
 * Allowlist entries may contain the token `{applicationId}`, replaced with the variant's
 * application id, because a library-injected permission is named after it and a variant may add
 * an id suffix.
 *
 * The manifest is a build input, so it is parsed with DOCTYPE declarations disabled; a parser
 * that honours one is an entity-expansion hazard with no upside here.
 */
object ManifestPolicy {

    /**
     * Permissions the merged manifest may declare or use. Every entry is a reviewed line.
     *
     * Today the merged manifest carries exactly one, injected by `androidx.core` (1.9 and later): a
     * signature-level permission that guards receivers registered with `RECEIVER_NOT_EXPORTED` on
     * API 31 and 32. Only this app, or an app signed with the same key, can hold it. It exposes
     * nothing to the user and reaches no network.
     *
     * What the next phases will be asked to justify, measured on a lab app with OkHttp 5.5.1,
     * WorkManager 2.12.0, DataStore 1.3.0 and core-ktx 1.19.1, so it is a known list rather than a
     * surprise: `android.permission.INTERNET` (arrives with the SRI client), plus what WorkManager
     * merges in: `FOREGROUND_SERVICE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `ACCESS_NETWORK_STATE`,
     * and a `SystemForegroundService` component. Phase 6's criterion is that no foreground service
     * is used, so that last one must be removed in the manifest or consciously accepted, not
     * allowlisted by reflex. Add a line here only as a deliberate decision.
     */
    val PERMISSIONS: List<String> = listOf(
        "{applicationId}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    )

    /**
     * Exported components the merged manifest may carry. Every entry is a reviewed line.
     *
     * Today: `androidx.profileinstaller.ProfileInstallReceiver`, merged in by AndroidX. It is
     * exported so that `adb` and the system can trigger baseline-profile installation, and it is
     * protected by `android:permission="android.permission.DUMP"`, which only the shell and the
     * system hold, so no other app can send to it.
     */
    val EXPORTED_COMPONENTS: List<String> = listOf(
        "androidx.profileinstaller.ProfileInstallReceiver",
    )

    /**
     * Services the merged manifest may carry. Starts empty and is truthfully empty: the app and
     * every library on its classpath declare none. Do not add a foreground service (Phase 6).
     */
    val SERVICES: List<String> = emptyList()

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    private const val APPLICATION_ID_TOKEN = "{applicationId}"
    private val COMPONENT_TAGS = listOf("activity", "activity-alias", "service", "receiver", "provider")

    fun violations(
        manifestText: String,
        permissions: List<String>,
        exportedComponents: List<String>,
        services: List<String>,
        applicationId: String,
    ): List<String> {
        val document = parse(manifestText)
            ?: return listOf("merged manifest is not well-formed XML or declares a DOCTYPE")
        val allowedPermissions = resolve(permissions, applicationId)
        val allowedExported = resolve(exportedComponents, applicationId)
        val allowedServices = resolve(services, applicationId)
        val result = sortedSetOf<String>()

        for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
            for (element in elements(document, tag)) {
                val name = nameOf(element)
                if (name !in allowedPermissions) result += "UNEXPECTED PERMISSION $name"
            }
        }
        for (element in elements(document, "permission")) {
            val name = nameOf(element)
            if (name !in allowedPermissions) result += "UNEXPECTED DECLARED PERMISSION $name"
        }
        for (tag in COMPONENT_TAGS) {
            for (element in elements(document, tag)) {
                val name = nameOf(element)
                if (element.getAttributeNS(ANDROID_NS, "exported") == "true" && name !in allowedExported) {
                    result += "UNEXPECTED EXPORTED COMPONENT $name"
                }
                if (tag == "service" && name !in allowedServices) {
                    result += "UNEXPECTED SERVICE $name"
                }
            }
        }
        return result.toList()
    }

    private fun resolve(entries: List<String>, applicationId: String): Set<String> =
        entries.map { it.replace(APPLICATION_ID_TOKEN, applicationId) }.toSet()

    private fun nameOf(element: Element): String = element.getAttributeNS(ANDROID_NS, "name")

    private fun elements(document: Document, tag: String): List<Element> {
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
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
