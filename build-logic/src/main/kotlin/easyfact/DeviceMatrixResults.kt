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
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Pure verdict over the JUnit XML that the managed-device run left behind. No Gradle types, so it runs
 * in a JVM unit test.
 *
 * It exists because a device-test task's exit code is not evidence that a test ran: an emulator that
 * never booted, a group with no device, or a runner that filtered everything out can all finish with
 * success. A matrix that measured nothing must fail (NFR-07, threat T-02-20), so the verdict is taken
 * from the result files, per declared device.
 */
object DeviceMatrixResults {

    /** What one device's result files add up to. */
    data class DeviceTally(val tests: Int, val failures: Int, val errors: Int, val skipped: Int) {
        val executed: Int get() = tests - skipped
    }

    /**
     * Violation lines for [expectedDevices] given [resultsByDevice], the JUnit XML text of every result file
     * found for each device. Empty when every declared device executed at least one test and none failed.
     */
    fun problems(expectedDevices: List<String>, resultsByDevice: Map<String, List<String>>): List<String> {
        if (expectedDevices.isEmpty()) return listOf("NO DEVICES declared: the matrix would certify nothing")
        val result = ArrayList<String>()
        for (device in expectedDevices) {
            val files = resultsByDevice[device].orEmpty()
            if (files.isEmpty()) {
                result += "NO RESULTS for device $device: it did not run"
                continue
            }
            val tally = tally(files)
            when {
                tally.tests == 0 -> result += "ZERO TESTS on device $device"
                tally.executed == 0 -> result += "ALL SKIPPED on device $device (${tally.skipped} skipped)"
                tally.failures + tally.errors > 0 ->
                    result += "FAILED on device $device (${tally.failures} failures, ${tally.errors} errors of ${tally.tests})"
            }
        }
        return result
    }

    /** The one-line summary printed for a device that passed. */
    fun describe(device: String, files: List<String>): String {
        val t = tally(files)
        return "$device: ${t.tests} tests, ${t.failures} failures, ${t.errors} errors, ${t.skipped} skipped"
    }

    /** Sums the `<testsuite>` counters of every file; a file that is not parseable counts as zero tests. */
    fun tally(files: List<String>): DeviceTally {
        var tests = 0
        var failures = 0
        var errors = 0
        var skipped = 0
        for (text in files) {
            val suite = parseRoot(text) ?: continue
            val suites = if (suite.tagName == "testsuites") {
                (0 until suite.childNodes.length).mapNotNull { suite.childNodes.item(it) as? Element }.filter { it.tagName == "testsuite" }
            } else {
                listOf(suite)
            }
            for (s in suites) {
                tests += s.getAttribute("tests").toIntOrNull() ?: 0
                failures += s.getAttribute("failures").toIntOrNull() ?: 0
                errors += s.getAttribute("errors").toIntOrNull() ?: 0
                skipped += s.getAttribute("skipped").toIntOrNull() ?: 0
            }
        }
        return DeviceTally(tests, failures, errors, skipped)
    }

    private fun parseRoot(text: String): Element? = try {
        DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(InputSource(StringReader(text))).documentElement
    } catch (e: Exception) {
        null
    }
}
