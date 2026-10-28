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
                "REJECTS_PASSWORD" -> Unit
                else -> result += "UNKNOWN expectation '$expect' for case $case (expected one of $EXPECTATIONS)"
            }
        }
        return result
    }
}
