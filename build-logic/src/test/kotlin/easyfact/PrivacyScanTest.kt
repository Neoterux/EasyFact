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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Committed proof that the privacy gate can fail (a gate nobody has seen red proves nothing).
 *
 * This file is itself tracked, so it is scanned by the gate it tests. Every identifier-shaped
 * fixture below is therefore assembled at run time from short fragments and never written as a
 * source literal; a literal would make `verifyPrivacyScan` fail on its own evidence.
 */
class PrivacyScanTest {

    /** Ten repeated nines: the placeholder RUC base. */
    private val rucBase = "9".repeat(10)

    /** The allowlisted placeholder RUC, assembled from [rucBase] and the establishment suffix. */
    private val placeholderRuc = rucBase + "001"

    private fun digits(count: Int, digit: Char = '1') = digit.toString().repeat(count)

    private fun file(vararg lines: String, path: String = "src/Example.kt") =
        PrivacyScan.ScannedFile(path, lines.toList())

    /** A 49-digit key whose RUC window is [rucSegment]; every other segment is a dummy. */
    private fun accessKey(rucSegment: String): String {
        require(rucSegment.length == 13)
        val date = "01" + "01" + "2026"
        val documentType = "01"
        val environment = "1"
        val series = "001" + "001"
        val sequential = "000000" + "001"
        val numericCode = "1234" + "5678"
        val emissionType = "1"
        val checkDigit = "0"
        return date + documentType + rucSegment + environment + series + sequential + numericCode +
            emissionType + checkDigit
    }

    @Test
    fun cleanFileSetHasNoViolations() {
        val files = listOf(
            file("fun main() = println(\"hello\")", "val version = 42", path = "src/A.kt"),
            file("plain prose with no identifiers at all", path = "docs/B.md"),
        )

        assertEquals(emptyList<String>(), PrivacyScan.violations(files))
    }

    @Test
    fun thirteenDigitRunIsReportedAsRuc13WithoutTheValue() {
        val value = digits(13)

        val result = PrivacyScan.violations(listOf(file("val x = \"$value\"")))

        assertEquals(listOf("PRIVACY ruc13 src/Example.kt:1"), result)
        assertFalse("the matched digits must not be published", result.joinToString().contains(value))
    }

    @Test
    fun tenDigitRunIsReportedAsCedula10() {
        val result = PrivacyScan.violations(listOf(file("id: " + digits(10, '7'))))

        assertEquals(listOf("PRIVACY cedula10 src/Example.kt:1"), result)
    }

    @Test
    fun fortyNineDigitKeyWithPlaceholderRucSegmentIsAllowed() {
        val key = accessKey(placeholderRuc)
        assertEquals(49, key.length)

        assertEquals(emptyList<String>(), PrivacyScan.violations(listOf(file("key=$key"))))
    }

    @Test
    fun fortyNineDigitKeyWithAnyOtherRucSegmentIsReportedOnce() {
        val key = accessKey(digits(13, '4'))
        assertEquals(49, key.length)

        val result = PrivacyScan.violations(listOf(file("key=$key")))

        assertEquals(listOf("PRIVACY accessKey49 src/Example.kt:1"), result)
    }

    @Test
    fun hexChecksumWrapperHashAndEpochMillisAreNotIdentifiers() {
        val sha1 = "a1b2c3d4e5".repeat(4)
        val wrapperLine = "distributionSha256Sum=" + "f" + digits(13) + "e" + "0ab1".repeat(12)
        val epoch = "val at = " + digits(13) + "L"

        val files = listOf(file("sha1=$sha1", wrapperLine, epoch))

        assertEquals(emptyList<String>(), PrivacyScan.violations(files))
    }

    @Test
    fun pemMarkerIsReported() {
        val marker = "-".repeat(5) + "BEGIN " + "PRIVATE" + " KEY" + "-".repeat(5)

        assertEquals(listOf("PRIVACY pemMarker src/Example.kt:1"), PrivacyScan.violations(listOf(file(marker))))
    }

    @Test
    fun pkcs12MarkerIsReported() {
        val blob = "MII" + "J".repeat(200)

        assertEquals(listOf("PRIVACY pkcs12Marker src/Example.kt:1"), PrivacyScan.violations(listOf(file("p=$blob"))))
    }

    @Test
    fun nonExampleEmailIsReported() {
        val address = "someone" + "@" + "other.invalid"

        assertEquals(listOf("PRIVACY email src/Example.kt:1"), PrivacyScan.violations(listOf(file("to $address"))))
    }

    @Test
    fun exampleDotComAddressesAreAllowed() {
        val lines = arrayOf("a" + "@" + "example.com", "b" + "@" + "sub.example.com")

        assertEquals(emptyList<String>(), PrivacyScan.violations(listOf(file(*lines))))
    }

    @Test
    fun violationsAreSortedAndDeduplicated() {
        val twoOnOneLine = digits(13) + " " + digits(13, '2')
        val files = listOf(
            file("clean", twoOnOneLine, path = "b.kt"),
            file(digits(13), path = "a.kt"),
        )

        assertEquals(
            listOf("PRIVACY ruc13 a.kt:1", "PRIVACY ruc13 b.kt:2"),
            PrivacyScan.violations(files),
        )
    }

    @Test
    fun rulesAreSixAndNamedInOrder() {
        assertEquals(
            listOf("ruc13", "cedula10", "accessKey49", "pemMarker", "pkcs12Marker", "email"),
            PrivacyScan.RULES.map { it.name },
        )
    }

    @Test
    fun emptyFileListFailsThePrecondition() {
        assertTrue(PrivacyScan.preconditionViolations(emptyList()).isNotEmpty())
        assertEquals(emptyList<String>(), PrivacyScan.preconditionViolations(listOf(file("x"))))
    }

    @Test
    fun residualGapNamesTheBlindSpots() {
        val gap = PrivacyScan.RESIDUAL_GAP.lowercase()

        assertTrue("runtime concatenation", "concatenat" in gap)
        assertTrue("base64", "base64" in gap)
        assertTrue("binary", "binary" in gap)
        assertTrue("untracked files", "untracked" in gap)
        assertTrue("git history", "history" in gap)
    }
}
