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

/** RED-phase stub with the final signatures; the behaviour arrives in the GREEN commit. */
enum class RimpeLegend(val documentText: String?) {
    NONE(null),
    EMPRENDEDOR(null),
    NEGOCIO_POPULAR(null),
}

/** RED-phase stub. */
class WithholdingAgentResolution private constructor(val number: String) {

    companion object {

        /** RED-phase stub: refuses everything. */
        fun of(raw: String): Outcome<WithholdingAgentResolution, WithholdingAgentError> =
            Outcome.Err(WithholdingAgentError.Empty)
    }
}

/** Why a withholding-agent resolution number was refused. */
sealed interface WithholdingAgentError {

    /** Nothing is left once whitespace and leading zeros are removed. */
    data object Empty : WithholdingAgentError

    /** The text holds something other than the ASCII digits 0 to 9. */
    data object NotNumeric : WithholdingAgentError

    /** More than [maxLength] digits remain after leading zeros are removed. */
    data class TooLong(val maxLength: Int, val actualLength: Int) : WithholdingAgentError
}
