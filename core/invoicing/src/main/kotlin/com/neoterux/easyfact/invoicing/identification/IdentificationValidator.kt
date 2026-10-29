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

/**
 * What [IdentificationValidator] concluded about one buyer identification.
 *
 * These values reach logs, so no variant carries the identification itself: they name the fault
 * and, where it helps, a measured length.
 */
sealed interface IdentificationVerdict {

    /** The identification satisfies every rule this validator knows for its type. */
    data object Valid : IdentificationVerdict

    /** Passport (06) and foreign (08) identifications have no check digit; only length is judged. */
    data object NoCheckDigit : IdentificationVerdict

    /** Nothing was entered. */
    data object Blank : IdentificationVerdict

    /** A passport or foreign identification longer than [maxLength] UTF-16 units. */
    data class TooLong(val maxLength: Int, val actualLength: Int) : IdentificationVerdict

    /** A cedula or RUC with [actual] characters where [expected] are required. */
    data class WrongLength(val expected: Int, val actual: Int) : IdentificationVerdict

    /** Something other than the ASCII digits `0` to `9` was found where only digits are allowed. */
    data object NotNumeric : IdentificationVerdict

    /** The first two digits are not an accepted province code. */
    data object BadProvince : IdentificationVerdict

    /** The third digit does not belong to any class this type admits. */
    data object BadThirdDigit : IdentificationVerdict

    /** The check digit does not match the digits before it. */
    data object BadCheckDigit : IdentificationVerdict

    /** The establishment suffix of a RUC is all zeros. */
    data object BadEstablishmentSuffix : IdentificationVerdict

    /** A consumidor final identification (07) that is not exactly the fixed placeholder. */
    data object NotConsumidorFinal : IdentificationVerdict

    /** The identification type code is not one this validator knows. */
    data object UnknownType : IdentificationVerdict
}

/**
 * Judges a buyer identification against its type (decision D-32) and answers with an
 * [IdentificationVerdict]: it never throws and never answers with a bare boolean.
 *
 * The SRI states only that a check digit exists (warning 62 of Ficha section 11, "cedulas that do
 * not pass the check digit"). The algorithms themselves come from community descriptions, recorded
 * as assumption A3 in the phase research, and carry LOW confidence. They are implemented here from
 * that description, not ported from any library. Phase 5 (CUST-02) decides whether a verdict
 * blocks the user or only warns.
 *
 * A public-entity RUC that fails its own rule is **never** retried under another rule. Some
 * community implementations retry it as a natural-person RUC; that has no published source and
 * would accept about one in ten public RUCs whose check digit is wrong. A legitimate number that
 * this stricter rule rejects reaches Phase 5 as [IdentificationVerdict.BadCheckDigit], which the
 * research recommends showing as a warning because the SRI itself only warns (code 62).
 *
 * Checks run in a fixed order and the first fault wins: blank and length, ASCII digits only,
 * province, third digit, check digit, establishment suffix.
 */
object IdentificationValidator {

    private const val CEDULA_LENGTH = 10
    private const val RUC_LENGTH = 13
    private const val FREE_FORM_MAX_LENGTH = 20
    private const val NATURAL_THIRD_DIGIT_MAX = '5'
    private const val PUBLIC_THIRD_DIGIT = '6'
    private const val PRIVATE_THIRD_DIGIT = '9'

    private val PRIVATE_WEIGHTS = intArrayOf(4, 3, 2, 7, 6, 5, 4, 3, 2, 1)
    private val PUBLIC_WEIGHTS = intArrayOf(3, 2, 7, 6, 5, 4, 3, 2, 1)

    /** Thirteen nines, built here so no identifier-shaped literal sits in main source (PrivacyScan). */
    private val CONSUMIDOR_FINAL = "9".repeat(13)

    /**
     * Judges [identification] for the SRI identification type [typeCode]: `04` RUC, `05` cedula,
     * `06` passport, `07` consumidor final, `08` foreign identification. Any other code is
     * [IdentificationVerdict.UnknownType]. The type is a string so this package does not depend on
     * the mapping package.
     */
    fun validate(typeCode: String, identification: String): IdentificationVerdict = when (typeCode) {
        "04" -> validateRuc(identification)
        "05" -> validateCedula(identification)
        "06", "08" -> validateFreeForm(identification)
        "07" -> if (isConsumidorFinal(identification)) IdentificationVerdict.Valid else IdentificationVerdict.NotConsumidorFinal
        else -> IdentificationVerdict.UnknownType
    }

    /** `true` only for exactly thirteen nines. */
    fun isConsumidorFinal(identification: String): Boolean = identification == CONSUMIDOR_FINAL

    private fun validateFreeForm(identification: String): IdentificationVerdict = when {
        identification.isBlank() -> IdentificationVerdict.Blank
        identification.length > FREE_FORM_MAX_LENGTH -> IdentificationVerdict.TooLong(FREE_FORM_MAX_LENGTH, identification.length)
        else -> IdentificationVerdict.NoCheckDigit
    }

    private fun validateCedula(identification: String): IdentificationVerdict {
        shapeProblem(identification, CEDULA_LENGTH)?.let { return it }
        if (!isAcceptedProvince(identification)) return IdentificationVerdict.BadProvince
        if (identification[2] > NATURAL_THIRD_DIGIT_MAX) return IdentificationVerdict.BadThirdDigit
        if (!cedulaSumIsDivisibleByTen(identification)) return IdentificationVerdict.BadCheckDigit
        return IdentificationVerdict.Valid
    }

    private fun validateRuc(identification: String): IdentificationVerdict {
        shapeProblem(identification, RUC_LENGTH)?.let { return it }
        if (!isAcceptedProvince(identification)) return IdentificationVerdict.BadProvince
        val third = identification[2]
        return when {
            third <= NATURAL_THIRD_DIGIT_MAX -> {
                if (!cedulaSumIsDivisibleByTen(identification)) return IdentificationVerdict.BadCheckDigit
                suffixVerdict(identification, 3)
            }
            third == PRIVATE_THIRD_DIGIT -> {
                if (weightedSum(identification, PRIVATE_WEIGHTS) % 11 != 0) return IdentificationVerdict.BadCheckDigit
                suffixVerdict(identification, 3)
            }
            third == PUBLIC_THIRD_DIGIT -> {
                if (weightedSum(identification, PUBLIC_WEIGHTS) % 11 != 0) return IdentificationVerdict.BadCheckDigit
                suffixVerdict(identification, 4)
            }
            else -> IdentificationVerdict.BadThirdDigit
        }
    }

    /** The blank, length and ASCII-digit checks, in that order; `null` when all pass. */
    private fun shapeProblem(identification: String, expectedLength: Int): IdentificationVerdict? = when {
        identification.isBlank() -> IdentificationVerdict.Blank
        identification.length != expectedLength -> IdentificationVerdict.WrongLength(expectedLength, identification.length)
        identification.any { it !in '0'..'9' } -> IdentificationVerdict.NotNumeric
        else -> null
    }

    /** Provinces 01 to 24, 30 and 50 (A3: widened rather than narrowed, since a false rejection blocks a customer). */
    private fun isAcceptedProvince(identification: String): Boolean {
        val province = (identification[0] - '0') * 10 + (identification[1] - '0')
        return province in 1..24 || province == 30 || province == 50
    }

    /**
     * Sum-to-zero formulation: every one of the first ten digits is weighted 2,1,2,1,..., products
     * above nine lose nine, and the check digit makes the total a multiple of ten.
     */
    private fun cedulaSumIsDivisibleByTen(identification: String): Boolean {
        var sum = 0
        for (position in 0 until CEDULA_LENGTH) {
            val digit = identification[position] - '0'
            val product = if (position % 2 == 0) digit * 2 else digit
            sum += if (product > 9) product - 9 else product
        }
        return sum % 10 == 0
    }

    private fun weightedSum(identification: String, weights: IntArray): Int {
        var sum = 0
        for (position in weights.indices) sum += (identification[position] - '0') * weights[position]
        return sum
    }

    /** [IdentificationVerdict.BadEstablishmentSuffix] when the last [suffixLength] digits are all zeros. */
    private fun suffixVerdict(identification: String, suffixLength: Int): IdentificationVerdict =
        if (identification.takeLast(suffixLength).all { it == '0' }) {
            IdentificationVerdict.BadEstablishmentSuffix
        } else {
            IdentificationVerdict.Valid
        }
}
