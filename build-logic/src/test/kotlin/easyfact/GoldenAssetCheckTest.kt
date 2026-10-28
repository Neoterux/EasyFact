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
import org.junit.Assert.assertTrue
import org.junit.Test

/** Committed failing fixtures for the golden asset completeness check: it must be seen red, not only green. */
class GoldenAssetCheckTest {

    private val manifest = """
        cases=a,b
        signingTime=2026-10-27T12:00-05:00
        firstId=100000
        unsigned=unsigned.xml
        case.a.p12=a.p12
        case.a.expect=SIGNS
        case.a.expectedSigned=a.expected-signed.xml
        case.a.opensFromApi=0
        case.b.p12=b.p12
        case.b.expect=REJECTS_PASSWORD
    """.trimIndent()

    private val present = mapOf("unsigned.xml" to 10L, "a.p12" to 5L, "a.expected-signed.xml" to 20L, "b.p12" to 5L)

    private fun check(text: String?, files: Map<String, Long> = present) =
        GoldenAssetCheck.problems(text) { files[it] ?: -1L }

    @Test
    fun aCompleteManifestHasNoProblems() {
        assertEquals(emptyList<String>(), check(manifest))
    }

    @Test
    fun aMissingManifestIsAProblem() {
        assertEquals(listOf("MISSING golden/manifest.properties"), check(null))
    }

    @Test
    fun aManifestThatNamesNoCaseIsAProblem() {
        assertTrue(check("cases=\nsigningTime=x\nfirstId=1\nunsigned=u").first().startsWith("EMPTY"))
    }

    @Test
    fun aNamedFileThatWasNeverWrittenIsAProblem() {
        assertEquals(listOf("MISSING file a.p12 (a p12)"), check(manifest, present - "a.p12"))
    }

    @Test
    fun anEmptyExpectedDocumentIsAProblem() {
        assertEquals(
            listOf("EMPTY file a.expected-signed.xml (a expectedSigned)"),
            check(manifest, present + ("a.expected-signed.xml" to 0L)),
        )
    }

    @Test
    fun aCaseThatShouldSignButHasNoExpectedDocumentEntryIsAProblem() {
        val broken = manifest.replace("case.a.expectedSigned=a.expected-signed.xml\n", "")
        assertEquals(listOf("MISSING manifest entry a expectedSigned"), check(broken))
    }

    @Test
    fun anUnknownExpectationIsAProblem() {
        val broken = manifest.replace("REJECTS_PASSWORD", "SKIPPED")
        assertTrue(check(broken).single().startsWith("UNKNOWN expectation 'SKIPPED' for case b"))
    }

    @Test
    fun aSigningCaseWithoutAnApiExpectationIsAProblem() {
        val broken = manifest.replace("case.a.opensFromApi=0\n", "")
        assertTrue(check(broken).single().startsWith("MISSING or invalid manifest entry a opensFromApi"))
    }

    @Test
    fun aNonNumericApiExpectationIsAProblem() {
        val broken = manifest.replace("opensFromApi=0", "opensFromApi=latest")
        assertTrue(check(broken).single().startsWith("MISSING or invalid manifest entry a opensFromApi ('latest')"))
    }

    @Test
    fun aNonZeroApiExpectationIsAccepted() {
        assertEquals(emptyList<String>(), check(manifest.replace("opensFromApi=0", "opensFromApi=34")))
    }
}
