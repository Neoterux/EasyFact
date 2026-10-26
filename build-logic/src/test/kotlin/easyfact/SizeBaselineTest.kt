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

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Committed failing fixtures for the size gate: the over-baseline case is the proof that
 * `checkReleaseSize` can be red. All numbers are synthetic.
 */
class SizeBaselineTest {

    private val wellFormed = """
        # Synthetic baseline
        # 2026-01-01: initial (apk.bytes 1000, aab.bytes 2000)
        apk.bytes=1000
        aab.bytes=2000
    """.trimIndent()

    @Test
    fun wellFormedBaselineParses() {
        assertEquals(SizeBaseline.Sizes(1000, 2000), SizeBaseline.parse(wellFormed))
    }

    @Test
    fun missingAabKeyRaisesInsteadOfYieldingZero() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SizeBaseline.parse("apk.bytes=1000")
        }

        assertTrue(failure.message, failure.message!!.contains("aab.bytes"))
    }

    @Test
    fun nonNumericDuplicateAndZeroValuesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SizeBaseline.parse("apk.bytes=big\naab.bytes=2") }
        assertThrows(IllegalArgumentException::class.java) { SizeBaseline.parse("apk.bytes=1\napk.bytes=2\naab.bytes=2") }
        assertThrows(IllegalArgumentException::class.java) { SizeBaseline.parse("apk.bytes=0\naab.bytes=2") }
    }

    @Test
    fun measuredEqualToBaselineAndWithinToleranceBothPass() {
        val baseline = SizeBaseline.Sizes(1000, 2000)

        assertEquals(emptyList<String>(), SizeBaseline.violations(baseline, baseline, toleranceBytes = 0))
        assertEquals(emptyList<String>(), SizeBaseline.violations(SizeBaseline.Sizes(1010, 2010), baseline, toleranceBytes = 10))
        assertEquals(emptyList<String>(), SizeBaseline.violations(SizeBaseline.Sizes(1, 1), baseline, toleranceBytes = 0))
    }

    @Test
    fun oneByteOverBaselinePlusToleranceYieldsAViolationNamingBothNumbers() {
        val baseline = SizeBaseline.Sizes(1000, 2000)

        val hits = SizeBaseline.violations(SizeBaseline.Sizes(1011, 2000), baseline, toleranceBytes = 10)

        assertEquals(1, hits.size)
        assertTrue(hits.single(), hits.single().contains("1011") && hits.single().contains("1010"))
        assertTrue(hits.single(), hits.single().startsWith("Release APK"))
    }

    @Test
    fun bundleOverBaselineIsReportedSeparately() {
        val hits = SizeBaseline.violations(SizeBaseline.Sizes(1000, 2001), SizeBaseline.Sizes(1000, 2000), toleranceBytes = 0)

        assertEquals(1, hits.size)
        assertTrue(hits.single(), hits.single().startsWith("Release App Bundle"))
    }

    @Test
    fun renderKeepsHistoryAndAppendsExactlyOneLine() {
        val rendered = SizeBaseline.render(
            existing = wellFormed,
            updated = SizeBaseline.Sizes(1500, 2500),
            previous = SizeBaseline.Sizes(1000, 2000),
            reason = "added a reviewed dependency",
            date = "2026-02-02",
        )

        val comments = rendered.lines().filter { it.startsWith("#") }
        val newLine = "# 2026-02-02: added a reviewed dependency (apk.bytes 1000 -> 1500, aab.bytes 2000 -> 2500)"
        assertEquals(wellFormed.lines().filter { it.startsWith("#") } + newLine, comments)
        assertEquals(SizeBaseline.Sizes(1500, 2500), SizeBaseline.parse(rendered))
        assertEquals(1, rendered.lines().count { it.startsWith("apk.bytes=") })
    }

    @Test
    fun reasonCannotForgeAKeyLine() {
        val rendered = SizeBaseline.render(
            existing = wellFormed,
            updated = SizeBaseline.Sizes(1500, 2500),
            previous = null,
            reason = "sneaky\napk.bytes=1",
            date = "2026-02-02",
        )

        assertEquals(SizeBaseline.Sizes(1500, 2500), SizeBaseline.parse(rendered))
    }

    @Test
    fun atomicWriteReplacesTheTargetAndLeavesNoTemporaryFile() {
        val directory = Files.createTempDirectory("size-baseline-test").toFile()
        try {
            val target = directory.resolve("size-baseline.properties")
            target.writeText("old")

            SizeBaseline.writeAtomically(target, "new")

            assertEquals("new", target.readText())
            assertEquals(listOf("size-baseline.properties"), directory.list()!!.toList())
            assertFalse(directory.list()!!.any { it.endsWith(".tmp") })
        } finally {
            directory.deleteRecursively()
        }
    }
}
