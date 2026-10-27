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

/**
 * Pure identifier-shape policy over the text of git-tracked files. No Gradle types, so it runs in
 * a JVM unit test.
 *
 * Placeholder stub: the rules are declared so the tests compile, and no rule fires yet.
 */
object PrivacyScan {

    data class Rule(val name: String, val pattern: Regex)

    data class ScannedFile(val path: String, val lines: List<String>)

    data class Allowlist(val syntheticRuc: String, val allowedEmailDomain: String) {
        companion object {
            val DEFAULT: Allowlist = Allowlist("9".repeat(10) + "001", "example.com")
        }
    }

    val RULES: List<Rule> = listOf(
        Rule("ruc13", Regex("(?!)")),
        Rule("cedula10", Regex("(?!)")),
        Rule("accessKey49", Regex("(?!)")),
        Rule("pemMarker", Regex("(?!)")),
        Rule("pkcs12Marker", Regex("(?!)")),
        Rule("email", Regex("(?!)")),
    )

    const val RESIDUAL_GAP: String = ""

    fun violations(files: List<ScannedFile>, allowlist: Allowlist = Allowlist.DEFAULT): List<String> = emptyList()

    fun preconditionViolations(files: List<ScannedFile>): List<String> = emptyList()
}
