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

package com.neoterux.easyfact.invoicing.result

/**
 * A successful engine result together with the non-blocking [warnings] raised while producing it.
 *
 * [Outcome.Ok] cannot carry warnings by itself, and D-27 and D-38 require some results to succeed
 * while still telling the caller something: a zero quantity, a zero total, a placeholder token the
 * resolver did not recognise. The caller (Phase 5 or 6) turns each warning into a confirmation or a
 * hint; none of them blocks the operation.
 */
data class Computed<out T>(
    val value: T,
    val warnings: List<EngineWarning> = emptyList(),
)

/**
 * A condition the engine reports next to a successful result (D-27, D-37, D-38).
 *
 * **No variant carries a value except [UnrecognisedToken].** Like the typed errors, a warning names
 * a position or a kind and never a customer's text, because warnings reach diagnostics. The one
 * deliberate exception is [UnrecognisedToken]: D-38 requires showing the user the token they typed,
 * so it keeps the text and must stay out of logs.
 */
sealed interface EngineWarning {

    /** The line at [lineIndex] has a quantity of zero (D-27). */
    data class ZeroQuantity(val lineIndex: Int) : EngineWarning

    /** The line at [lineIndex] has a total of zero (D-27). */
    data class ZeroLineTotal(val lineIndex: Int) : EngineWarning

    /** The whole invoice totals zero (D-27). */
    data object ZeroInvoiceTotal : EngineWarning

    /**
     * A previewed `{SEQ}` is only a visible marker: the real sequential is assigned at issue and the
     * resolver runs again with it before the XML is built (D-37). Emitted by the placeholder preview.
     */
    data object SequenceAssignedAtIssue : EngineWarning

    /**
     * A `{...}` token the resolver does not know, left as literal text (D-38). [token] is the text
     * the user typed in their own template, not customer data, which is why this variant may carry it.
     * `toString()` does not render it, so an accidental log line stays clean.
     */
    class UnrecognisedToken(val token: String) : EngineWarning {

        override fun equals(other: Any?): Boolean = other is UnrecognisedToken && other.token == token

        override fun hashCode(): Int = token.hashCode()

        override fun toString(): String = "UnrecognisedToken(redacted)"
    }
}
