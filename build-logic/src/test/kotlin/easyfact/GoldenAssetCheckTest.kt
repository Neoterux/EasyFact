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
        cases=a,m,b,c
        signingTime=2026-10-27T12:00-05:00
        firstId=100000
        unsigned=unsigned.xml
        case.a.p12=a.p12
        case.a.encoding=LEGACY_PBE
        case.a.expect=SIGNS
        case.a.expectedSigned=a.expected-signed.xml
        case.a.opensFromApi=0
        case.m.p12=m.p12
        case.m.encoding=MODERN_AES
        case.m.expect=SIGNS
        case.m.expectedSigned=m.expected-signed.xml
        case.m.opensFromApi=34
        case.b.p12=b.p12
        case.b.encoding=LEGACY_PBE
        case.b.expect=REJECTS_PASSWORD
        case.b.opensFromApi=0
        case.b.controlPassword=control
        case.c.p12=c.p12
        case.c.encoding=MODERN_AES
        case.c.expect=REJECTS_PASSWORD
        case.c.opensFromApi=34
        case.c.controlPassword=control
    """.trimIndent()

    private val present = mapOf(
        "unsigned.xml" to 10L,
        "a.p12" to 5L, "a.expected-signed.xml" to 20L,
        "m.p12" to 5L, "m.expected-signed.xml" to 20L,
        "b.p12" to 5L,
        "c.p12" to 5L,
    )

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
        val broken = manifest.replace("case.c.expect=REJECTS_PASSWORD", "case.c.expect=SKIPPED")
        assertTrue(check(broken).single().startsWith("UNKNOWN expectation 'SKIPPED' for case c"))
    }

    @Test
    fun aSigningCaseWithoutAnApiExpectationIsAProblem() {
        val broken = manifest.replace("case.a.opensFromApi=0\n", "")
        // The invalid case drops out of the measurement, so the rejection case of its encoding is reported as unmeasured too.
        val problems = check(broken)
        assertTrue(problems.first().startsWith("MISSING or invalid manifest entry a opensFromApi"))
        assertEquals(2, problems.size)
    }

    @Test
    fun aNonNumericApiExpectationIsAProblem() {
        val broken = manifest.replace("case.a.opensFromApi=0", "case.a.opensFromApi=latest")
        val problems = check(broken)
        assertTrue(problems.first().startsWith("MISSING or invalid manifest entry a opensFromApi ('latest')"))
        assertEquals(2, problems.size)
    }

    @Test
    fun aNonZeroApiExpectationIsAccepted() {
        assertEquals(emptyList<String>(), check(manifest.replace("opensFromApi=34", "opensFromApi=35")))
    }

    @Test
    fun aRejectionCaseWithoutAControlPasswordIsAProblem() {
        val broken = manifest.replace("case.b.controlPassword=control\n", "")
        assertEquals(
            listOf(
                "MISSING manifest entry b controlPassword: a rejection case must open its store with the correct " +
                    "password before the wrong one is tried",
            ),
            check(broken),
        )
    }

    @Test
    fun aRejectionCaseWithABlankControlPasswordIsAProblem() {
        val broken = manifest.replace("case.b.controlPassword=control", "case.b.controlPassword=   ")
        assertTrue(check(broken).single().startsWith("MISSING manifest entry b controlPassword"))
    }

    @Test
    fun aRejectionCaseWithoutAnApiExpectationIsAProblem() {
        val broken = manifest.replace("case.c.opensFromApi=34\n", "")
        assertEquals(
            listOf("MISSING or invalid manifest entry c opensFromApi ('null'): a non-negative API level is required"),
            check(broken),
        )
    }

    @Test
    fun aRejectionCaseWithANonNumericApiExpectationIsAProblem() {
        val broken = manifest.replace("case.c.opensFromApi=34", "case.c.opensFromApi=latest")
        assertEquals(
            listOf("MISSING or invalid manifest entry c opensFromApi ('latest'): a non-negative API level is required"),
            check(broken),
        )
    }

    @Test
    fun aRejectionCaseWithoutAnEncodingIsAProblem() {
        val broken = manifest.replace("case.b.encoding=LEGACY_PBE\n", "")
        assertEquals(listOf("MISSING manifest entry b encoding"), check(broken))
    }

    /** The configuration that shipped before this plan: wrong-password on the AES encoding at level 0 while the AES signing case opens from 34. */
    @Test
    fun aRejectionCaseThatOpensBelowTheSigningCaseOfTheSameEncodingIsAProblemNamingBoth() {
        val preFix = manifest.replace("case.c.opensFromApi=34", "case.c.opensFromApi=0")
        assertEquals(
            listOf(
                "VACUOUS rejection case c claims to open from API 0, below API 34 where signing case m of the same " +
                    "encoding MODERN_AES is measured to open: below API 34 the store is refused for its encoding, not its password",
            ),
            check(preFix),
        )
    }

    @Test
    fun aRejectionCaseOfAnEncodingNoSigningCaseMeasuresIsAProblem() {
        val broken = manifest.replace("case.c.encoding=MODERN_AES", "case.c.encoding=CHACHA")
        assertEquals(
            listOf(
                "UNMEASURED rejection case c uses encoding CHACHA, which no signing case measures: " +
                    "its opensFromApi has nothing to be checked against",
            ),
            check(broken),
        )
    }

    @Test
    fun aManifestWithNoRejectionCaseOnEveryLevelIsAProblem() {
        val broken = manifest.replace("case.b.opensFromApi=0", "case.b.opensFromApi=34")
        assertEquals(
            listOf(
                "MISSING rejection case with opensFromApi 0: no password rejection runs on every API level, " +
                    "so the minimum level could pass vacuously",
            ),
            check(broken),
        )
    }

    @Test
    fun aManifestWithNoRejectionCaseAtAllIsAProblem() {
        val broken = manifest.replace("cases=a,m,b,c", "cases=a,m")
        assertEquals(
            listOf(
                "MISSING rejection case with opensFromApi 0: no password rejection runs on every API level, " +
                    "so the minimum level could pass vacuously",
            ),
            check(broken),
        )
    }
}
