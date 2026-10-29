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

package com.neoterux.easyfact.invoicing.placeholder

import com.neoterux.easyfact.invoicing.result.Computed
import com.neoterux.easyfact.invoicing.result.EngineWarning
import java.time.LocalDate
import java.util.Locale

/**
 * The period an invoice bills for (D-35). Tokens resolve against [start], never against an issue date:
 * the issue date is always today (ISSUE-04) and has no business in a description.
 */
data class BillingPeriod(val start: LocalDate, val end: LocalDate)

/**
 * What a pattern may refer to besides the period: the customer the invoice is for (D-36).
 *
 * `toString()` renders nothing, because both values are customer data and diagnostics must not carry
 * them (NFR-04).
 */
data class PlaceholderContext(val period: BillingPeriod, val customerName: String, val customerId: String) {
    override fun toString(): String = "PlaceholderContext(redacted)"
}

/**
 * The sequential the allocator assigned at issue, as the three parts `{SEQ}` renders (D-37).
 *
 * The caller is the sequence allocator, which guarantees a three-digit [establishment] and
 * [emissionPoint] and a [sequential] from 1 to 999999999. This type does not re-validate them: a value
 * outside that range would also be refused by the access key and the schema further down.
 */
data class CommittedSequence(val establishment: String, val emissionPoint: String, val sequential: Long)

/**
 * Text resolved for display only (D-37). A preview is a distinct type, so the commit path, which takes
 * a `String`, cannot be handed preview text by accident; the sanitiser and the mapper additionally
 * refuse the marker character (plan 03-08).
 *
 * `toString()` renders nothing, because the text can hold the customer's name.
 */
@JvmInline
value class PreviewText(val text: String) {
    override fun toString(): String = "PreviewText(redacted)"
}

/**
 * Resolves the `{TOKEN}` placeholders of a template pattern (D-35 to D-38; unit-tested in core per
 * AGENTS.md section 9.4).
 *
 * **Token set (D-36), case-sensitive:** `{YY}`, `{YYYY}`, `{MM}`, `{MONTH_NAME}` (OCTUBRE),
 * `{Month_name}` (Octubre) from the period start; `{PERIOD_START}` and `{PERIOD_END}` as `dd/MM/yyyy`;
 * `{CUSTOMER_NAME}` and `{CUSTOMER_ID}` from the context; `{SEQ}`.
 *
 * **Period and locale (D-35, D-36).** Neither function takes an issue date or a clock. Month names are
 * document content that becomes part of a signed `descripcion`, so they come from a fixed Spanish
 * table in this file and never from the device locale; numbers are zero-padded by hand. The result is
 * therefore identical on every device and under every default locale.
 *
 * **Preview and commit (D-37).** [preview] renders `{SEQ}` as [PENDING_SEQUENCE_MARKER] and records
 * [EngineWarning.SequenceAssignedAtIssue], because the real sequential does not exist yet. [resolve]
 * runs again at commit with the real [CommittedSequence], renders `EEE-PPP-NNNNNNNNN` (the same shape
 * as the `guiaRemision` pattern) and never records that note.
 *
 * **Unknown and malformed tokens (D-38).** A name outside the set (`{FOO}`), an empty `{}` and an
 * unclosed `{MM` stay in the output as literal text, and each distinct one adds one
 * [EngineWarning.UnrecognisedToken] naming the literal fragment, in first-occurrence order. A fragment
 * runs from a `{` to the next `}` or, when another `{` or the end of the text comes first, up to that
 * point. For example the pattern shown in AGENTS.md section 5, `{PREFIX}-{YY}{MM}-{SUFFIX}`, resolves
 * `{PREFIX}` and `{SUFFIX}` as unknown tokens with warnings, which is the intended behaviour: the v1
 * token set is closed.
 *
 * **Scanning.** One left-to-right pass writes to a builder. A substituted value is never scanned
 * again, so a customer name that contains `{MM}` is inserted as it is.
 */
object PlaceholderResolver {

    /**
     * What `{SEQ}` renders in a preview: U+E000, a private-use code point. It is deliberately not a character a
     * person can type or paste in ordinary text (the horizontal ellipsis that phone keyboards substitute for
     * "..." collided with it), and [com.neoterux.easyfact.invoicing.text.TextSanitizer] refuses every
     * private-use character, so user data can never carry it. A screen that shows a preview replaces the marker
     * with its own visible placeholder.
     */
    const val PENDING_SEQUENCE_MARKER = "\uE000"

    /** Resolves [pattern] for display. `{SEQ}` becomes the marker, with a note that it is assigned at issue. */
    fun preview(pattern: String, context: PlaceholderContext): Computed<PreviewText> {
        val result = scan(pattern, context, null)
        return Computed(PreviewText(result.value), result.warnings)
    }

    /** Resolves [pattern] at commit, with the real [sequence]. Never records the assigned-at-issue note. */
    fun resolve(pattern: String, context: PlaceholderContext, sequence: CommittedSequence): Computed<String> =
        scan(pattern, context, sequence)

    private fun scan(pattern: String, context: PlaceholderContext, sequence: CommittedSequence?): Computed<String> {
        val out = StringBuilder(pattern.length)
        // A set keeps one entry per distinct warning and, being linked, their first-occurrence order.
        val warnings = LinkedHashSet<EngineWarning>()
        var i = 0
        while (i < pattern.length) {
            if (pattern[i] != '{') {
                out.append(pattern[i])
                i++
                continue
            }
            var k = i + 1
            while (k < pattern.length && pattern[k] != '{' && pattern[k] != '}') k++
            if (k < pattern.length && pattern[k] == '}') {
                val value = valueOf(pattern.substring(i + 1, k), context, sequence, warnings)
                if (value != null) {
                    out.append(value)
                } else {
                    val fragment = pattern.substring(i, k + 1)
                    out.append(fragment)
                    warnings.add(EngineWarning.UnrecognisedToken(fragment))
                }
                i = k + 1
            } else {
                // No closing brace before the next opening brace or the end: literal up to that point.
                val fragment = pattern.substring(i, k)
                out.append(fragment)
                warnings.add(EngineWarning.UnrecognisedToken(fragment))
                i = k
            }
        }
        return Computed(out.toString(), warnings.toList())
    }

    /** The text for the token [name], or `null` when it is not in the v1 set. */
    private fun valueOf(
        name: String,
        context: PlaceholderContext,
        sequence: CommittedSequence?,
        warnings: MutableSet<EngineWarning>,
    ): String? {
        val start = context.period.start
        return when (name) {
            "YY" -> pad(start.year % 100, 2)
            "YYYY" -> pad(start.year, 4)
            "MM" -> pad(start.monthValue, 2)
            "MONTH_NAME" -> MONTHS_UPPER[start.monthValue - 1]
            "Month_name" -> MONTHS_TITLE[start.monthValue - 1]
            "PERIOD_START" -> dayMonthYear(start)
            "PERIOD_END" -> dayMonthYear(context.period.end)
            "CUSTOMER_NAME" -> context.customerName
            "CUSTOMER_ID" -> context.customerId
            "SEQ" ->
                if (sequence == null) {
                    warnings.add(EngineWarning.SequenceAssignedAtIssue)
                    PENDING_SEQUENCE_MARKER
                } else {
                    "${sequence.establishment}-${sequence.emissionPoint}-${pad(sequence.sequential, 9)}"
                }
            else -> null
        }
    }

    private fun dayMonthYear(date: LocalDate): String =
        "${pad(date.dayOfMonth, 2)}/${pad(date.monthValue, 2)}/${pad(date.year, 4)}"

    private fun pad(number: Int, width: Int): String = number.toString().padStart(width, '0')

    private fun pad(number: Long, width: Int): String = number.toString().padStart(width, '0')

    private val MONTHS_UPPER =
        listOf(
            "ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO",
            "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE",
        )

    /** First letter upper, the rest lower, with the invariant locale so no default locale is read. */
    private val MONTHS_TITLE = MONTHS_UPPER.map { it.substring(0, 1) + it.substring(1).lowercase(Locale.ROOT) }
}
