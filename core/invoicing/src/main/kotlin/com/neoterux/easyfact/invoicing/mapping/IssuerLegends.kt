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

package com.neoterux.easyfact.invoicing.mapping

import com.neoterux.easyfact.invoicing.result.Outcome

/**
 * The legal RIMPE legend an issuer prints on a factura (D-20).
 *
 * The user never types a legal legend: the issuer profile (SETUP-03) offers these three choices and the
 * mapper writes the matching text into `contribuyenteRimpe`. The two texts are exactly the two the
 * schema's pattern accepts, spelt with the precomposed capital E with acute accent (U+00C9), which is
 * NFC. Source: Ficha v2.34 Annex 22 (pp.129-130).
 *
 * The texts are document content, not UI text, so they are the permitted exception to the English-only
 * rule (AGENTS.md section 2.1), exactly like an SRI element name.
 */
enum class RimpeLegend(
    /** The text written into `contribuyenteRimpe`, or `null` when the issuer is not in the regime. */
    val documentText: String?,
) {
    /** Not a RIMPE taxpayer: the element is omitted. */
    NONE(null),

    /** A RIMPE entrepreneur ("emprendedor"). */
    EMPRENDEDOR("CONTRIBUYENTE RÉGIMEN RIMPE"),

    /** A RIMPE popular business ("negocio popular"). */
    NEGOCIO_POPULAR("CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE"),
}

/**
 * The issuer's withholding-agent resolution number (`agenteRetencion`), as the SRI wants it written.
 *
 * Ficha v2.34 Annex 21 (p.128), paraphrased: the value is numeric, at most eight digits, and is the
 * resolution number with its leading zeros omitted. [of] therefore trims the text, accepts only the
 * ASCII digits 0 to 9, removes the leading zeros and then counts what is left. Being typed, a value that
 * reaches the mapper is already correct and the writer's facet is only a backstop.
 */
class WithholdingAgentResolution private constructor(
    /** The digits as written into the document: no leading zero, one to eight characters. */
    val number: String,
) {

    override fun equals(other: Any?): Boolean = other is WithholdingAgentResolution && other.number == number

    override fun hashCode(): Int = number.hashCode()

    override fun toString(): String = "WithholdingAgentResolution($number)"

    companion object {

        /** The most digits the schema allows (`agenteRetencion` is bounded at eight; a test ties the two). */
        const val MAX_DIGITS = 8

        /**
         * Reads [raw] as a resolution number, or says which rule it breaks. The numeric check runs
         * before the empty check, so `"0A"` is not numeric rather than empty.
         */
        fun of(raw: String): Outcome<WithholdingAgentResolution, WithholdingAgentError> {
            val trimmed = raw.trim()
            // A character range, never Character.isDigit: that also accepts Arabic-Indic and fullwidth digits.
            if (trimmed.any { it !in '0'..'9' }) return Outcome.Err(WithholdingAgentError.NotNumeric)
            val stripped = trimmed.trimStart('0')
            if (stripped.isEmpty()) return Outcome.Err(WithholdingAgentError.Empty)
            if (stripped.length > MAX_DIGITS) {
                return Outcome.Err(WithholdingAgentError.TooLong(MAX_DIGITS, stripped.length))
            }
            return Outcome.Ok(WithholdingAgentResolution(stripped))
        }
    }
}

/** Why a withholding-agent resolution number was refused. No variant carries the text. */
sealed interface WithholdingAgentError {

    /** Nothing is left once whitespace and leading zeros are removed. */
    data object Empty : WithholdingAgentError

    /** The text holds something other than the ASCII digits 0 to 9. */
    data object NotNumeric : WithholdingAgentError

    /** [actualLength] digits remain after the leading zeros are removed, more than [maxLength]. */
    data class TooLong(val maxLength: Int, val actualLength: Int) : WithholdingAgentError
}
