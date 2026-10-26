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

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Pure policy over the committed release-size baseline (`app/size-baseline.properties`).
 *
 * The format is `key=value` lines plus `#` comments. The two keys are `apk.bytes` and
 * `aab.bytes`. Comments carry the dated history of every change and are preserved verbatim by
 * [render], so the file records why each number moved.
 *
 * No Gradle types: the policy runs in a JVM unit test.
 */
object SizeBaseline {

    const val APK_KEY = "apk.bytes"
    const val AAB_KEY = "aab.bytes"

    /** Measured or recorded sizes, in bytes. */
    data class Sizes(val apkBytes: Long, val aabBytes: Long)

    /**
     * Parses [text] into [Sizes]. A missing, duplicated or non-numeric key is rejected rather than
     * defaulted: a zero baseline would make the gate fail every build, and a silently ignored key
     * could make it certify nothing.
     */
    fun parse(text: String): Sizes {
        val values = HashMap<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed baseline line (expected key=value): '$line'" }
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(values.put(key, value) == null) { "Baseline key '$key' appears more than once" }
        }
        return Sizes(apkBytes = number(values, APK_KEY), aabBytes = number(values, AAB_KEY))
    }

    private fun number(values: Map<String, String>, key: String): Long {
        val raw = requireNotNull(values[key]) { "Baseline is missing required key '$key'" }
        val parsed = raw.toLongOrNull()
        require(parsed != null && parsed > 0) { "Baseline key '$key' must be a positive integer byte count, was '$raw'" }
        return parsed
    }

    /**
     * Returns one message per artifact that exceeds `baseline + toleranceBytes`, each naming the
     * measured size and the allowed size. Smaller artifacts are never a violation.
     */
    fun violations(measured: Sizes, baseline: Sizes, toleranceBytes: Long): List<String> {
        val result = ArrayList<String>()
        if (measured.apkBytes > baseline.apkBytes + toleranceBytes) {
            result += over("APK", measured.apkBytes, baseline.apkBytes, toleranceBytes)
        }
        if (measured.aabBytes > baseline.aabBytes + toleranceBytes) {
            result += over("App Bundle", measured.aabBytes, baseline.aabBytes, toleranceBytes)
        }
        return result
    }

    private fun over(label: String, measured: Long, baseline: Long, tolerance: Long) =
        "Release $label is $measured bytes, baseline allows ${baseline + tolerance} " +
            "(baseline $baseline + tolerance $tolerance)"

    /**
     * Renders a new baseline body from [existing]: every existing comment line is kept in order,
     * exactly one new dated history line is appended, and the two key lines are rewritten with
     * [updated]. [reason] is collapsed to a single line so it can never smuggle in a forged
     * `key=value` line.
     */
    fun render(existing: String, updated: Sizes, previous: Sizes?, reason: String, date: String): String {
        val comments = existing.lines()
            .filter { it.trim().startsWith("#") }
            .map { it.trimEnd() }
        val oneLineReason = reason.trim().replace(Regex("\\s+"), " ")
        val before = previous?.let { "apk.bytes ${it.apkBytes} -> ${updated.apkBytes}, aab.bytes ${it.aabBytes} -> ${updated.aabBytes}" }
            ?: "apk.bytes ${updated.apkBytes}, aab.bytes ${updated.aabBytes}"
        val history = "# $date: $oneLineReason ($before)"
        return buildString {
            comments.forEach { appendLine(it) }
            appendLine(history)
            appendLine("$APK_KEY=${updated.apkBytes}")
            appendLine("$AAB_KEY=${updated.aabBytes}")
        }
    }

    /**
     * Writes [content] to [target] through a temporary file in the same directory followed by an
     * atomic rename, so an interrupted run leaves either the previous file or the new one, never a
     * truncated file that a later comparison could misread.
     */
    fun writeAtomically(target: File, content: String) {
        val directory = target.absoluteFile.parentFile
        val temporary = Files.createTempFile(directory.toPath(), target.name, ".tmp")
        try {
            Files.write(temporary, content.toByteArray(StandardCharsets.UTF_8))
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: IOException) {
            Files.deleteIfExists(temporary)
            throw failure
        }
    }
}
