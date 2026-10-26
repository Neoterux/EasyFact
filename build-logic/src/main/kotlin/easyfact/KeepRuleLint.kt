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
 * Pure policy over R8 keep-rule text. No Gradle types, so it runs in a JVM unit test.
 *
 * Two independent rules, both about rules the project writes itself (never AGP defaults or
 * library consumer rules, which this policy never sees):
 *
 *  - Every rule statement must sit directly beneath a comment block containing a line that
 *    starts with `# Reason:`, so each surviving symbol is justified in writing.
 *  - Blanket directives and patterns are rejected: they freeze reflection-reachable code and
 *    enlarge the shipped surface silently.
 *
 * A statement is any non-blank, non-comment line beginning with `-`. Following lines that do
 * not begin with `-` are treated as its continuation (a multi-line `{ ... }` member block).
 */
object KeepRuleLint {

    private const val MISSING_REASON = "no preceding '# Reason:'"
    private const val BLANKET = "blanket keep/dontwarn"
    private const val INCLUDE = "-include/-basedirectory pulls in rules this gate never sees"

    /** Directives that switch a whole R8 phase off; never acceptable in project-owned rules. */
    private val blanketDirectives = setOf("-dontshrink", "-dontoptimize", "-dontobfuscate", "-ignorewarnings")

    /** Keep-family directives that take a class specification. `-keepattributes` does not. */
    private val keepFamily = setOf(
        "-keep",
        "-keepclassmembers",
        "-keepclasseswithmembers",
        "-keepnames",
        "-keepclassmembernames",
        "-keepclasseswithmembernames",
    )

    private val classKeywords = setOf("class", "interface", "enum", "@interface")

    /**
     * Directives that name another rule file. Rules reached this way are applied by R8 but are
     * invisible to this gate, so a single `-include` would defeat both rules above at once.
     */
    private val indirectionDirectives = setOf("-include", "-basedirectory")

    /**
     * Returns one `<displayName>:<line>: <violation>` string per problem found in [text], in
     * source order. An empty list means the file satisfies the policy.
     */
    fun violations(text: String, displayName: String): List<String> {
        val lines = text.lines()
        val result = ArrayList<String>()
        var index = 0
        while (index < lines.size) {
            val trimmed = lines[index].trim()
            if (!isStatementStart(trimmed)) {
                index++
                continue
            }
            val statementLine = index + 1
            val body = StringBuilder(stripTrailingComment(trimmed))
            var next = index + 1
            while (next < lines.size && isContinuation(lines[next].trim())) {
                body.append(' ').append(stripTrailingComment(lines[next].trim()))
                next++
            }
            if (!hasReasonAbove(lines, index)) {
                result += "$displayName:$statementLine: $MISSING_REASON"
            }
            if (isBlanket(body.toString())) {
                result += "$displayName:$statementLine: $BLANKET"
            }
            if (isIndirection(body.toString())) {
                result += "$displayName:$statementLine: $INCLUDE"
            }
            index = next
        }
        return result
    }

    private fun isStatementStart(trimmed: String) = trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.startsWith("-")

    private fun isContinuation(trimmed: String) = trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("-")

    private fun stripTrailingComment(line: String): String {
        val at = line.indexOf(" #")
        return if (at >= 0) line.substring(0, at).trim() else line
    }

    /** True when the contiguous comment block directly above [statementIndex] has a `# Reason:` line. */
    private fun hasReasonAbove(lines: List<String>, statementIndex: Int): Boolean {
        var i = statementIndex - 1
        while (i >= 0) {
            val trimmed = lines[i].trim()
            if (!trimmed.startsWith("#")) return false
            if (trimmed.startsWith("# Reason:")) return true
            i--
        }
        return false
    }

    private fun isIndirection(statement: String) = directiveOf(statement) in indirectionDirectives

    /**
     * The directive of [statement] with any `,modifier` suffix removed, or null when there is none.
     *
     * The suffix strip is CR-02. R8 accepts keep modifiers on the directive itself
     * (`-keep,allowobfuscation,allowshrinking class * { *; }`), and comparing the raw first token
     * against [keepFamily] by equality missed every modifier form — so the broadest possible keep
     * rule passed the gate untouched.
     */
    private fun directiveOf(statement: String): String? =
        tokensOf(statement).firstOrNull()?.substringBefore(',')

    private fun tokensOf(statement: String) =
        statement.split(Regex("[\\s{};]+")).filter { it.isNotEmpty() }

    private fun isBlanket(statement: String): Boolean {
        val tokens = tokensOf(statement)
        val directive = directiveOf(statement) ?: return false
        return when {
            directive in blanketDirectives -> true
            directive == "-dontwarn" -> tokens.drop(1).flatMap { it.split(',') }.any { isBarePattern(it) }
            directive in keepFamily -> {
                val keyword = tokens.indexOfFirst { it in classKeywords }
                keyword >= 0 && keyword + 1 < tokens.size && isBarePattern(tokens[keyword + 1])
            }
            else -> false
        }
    }

    private fun isBarePattern(pattern: String) = pattern == "*" || pattern == "**"
}
