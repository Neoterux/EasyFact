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

package com.neoterux.easyfact.catalogue

import com.neoterux.easyfact.invoicing.catalogue.CatalogueEntry
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Completeness gate for the Spanish catalogue strings (SC5, D-03, D-08): every [CatalogueEntry] must have a
 * non-blank `<string>` named by its `resourceName` in `src/main/res/values/strings.xml`, and no
 * `catalogue_`-prefixed string may be left without an entry (a stale key after a rename).
 *
 * A plain JVM test on purpose. Robolectric would be a new dependency needing the owner's approval, and it
 * would run the JDK's XML parser anyway; the file is read from disk with JAXP, which exists only in this
 * test source set. The checking logic is [missingOrBlank], a pure function over bytes, so the gate can be
 * proven to fail on a synthetic file without touching the real one.
 *
 * Gradle runs `:app` unit tests with the module directory as the working directory (research A8). The path
 * is asserted to exist, with a message naming it, so a changed working directory fails loudly instead of
 * passing vacuously.
 */
class CatalogueStringsTest {

    private val realFile = File("src/main/res/values/strings.xml")

    private fun realBytes(): ByteArray {
        assertTrue(
            "strings.xml not found at ${realFile.path} relative to the :app module directory " +
                "(working directory: ${File(".").absoluteFile.normalize().path})",
            realFile.isFile,
        )
        return realFile.readBytes()
    }

    @Test
    fun theRealStringsFileExistsAndHoldsAStringPerEntryPlusTheAppName() {
        val strings = stringsOf(realBytes())

        assertEquals(
            "expected exactly one string per entry plus app_name, so a surplus string is a stale key",
            CatalogueEntry.entries.size + 1,
            strings.size,
        )
    }

    @Test
    fun noCatalogueStringIsLeftBehindByARenamedOrRemovedEntry() {
        val named = CatalogueEntry.entries.map { it.resourceName }

        assertEquals(emptyList<String>(), unclaimedCatalogueKeys(named, realBytes()))
    }

    @Test
    fun everyCatalogueEntryHasANonBlankString() {
        val required = CatalogueEntry.entries.map { it.resourceName }

        assertEquals(emptyList<String>(), missingOrBlank(required, realBytes()))
    }

    @Test
    fun catalogueResourceNamesAreDistinct() {
        val names = CatalogueEntry.entries.map { it.resourceName }

        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun theAppNameIsUnchanged() {
        assertEquals("EasyFact", stringsOf(realBytes())["app_name"])
    }

    @Test
    fun theUnrecognisedTokenWarningCarriesTheTokenAsItsOnlyFormatArgument() {
        val value = stringsOf(realBytes()).getValue(CatalogueEntry.WARNING_UNRECOGNISED_TOKEN.resourceName)

        assertTrue("expected %1\$s in: $value", value.contains("%1\$s"))
        assertEquals(1, Regex("%").findAll(value).count())
    }

    @Test
    fun theSequenceAssignedAtIssueNoteIsAPlainStatementWithoutAFormatArgument() {
        val value = stringsOf(realBytes()).getValue(CatalogueEntry.WARNING_SEQUENCE_ASSIGNED_AT_ISSUE.resourceName)

        assertFalse("a format argument in: $value", value.contains("%"))
        assertFalse("a question in: $value", value.contains("?"))
    }

    @Test
    fun theZeroValueWarningsAskForConfirmation() {
        val strings = stringsOf(realBytes())
        val zeroValueEntries = listOf(
            CatalogueEntry.WARNING_ZERO_QUANTITY,
            CatalogueEntry.WARNING_ZERO_LINE_TOTAL,
            CatalogueEntry.WARNING_ZERO_INVOICE_TOTAL,
        )

        for (entry in zeroValueEntries) {
            val value = strings.getValue(entry.resourceName)
            assertTrue("${entry.resourceName} must ask a question: $value", value.contains("?"))
        }
    }

    // --- The checker, proven on synthetic input -----------------------------------------------------

    @Test
    fun theCheckerReportsAMissingAndABlankKeyInInputOrder() {
        val synthetic = """
            <resources>
                <string name="a">x</string>
                <string name="b">   </string>
            </resources>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        assertEquals(listOf("b", "c"), missingOrBlank(listOf("a", "b", "c"), synthetic))
    }

    @Test
    fun theUnclaimedKeyCheckerReportsOnlyCataloguePrefixedKeysNoEntryNames() {
        val synthetic = """
            <resources>
                <string name="app_name">x</string>
                <string name="catalogue_kept">x</string>
                <string name="catalogue_stale">x</string>
                <string name="catalogue_also_stale">x</string>
            </resources>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        assertEquals(
            listOf("catalogue_stale", "catalogue_also_stale"),
            unclaimedCatalogueKeys(listOf("catalogue_kept", "catalogue_missing"), synthetic),
        )
        assertEquals(emptyList<String>(), unclaimedCatalogueKeys(emptyList(), "<resources/>".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun theCheckerReportsAnEmptyElementAsBlank() {
        val synthetic = """<resources><string name="a"/><string name="b">ok</string></resources>"""
            .toByteArray(Charsets.UTF_8)

        assertEquals(listOf("a"), missingOrBlank(listOf("a", "b"), synthetic))
    }

    @Test
    fun theCheckerPassesWhenEveryRequiredKeyHasText() {
        val synthetic = """<resources><string name="a">x</string><string name="b">y</string></resources>"""
            .toByteArray(Charsets.UTF_8)

        assertEquals(emptyList<String>(), missingOrBlank(listOf("a", "b"), synthetic))
    }

    @Test
    fun theCheckerReportsEveryKeyOfAnEmptyResourcesFile() {
        val synthetic = "<resources/>".toByteArray(Charsets.UTF_8)

        assertEquals(listOf("a", "b"), missingOrBlank(listOf("a", "b"), synthetic))
    }
}

/**
 * The names in [required] that [stringsXml] lacks or holds with blank text, in the order given.
 *
 * Reads `<string>` elements by their `name` attribute. A document type declaration is refused, which no
 * resource file has.
 */
internal fun missingOrBlank(required: Collection<String>, stringsXml: ByteArray): List<String> {
    val present = stringsOf(stringsXml)
    return required.filter { present[it].isNullOrBlank() }
}

/**
 * The `catalogue_`-prefixed names in [stringsXml] that no name in [claimed] matches, in file order: a string a
 * renamed or removed entry left behind. Strings outside the prefix, such as `app_name`, are not judged.
 */
internal fun unclaimedCatalogueKeys(claimed: Collection<String>, stringsXml: ByteArray): List<String> {
    val names = claimed.toSet()
    return stringsOf(stringsXml).keys.filter { it.startsWith("catalogue_") && it !in names }
}

/** Every `<string>` of [stringsXml] by name, with the text exactly as the parser decodes it. */
private fun stringsOf(stringsXml: ByteArray): Map<String, String> {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(stringsXml))
    val nodes = document.getElementsByTagName("string")
    val result = LinkedHashMap<String, String>()
    for (index in 0 until nodes.length) {
        val element = nodes.item(index) as Element
        result[element.getAttribute("name")] = element.textContent
    }
    return result
}
