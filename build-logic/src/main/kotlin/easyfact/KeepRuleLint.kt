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
 * A statement is any non-blank, non-comment line beginning with `-` or `@`. Following lines that
 * begin with neither are treated as its continuation (a multi-line `{ ... }` member block).
 *
 * Within a statement the policy is token-oriented, because R8 is: a line can carry more than one
 * directive, and a directive's arguments run to the next directive token. Deciding a statement by
 * the first token of its first line let `-keep class X { *; } -include other.pro` and a leading
 * `@other.pro` both pass while R8 obeyed them.
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
     *
     * ProGuard's `@file` shorthand is the same control and is handled in [isIndirection], since it
     * carries no leading `-`.
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

    private fun isStatementStart(trimmed: String) =
        trimmed.isNotEmpty() && !trimmed.startsWith("#") && (trimmed.startsWith("-") || trimmed.startsWith("@"))

    private fun isContinuation(trimmed: String) =
        trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("-") && !trimmed.startsWith("@")

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

    /**
     * True when any directive token of [statement] names another rule file.
     *
     * A leading `@file` is ProGuard's documented shorthand for `-include file`, and R8 honours it,
     * so it counts. An `@` token anywhere else is an annotation filter inside a class
     * specification (`-keep @androidx.annotation.Keep class *`) and is deliberately NOT flagged —
     * flagging it would reject a legitimate rule. `@file` is a top-level form, so first-token
     * position is the whole of its grammar.
     */
    private fun isIndirection(statement: String): Boolean {
        val tokens = tokensOf(statement)
        if (tokens.firstOrNull()?.startsWith("@") == true) return true
        return tokens.any { it.startsWith("-") && it.substringBefore(',') in indirectionDirectives }
    }

    /**
     * Every directive token of [statement], paired with the arguments that belong to it.
     *
     * This is the token-oriented pass. R8 reads a configuration as a token stream, not a stream of
     * lines whose first word decides everything, so `-keep class X { *; } -include other.pro` is
     * two directives on one line and `-keep class * { *; }` hidden after a narrow rule is a blanket
     * keep. Judging a statement by its first token alone missed both: the lint reported green and
     * R8 obeyed the rule. Arguments run to the next directive token, so one directive's class
     * specification is never read as another's.
     *
     * The `,modifier` suffix is stripped from each directive, because R8 accepts keep modifiers on
     * the directive itself (`-keep,allowobfuscation,allowshrinking class * { *; }`).
     */
    private fun directives(statement: String): List<Pair<String, List<String>>> {
        val tokens = tokensOf(statement)
        val result = ArrayList<Pair<String, List<String>>>()
        for ((i, token) in tokens.withIndex()) {
            if (!token.startsWith("-")) continue
            val arguments = tokens.drop(i + 1).takeWhile { !it.startsWith("-") }
            result += token.substringBefore(',') to arguments
        }
        return result
    }

    private fun tokensOf(statement: String) =
        statement.split(Regex("[\\s{};]+")).filter { it.isNotEmpty() }

    private fun isBlanket(statement: String): Boolean =
        directives(statement).any { (directive, arguments) ->
            when {
                directive in blanketDirectives -> true
                directive == "-dontwarn" -> arguments.flatMap { it.split(',') }.any { isBarePattern(it) }
                directive in keepFamily -> {
                    val keyword = arguments.indexOfFirst { it in classKeywords }
                    keyword >= 0 && keyword + 1 < arguments.size && isBarePattern(arguments[keyword + 1])
                }
                else -> false
            }
        }

    private fun isBarePattern(pattern: String) = pattern == "*" || pattern == "**"
}
