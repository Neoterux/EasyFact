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

package com.neoterux.easyfact.invoicing.factura

/**
 * Why a [Factura] could not be turned into a document.
 *
 * Following the convention set in plan 02-03, failure is a typed value carried by
 * [com.neoterux.easyfact.invoicing.result.Outcome], never an exception: the SRI returns
 * structured, actionable failures and the type system should preserve them all the way to the
 * screen.
 *
 * **No variant carries a value.** Each one names the element that was wrong and, where it helps,
 * a length or a catalogue code — never the customer's name, their identification number, their
 * address or any other free text. An error is rendered into diagnostics and into a log, and both
 * are publication channels (AGENTS.md section 7). A length is not the value; a catalogue code is
 * a protocol constant and identifies nobody.
 */
sealed interface FacturaError {

    /** The SRI element name the failure is about, so the UI can point at one field. */
    val element: String

    /** [element] is longer than the schema's `maxLength` facet allows. */
    data class FieldTooLong(
        override val element: String,
        val maxLength: Int,
        val actualLength: Int,
    ) : FacturaError

    /**
     * [element] contains a character that XML 1.0 does not allow, so no amount of escaping can
     * make a well-formed document out of it.
     */
    data class IllegalCharacter(override val element: String) : FacturaError

    /**
     * [element] must carry content and is blank. This covers a required element and an optional
     * one the caller chose to populate alike: an empty element is rejected by the schema's
     * `minLength` and pattern facets, and it is also a silent data loss at the domain boundary,
     * so it is never written in place of an absent value.
     */
    data class BlankField(override val element: String) : FacturaError

    /** [code] is not in the catalogue the SRI publishes for [element]. */
    data class CodeOutsideCatalogue(
        override val element: String,
        val code: String,
        val allowed: Set<String>,
    ) : FacturaError

    /** [element] repeats more times than the specification allows; `infoAdicional` caps at 15. */
    data class TooManyEntries(
        override val element: String,
        val maxEntries: Int,
        val actualEntries: Int,
    ) : FacturaError
}
