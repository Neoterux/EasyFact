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

package com.neoterux.easyfact.invoicing.text

import com.neoterux.easyfact.invoicing.result.Outcome

/** Why a piece of document text was refused. Every variant names the field and never the text (NFR-04). */
sealed interface SanitizerError {
    val field: String

    data class ControlCharacter(override val field: String) : SanitizerError

    data class UnsupportedCharacter(override val field: String) : SanitizerError

    data class Empty(override val field: String) : SanitizerError

    data class TooLong(override val field: String, val maxLength: Int, val actualLength: Int) : SanitizerError
}

/** Scaffold: accepts everything, so the suite compiles and fails on its assertions. Replaced in the GREEN commit. */
object TextSanitizer {

    fun required(field: String, raw: String, maxLength: Int?): Outcome<String, SanitizerError> = Outcome.Ok(raw)

    fun optional(field: String, raw: String?, maxLength: Int?): Outcome<String?, SanitizerError> = Outcome.Ok(raw)
}
