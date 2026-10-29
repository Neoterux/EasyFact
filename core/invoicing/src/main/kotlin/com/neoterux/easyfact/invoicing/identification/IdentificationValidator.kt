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

package com.neoterux.easyfact.invoicing.identification

/** Red-phase stub: the verdict vocabulary is final, the rules are not implemented yet. */
sealed interface IdentificationVerdict {
    data object Valid : IdentificationVerdict
    data object NoCheckDigit : IdentificationVerdict
    data object Blank : IdentificationVerdict
    data class TooLong(val maxLength: Int, val actualLength: Int) : IdentificationVerdict
    data class WrongLength(val expected: Int, val actual: Int) : IdentificationVerdict
    data object NotNumeric : IdentificationVerdict
    data object BadProvince : IdentificationVerdict
    data object BadThirdDigit : IdentificationVerdict
    data object BadCheckDigit : IdentificationVerdict
    data object BadEstablishmentSuffix : IdentificationVerdict
    data object NotConsumidorFinal : IdentificationVerdict
    data object UnknownType : IdentificationVerdict
}

object IdentificationValidator {
    fun validate(typeCode: String, identification: String): IdentificationVerdict = IdentificationVerdict.UnknownType

    fun isConsumidorFinal(identification: String): Boolean = false
}
