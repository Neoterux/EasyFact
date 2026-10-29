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
import java.time.LocalDate

/** The period an invoice bills for. */
data class BillingPeriod(val start: LocalDate, val end: LocalDate)

/** What a pattern may refer to besides the period. */
data class PlaceholderContext(val period: BillingPeriod, val customerName: String, val customerId: String) {
    override fun toString(): String = "PlaceholderContext(redacted)"
}

/** The sequential assigned at issue. */
data class CommittedSequence(val establishment: String, val emissionPoint: String, val sequential: Long)

/** Text resolved for display only. */
@JvmInline
value class PreviewText(val text: String) {
    override fun toString(): String = "PreviewText(redacted)"
}

/** Scaffold: returns the pattern unchanged, so the suite compiles and fails on its assertions. Replaced in the GREEN commit. */
object PlaceholderResolver {

    const val PENDING_SEQUENCE_MARKER = "…"

    fun preview(pattern: String, context: PlaceholderContext): Computed<PreviewText> =
        Computed(PreviewText(pattern))

    fun resolve(pattern: String, context: PlaceholderContext, sequence: CommittedSequence): Computed<String> =
        Computed(pattern)
}
