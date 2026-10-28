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

/** Committed failing fixtures for the device-matrix verdict: a matrix that ran nothing must be seen red. */
class DeviceMatrixResultsTest {

    private fun suite(tests: Int, failures: Int = 0, errors: Int = 0, skipped: Int = 0) =
        "<?xml version=\"1.0\"?><testsuite name=\"s\" tests=\"$tests\" failures=\"$failures\" errors=\"$errors\" skipped=\"$skipped\"></testsuite>"

    private val devices = listOf("api31", "api34")

    @Test
    fun everyDeviceThatExecutedTestsPasses() {
        val results = mapOf("api31" to listOf(suite(7)), "api34" to listOf(suite(7)))
        assertEquals(emptyList<String>(), DeviceMatrixResults.problems(devices, results))
    }

    @Test
    fun aMatrixWithNoDeclaredDeviceCertifiesNothing() {
        assertTrue(DeviceMatrixResults.problems(emptyList(), emptyMap()).single().startsWith("NO DEVICES"))
    }

    @Test
    fun aDeclaredDeviceWithoutResultFilesDidNotRun() {
        val results = mapOf("api31" to listOf(suite(7)))
        assertEquals(listOf("NO RESULTS for device api34: it did not run"), DeviceMatrixResults.problems(devices, results))
    }

    @Test
    fun aDeviceThatRanZeroTestsFails() {
        val results = mapOf("api31" to listOf(suite(7)), "api34" to listOf(suite(0)))
        assertEquals(listOf("ZERO TESTS on device api34"), DeviceMatrixResults.problems(devices, results))
    }

    @Test
    fun aDeviceWhoseTestsWereAllSkippedFails() {
        val results = mapOf("api31" to listOf(suite(7)), "api34" to listOf(suite(3, skipped = 3)))
        assertTrue(DeviceMatrixResults.problems(devices, results).single().startsWith("ALL SKIPPED on device api34"))
    }

    @Test
    fun aFailureOrErrorOnAnyDeviceFails() {
        val results = mapOf("api31" to listOf(suite(7, failures = 1)), "api34" to listOf(suite(7, errors = 2)))
        val problems = DeviceMatrixResults.problems(devices, results)
        assertEquals(2, problems.size)
        assertTrue(problems[0].startsWith("FAILED on device api31"))
        assertTrue(problems[1].startsWith("FAILED on device api34"))
    }

    @Test
    fun anUnparseableResultFileCountsAsZeroTests() {
        val results = mapOf("api31" to listOf("not xml"), "api34" to listOf(suite(1)))
        assertEquals(listOf("ZERO TESTS on device api31"), DeviceMatrixResults.problems(devices, results))
    }

    @Test
    fun countersAreSummedAcrossFilesAndSuites() {
        val wrapped = "<testsuites><testsuite tests=\"2\" failures=\"0\" errors=\"0\" skipped=\"0\"/>" +
            "<testsuite tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"1\"/></testsuites>"
        val tally = DeviceMatrixResults.tally(listOf(wrapped, suite(4)))
        assertEquals(9, tally.tests)
        assertEquals(1, tally.skipped)
        assertEquals(8, tally.executed)
    }
}
