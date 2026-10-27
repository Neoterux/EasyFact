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
 * The repository is public (AGENTS.md section 2.2), so a real identification number, a key
 * marker or a real mailbox in a tracked file is a permanent publication. This policy recognises
 * the *shape* of such values, because a shape is all a machine can check; [RESIDUAL_GAP] says what
 * that leaves uncovered.
 *
 * The matched text is never placed in a message. A violation names the rule, the path and the
 * line, and nothing else: a build log is itself a publication channel, and echoing the offending
 * value into it would republish exactly what the gate exists to stop.
 *
 * This source file is scanned by the gate it implements, so no identifier-shaped literal appears
 * in it. The placeholder values are assembled from fragments.
 */
object PrivacyScan {

    /** One identifier shape. [name] is the token printed in a violation line. */
    data class Rule(val name: String, val pattern: Regex)

    /** The text of one tracked file, split into lines. [path] is repository-relative. */
    data class ScannedFile(val path: String, val lines: List<String>)

    /**
     * The synthetic values the policy accepts.
     *
     * [syntheticRuc] is the placeholder RUC the shared test fixtures publish. It is accepted as a
     * 13-digit run, as the RUC window of a 49-digit access key, and, through its first ten digits,
     * as a 10-digit run. Nothing else is accepted: a run that differs from the placeholder by one
     * digit is a violation. [allowedEmailDomain] covers the domain itself and its subdomains.
     */
    data class Allowlist(val syntheticRuc: String, val allowedEmailDomain: String) {
        companion object {
            /** Built from fragments so this file carries no identifier-shaped literal. */
            val DEFAULT: Allowlist = Allowlist("9".repeat(10) + "001", "example.com")
        }
    }

    // A digit run counts only when it is bounded by something that is not a letter or a digit, so a
    // hex checksum, a Gradle wrapper hash or a `...L` epoch literal never matches by accident.
    private const val NOT_ALNUM_BEFORE = "(?<![0-9A-Za-z])"
    private const val NOT_ALNUM_AFTER = "(?![0-9A-Za-z])"

    private fun exactDigitRun(length: Int) = Regex("$NOT_ALNUM_BEFORE[0-9]{$length}$NOT_ALNUM_AFTER")

    private val RUC_13 = exactDigitRun(13)
    private val CEDULA_10 = exactDigitRun(10)
    private val ACCESS_KEY_49 = exactDigitRun(49)

    /** Matches an email address; the domain is judged against [Allowlist.allowedEmailDomain] afterwards. */
    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,})")

    /**
     * Zero-based window of the issuer RUC inside a 49-digit access key: eight digits of date and
     * two of document type come first, then thirteen digits of RUC.
     */
    private const val ACCESS_KEY_RUC_START = 10
    private const val ACCESS_KEY_RUC_END = 23

    /**
     * The six shapes, in the order they are evaluated and reported.
     *
     * `pemMarker` is the textual armour that precedes any PEM block (certificate or key).
     * `pkcs12Marker` is the base64 form a PKCS#12 store takes when it is pasted into text such as
     * a CI secret: a long base64 run starting with the DER sequence prefix. It also catches any
     * other base64-encoded DER blob, which is key material too.
     *
     * Each digit rule matches a run of *exactly* its length, so a key that is allowlisted by its
     * RUC window cannot also fire the shorter rules on the same span: no 13-digit or 10-digit run
     * exists inside a 49-digit one.
     */
    val RULES: List<Rule> = listOf(
        Rule("ruc13", RUC_13),
        Rule("cedula10", CEDULA_10),
        Rule("accessKey49", ACCESS_KEY_49),
        Rule("pemMarker", Regex("-{5}BEGIN [A-Z0-9 ]+-{5}")),
        // `=` is deliberately not in the lookbehind: `NAME=<base64>` is how a pasted secret looks.
        Rule("pkcs12Marker", Regex("(?<![A-Za-z0-9+/])MII[A-Za-z0-9+/]{125,}={0,2}")),
        Rule("email", EMAIL),
    )

    /**
     * The honest limits of this gate. It is printed with every result and repeated in the plan
     * summary, and must not be softened: a green run proves the absence of *shapes*, not of
     * private information.
     */
    const val RESIDUAL_GAP: String =
        "RESIDUAL GAP: this scan matches the shape of an identifier in git-tracked text files, and " +
            "nothing else. It cannot see a value split across lines or concatenated at runtime, an " +
            "identifier encoded in base64 or held inside a binary file (binary files are skipped and " +
            "counted), a real name or address (which have no shape), anything in an untracked file, " +
            "or anything committed to git history before this gate existed. A green run is not proof " +
            "that nothing private is published; review remains the control for what a shape cannot express."

    /**
     * `PRIVACY <rule> <path>:<line>` for every line of every file that matches a rule and is not
     * allowlisted. The line number is one-based, and the result is sorted and de-duplicated. The
     * matched text is never included.
     */
    fun violations(files: List<ScannedFile>, allowlist: Allowlist = Allowlist.DEFAULT): List<String> {
        val result = sortedSetOf<String>()
        for (file in files) {
            file.lines.forEachIndexed { index, line ->
                for (rule in RULES) {
                    if (fires(rule, line, allowlist)) {
                        result += "PRIVACY ${rule.name} ${file.path}:${index + 1}"
                    }
                }
            }
        }
        return result.toList()
    }

    /**
     * Returns a violation when the scan was wired to no files. A pass over zero files means
     * nothing was looked at, which a privacy gate must never report as clean. Returns an empty
     * list when there was something to scan.
     */
    fun preconditionViolations(files: List<ScannedFile>): List<String> =
        if (files.isEmpty()) {
            listOf("no tracked files were supplied to the privacy scan, so a pass would mean nothing was looked at")
        } else {
            emptyList()
        }

    private fun fires(rule: Rule, line: String, allowlist: Allowlist): Boolean {
        val matches = rule.pattern.findAll(line)
        return when (rule.name) {
            "ruc13" -> matches.any { it.value != allowlist.syntheticRuc }
            "cedula10" -> matches.any { it.value != allowlist.syntheticRuc.take(CEDULA_LENGTH) }
            "accessKey49" -> matches.any { rucWindow(it.value) != allowlist.syntheticRuc }
            "email" -> matches.any { !domainAllowed(it.groupValues[1], allowlist.allowedEmailDomain) }
            else -> matches.any()
        }
    }

    /** Positional, never a substring test: only the RUC window of the key is compared. */
    private fun rucWindow(accessKey: String): String =
        accessKey.substring(ACCESS_KEY_RUC_START, ACCESS_KEY_RUC_END)

    private fun domainAllowed(domain: String, allowed: String): Boolean {
        val d = domain.lowercase()
        val a = allowed.lowercase()
        return d == a || d.endsWith(".$a")
    }

    private const val CEDULA_LENGTH = 10
}
