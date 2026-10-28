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
import java.util.Properties

/**
 * Pure completeness check over the golden signing asset manifest. No Gradle types, so it runs in a
 * JVM unit test.
 *
 * A generator that exits zero having written nothing, or a manifest that names a file the generator
 * never wrote, would let the device matrix "pass" against nothing. This reports every such gap as a
 * line; an empty result means the manifest and the files agree. The manifest format is written by
 * `GoldenAssetGenerator` in `:core:testing`; the keys read here are the ones the device test reads.
 *
 * It also refuses a rejection case that could pass vacuously (WR-08). A `REJECTS_PASSWORD` case must
 * carry a `controlPassword` (the device opens the store with it before trying the wrong one) and an
 * `encoding`; it may not claim to open from a lower API level than a signing case of the same encoding is
 * measured to open from (below that level the store is refused for its encoding, so the refusal says
 * nothing about the password); that encoding must be measured by at least one signing case; and at least
 * one rejection case must open on every level (`opensFromApi` 0), so the minimum supported level always
 * runs one real password rejection.
 */
object GoldenAssetCheck {

    private val EXPECTATIONS = setOf("SIGNS", "REJECTS_PASSWORD")

    /**
     * @param manifestText the manifest content, or `null` when the file does not exist
     * @param sizeOf the size in bytes of the file at a path relative to the `golden/` directory, or `-1` when it is absent
     */
    fun problems(manifestText: String?, sizeOf: (String) -> Long): List<String> {
        if (manifestText == null) return listOf("MISSING golden/manifest.properties")
        val manifest = Properties().apply { load(StringReader(manifestText)) }
        val cases = manifest.getProperty("cases").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (cases.isEmpty()) return listOf("EMPTY manifest names no case")

        val result = ArrayList<String>()
        fun requireFile(label: String, name: String?) {
            when {
                name.isNullOrBlank() -> result += "MISSING manifest entry $label"
                sizeOf(name) < 0 -> result += "MISSING file $name ($label)"
                sizeOf(name) == 0L -> result += "EMPTY file $name ($label)"
            }
        }
        requireFile("unsigned", manifest.getProperty("unsigned"))
        for (key in listOf("signingTime", "firstId")) {
            if (manifest.getProperty(key).isNullOrBlank()) result += "MISSING manifest entry $key"
        }
        for (case in cases) {
            val prefix = "case.$case"
            requireFile("$case p12", manifest.getProperty("$prefix.p12"))
            when (val expect = manifest.getProperty("$prefix.expect")) {
                "SIGNS" -> {
                    requireFile("$case expectedSigned", manifest.getProperty("$prefix.expectedSigned"))
                    val opensFrom = manifest.getProperty("$prefix.opensFromApi")
                    if (opensFrom?.trim()?.toIntOrNull()?.let { it >= 0 } != true) {
                        result += "MISSING or invalid manifest entry $case opensFromApi ('$opensFrom'): a non-negative API level is required"
                    }
                }
                "REJECTS_PASSWORD" -> {
                    if (manifest.getProperty("$prefix.controlPassword").isNullOrBlank()) {
                        result += "MISSING manifest entry $case controlPassword: a rejection case must open its store " +
                            "with the correct password before the wrong one is tried"
                    }
                    if (manifest.getProperty("$prefix.encoding").isNullOrBlank()) {
                        result += "MISSING manifest entry $case encoding"
                    }
                    val opensFrom = manifest.getProperty("$prefix.opensFromApi")
                    if (opensFrom?.trim()?.toIntOrNull()?.let { it >= 0 } != true) {
                        result += "MISSING or invalid manifest entry $case opensFromApi ('$opensFrom'): a non-negative API level is required"
                    }
                }
                else -> result += "UNKNOWN expectation '$expect' for case $case (expected one of $EXPECTATIONS)"
            }
        }
        result += rejectionRules(manifest, cases)
        return result
    }

    private class Described(val id: String, val encoding: String, val opensFromApi: Int)

    /** The manifest-level rules over the rejection cases; a case whose own entries are invalid is already reported and skipped here. */
    private fun rejectionRules(manifest: Properties, cases: List<String>): List<String> {
        fun described(expectation: String) = cases.filter { manifest.getProperty("case.$it.expect") == expectation }.mapNotNull {
            val encoding = manifest.getProperty("case.$it.encoding")?.trim().orEmpty()
            val opensFrom = manifest.getProperty("case.$it.opensFromApi")?.trim()?.toIntOrNull()
            if (encoding.isEmpty() || opensFrom == null || opensFrom < 0) null else Described(it, encoding, opensFrom)
        }
        val signing = described("SIGNS")
        val rejecting = described("REJECTS_PASSWORD")

        val result = ArrayList<String>()
        for (rejection in rejecting) {
            val measuring = signing.filter { it.encoding == rejection.encoding }
            if (measuring.isEmpty()) {
                result += "UNMEASURED rejection case ${rejection.id} uses encoding ${rejection.encoding}, which no signing " +
                    "case measures: its opensFromApi has nothing to be checked against"
                continue
            }
            val strictest = measuring.maxByOrNull { it.opensFromApi }!!
            if (rejection.opensFromApi < strictest.opensFromApi) {
                result += "VACUOUS rejection case ${rejection.id} claims to open from API ${rejection.opensFromApi}, below " +
                    "API ${strictest.opensFromApi} where signing case ${strictest.id} of the same encoding ${rejection.encoding} " +
                    "is measured to open: below API ${strictest.opensFromApi} the store is refused for its encoding, not its password"
            }
        }
        val rejectsEverywhere = cases.any {
            manifest.getProperty("case.$it.expect") == "REJECTS_PASSWORD" &&
                manifest.getProperty("case.$it.opensFromApi")?.trim()?.toIntOrNull() == 0
        }
        if (!rejectsEverywhere) {
            result += "MISSING rejection case with opensFromApi 0: no password rejection runs on every API level, " +
                "so the minimum level could pass vacuously"
        }
        return result
    }
}
