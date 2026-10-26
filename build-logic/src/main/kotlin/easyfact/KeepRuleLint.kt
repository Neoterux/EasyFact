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
 * The file is read as ONE TOKEN STREAM, the way R8 reads it — not as lines whose first word
 * decides everything. Three rounds of line-oriented heuristics each closed the spelling the last
 * reviewer named and left the next one open: a `,modifier` suffix, then a second directive on the
 * same line, then a leading `@file`, and after each fix `-keep class X { } @other.pro` or a
 * comment line inside an open `{` block still walked through. They were one defect, not four:
 * comments and newlines are whitespace to R8, and a rule can begin wherever the previous one
 * ended.
 *
 * So: a `#` opens a comment only at a token start, `{` and `}` carry a brace depth but only for
 * the directives that can actually take a member block, and a rule begins at any depth-0 token
 * that starts with `-` or `@`. The two qualifications are not pedantry — `-printmapping out#x.txt`
 * and `-printmapping out{x.txt` are both legal R8 and each hid every rule that followed it on the
 * line.
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

    /**
     * Class-type keywords in a class specification, each with its negated form.
     *
     * The negated forms matter: `-keep !interface * { *; }` keeps every class that is not an
     * interface, which is a blanket keep by any reading, and it used to pass because only the
     * bare keywords were listed.
     */
    private val classKeywords = setOf(
        "class", "interface", "enum", "@interface",
        "!class", "!interface", "!enum",
    )

    /**
     * Directives that name another rule file. Rules reached this way are applied by R8 but are
     * invisible to this gate, so a single `-include` would defeat both rules above at once.
     *
     * ProGuard's `@file` shorthand is the same control written without a leading `-`; it is
     * recognised in [ruleStarts] wherever a rule can begin, which R8 was observed to honour
     * mid-statement, not only at the start of one.
     */
    private val indirectionDirectives = setOf("-include", "-basedirectory")

    /**
     * Directives that take a class specification, and so are the only ones whose `{` opens a
     * member block.
     *
     * W1b: a `{` inside any other argument — a filename, say — used to open a block that never
     * closed, so every rule after it was read as member-block content and never evaluated.
     */
    private val classSpecDirectives = keepFamily + setOf(
        "-if",
        "-whyareyoukeeping",
        "-assumenosideeffects",
        "-assumenoexternalsideeffects",
        "-assumenoexternalreturnvalues",
        "-assumevalues",
    )

    /** Tokens after which an `@` is an annotation filter in a class specification, not an include. */
    private val annotationFilterPredecessors = setOf("extends", "implements")

    private data class Token(val text: String, val line: Int)

    /** One rule: the directive that opened it, its arguments, and the line it began on. */
    private data class Rule(val directive: String, val arguments: List<String>, val line: Int)

    /**
     * Returns one `<displayName>:<line>: <violation>` string per problem found in [text], in
     * source order. An empty list means the file satisfies the policy.
     */
    fun violations(text: String, displayName: String): List<String> {
        val lines = text.lines()
        val result = ArrayList<String>()
        val reasonReported = HashSet<Int>()

        for (rule in ruleStarts(tokenize(lines))) {
            if (!hasReasonAbove(lines, rule.line) && reasonReported.add(rule.line)) {
                result += "$displayName:${rule.line + 1}: $MISSING_REASON"
            }
            if (isBlanket(rule)) {
                result += "$displayName:${rule.line + 1}: $BLANKET"
            }
            if (isIndirection(rule)) {
                result += "$displayName:${rule.line + 1}: $INCLUDE"
            }
        }
        return result
    }

    /**
     * Splits [lines] into tokens, dropping comments and keeping `{`, `}` and `;` as tokens of
     * their own so brace depth can be tracked across lines.
     */
    private fun tokenize(lines: List<String>): List<Token> {
        val tokens = ArrayList<Token>()
        for ((number, raw) in lines.withIndex()) {
            val word = StringBuilder()
            fun flush() {
                if (word.isNotEmpty()) {
                    tokens += Token(word.toString(), number)
                    word.clear()
                }
            }
            for (character in raw) {
                // W1a: `#` opens a comment only at a token start. Cutting the line at any `#`
                // truncated `-printmapping out#x.txt` to `-printmapping out` and hid every rule
                // after it on that line.
                if (character == '#' && word.isEmpty()) break
                when {
                    character.isWhitespace() -> flush()
                    character == '{' || character == '}' || character == ';' -> {
                        flush()
                        tokens += Token(character.toString(), number)
                    }
                    else -> word.append(character)
                }
            }
            flush()
        }
        return tokens
    }

    /**
     * Every rule in [tokens], in source order, with the arguments that belong to it.
     *
     * A rule begins at a depth-0 token starting with `-`, or at a depth-0 `@` that is not an
     * annotation filter. Its arguments run until the next rule begins or a member block opens, so
     * one rule's class specification is never read as another's. Everything inside `{ ... }` is a
     * member block: an `@` there is an annotation filter on a member, which is why a filter on its
     * own line is no longer mistaken for an include.
     */
    private fun ruleStarts(tokens: List<Token>): List<Rule> {
        val rules = ArrayList<Rule>()
        var depth = 0
        var previous: String? = null
        var openerTakesSpec = false
        for ((index, token) in tokens.withIndex()) {
            when {
                // Only a class-specification directive's `{` opens a member block (W1b); anywhere
                // else the brace is ordinary argument text and must not move the depth counter.
                token.text == "{" -> if (openerTakesSpec) depth++
                token.text == "}" -> if (depth > 0) depth--
                depth > 0 -> Unit
                token.text == ";" -> Unit
                token.text.startsWith("-") -> {
                    val directive = token.text.substringBefore(',')
                    openerTakesSpec = directive in classSpecDirectives
                    rules += Rule(directive, argumentsFrom(tokens, index + 1), token.line)
                }
                token.text.startsWith("@") && !isAnnotationFilter(previous) -> {
                    openerTakesSpec = false
                    rules += Rule("@", argumentsFrom(tokens, index + 1), token.line)
                }
                else -> Unit
            }
            if (depth == 0 && token.text != ";") previous = token.text
        }
        return rules
    }

    /**
     * True when an `@` token following [previous] is a class specification's annotation filter.
     *
     * `-keep @androidx.annotation.Keep class *` and `... extends @Anno Base` are both legal and
     * must not be read as includes. Anywhere else at depth 0 a rule can begin, so `@` there is
     * ProGuard's include shorthand — including straight after a `}`, which R8 was observed to
     * honour and which three earlier fixes let through.
     */
    private fun isAnnotationFilter(previous: String?): Boolean {
        val token = previous ?: return false
        return token.substringBefore(',') in keepFamily || token in annotationFilterPredecessors
    }

    private fun argumentsFrom(tokens: List<Token>, start: Int): List<String> {
        val arguments = ArrayList<String>()
        var index = start
        while (index < tokens.size) {
            val text = tokens[index].text
            if (text == "{" || text == "}" || text == ";" || text.startsWith("-")) break
            arguments += text
            index++
        }
        return arguments
    }

    private fun isIndirection(rule: Rule) = rule.directive == "@" || rule.directive in indirectionDirectives

    private fun isBlanket(rule: Rule): Boolean = when {
        rule.directive in blanketDirectives -> true
        rule.directive == "-dontwarn" -> rule.arguments.flatMap { it.split(',') }.any { isBarePattern(it) }
        rule.directive in keepFamily -> {
            // An annotation filter scopes the rule to classes carrying that annotation, so
            // `-keep @Keep class *` is narrow by intent and is not a blanket keep.
            val keyword = rule.arguments.indexOfFirst { it in classKeywords }
            rule.arguments.none { it.startsWith("@") } &&
                keyword >= 0 && keyword + 1 < rule.arguments.size && isBarePattern(rule.arguments[keyword + 1])
        }
        else -> false
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

    private fun isBarePattern(pattern: String) = pattern == "*" || pattern == "**"
}
