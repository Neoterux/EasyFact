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

import com.neoterux.easyfact.testing.SyntheticIdNumbers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buyer-identification validator, exercised only on values the independent generator in
 * `:core:testing` produces at runtime. Assertion messages name the case and the index, never the
 * value: a generated number may coincide with an issued one.
 */
class IdentificationValidatorTest {

    /** At least five hundred generated cases per class; the bound is visible here on purpose. */
    private val generated = 0 until 500

    private val cedulaType = "05"
    private val rucType = "04"

    private fun validate(type: String, value: String) = IdentificationValidator.validate(type, value)

    private fun replaceAt(value: String, index: Int, replacement: String): String =
        value.substring(0, index) + replacement + value.substring(index + 1)

    /** The cedula weighted sum over ten digits, without the restriction on the third digit. */
    private fun firstTenPassCedulaSum(value: String): Boolean {
        var sum = 0
        for (position in 0 until 10) {
            val product = (value[position] - '0') * (if (position % 2 == 0) 2 else 1)
            sum += if (product > 9) product - 9 else product
        }
        return sum % 10 == 0
    }

    @Test
    fun cedula_generatedValuesAreValid() {
        for (i in generated) {
            assertEquals("cedula $i", IdentificationVerdict.Valid, validate(cedulaType, SyntheticIdNumbers.cedula(i)))
        }
    }

    @Test
    fun naturalRuc_generatedValuesAreValid() {
        for (i in generated) {
            assertEquals("naturalRuc $i", IdentificationVerdict.Valid, validate(rucType, SyntheticIdNumbers.naturalRuc(i)))
        }
    }

    @Test
    fun privateRuc_generatedValuesAreValid() {
        for (i in generated) {
            assertEquals("privateRuc $i", IdentificationVerdict.Valid, validate(rucType, SyntheticIdNumbers.privateRuc(i)))
        }
    }

    @Test
    fun publicRuc_generatedValuesAreValid() {
        for (i in generated) {
            assertEquals("publicRuc $i", IdentificationVerdict.Valid, validate(rucType, SyntheticIdNumbers.publicRuc(i)))
        }
    }

    @Test
    fun wrongCheckDigit_isBadCheckDigitForEveryClass() {
        for (i in generated) {
            assertEquals(
                "cedula $i",
                IdentificationVerdict.BadCheckDigit,
                validate(cedulaType, SyntheticIdNumbers.withWrongCheckDigit(SyntheticIdNumbers.cedula(i), 9)),
            )
            assertEquals(
                "naturalRuc $i",
                IdentificationVerdict.BadCheckDigit,
                validate(rucType, SyntheticIdNumbers.withWrongCheckDigit(SyntheticIdNumbers.naturalRuc(i), 9)),
            )
            assertEquals(
                "privateRuc $i",
                IdentificationVerdict.BadCheckDigit,
                validate(rucType, SyntheticIdNumbers.withWrongCheckDigit(SyntheticIdNumbers.privateRuc(i), 9)),
            )
            assertEquals(
                "publicRuc $i",
                IdentificationVerdict.BadCheckDigit,
                validate(rucType, SyntheticIdNumbers.withWrongCheckDigit(SyntheticIdNumbers.publicRuc(i), 8)),
            )
        }
    }

    /**
     * A single body-digit typo is always caught. Indices 0 to 2 are excluded because the province
     * and third-digit checks run before the check digit and would answer first.
     */
    @Test
    fun singleBodyDigitTypo_isBadCheckDigitForEveryClass() {
        for (i in generated) {
            for (index in 3..8) {
                assertEquals(
                    "cedula $i digit $index",
                    IdentificationVerdict.BadCheckDigit,
                    validate(cedulaType, SyntheticIdNumbers.withWrongCheckDigit(SyntheticIdNumbers.cedula(i), index)),
                )
                assertEquals(
                    "naturalRuc $i digit $index",
                    IdentificationVerdict.BadCheckDigit,
                    validate(rucType, SyntheticIdNumbers.withWrongCheckDigit(SyntheticIdNumbers.naturalRuc(i), index)),
                )
                assertEquals(
                    "privateRuc $i digit $index",
                    IdentificationVerdict.BadCheckDigit,
                    validate(rucType, SyntheticIdNumbers.withWrongCheckDigit(SyntheticIdNumbers.privateRuc(i), index)),
                )
            }
            for (index in 3..7) {
                assertEquals(
                    "publicRuc $i digit $index",
                    IdentificationVerdict.BadCheckDigit,
                    validate(rucType, SyntheticIdNumbers.withWrongCheckDigit(SyntheticIdNumbers.publicRuc(i), index)),
                )
            }
        }
    }

    /**
     * No second rule may turn a failed public-RUC check digit into a pass. Every substitute of the
     * check digit whose first ten digits happen to satisfy the cedula sum would be accepted by an
     * implementation that retries a public RUC as a natural one; none may be accepted here. The
     * count guards against the loop passing without ever reaching such a case.
     */
    @Test
    fun publicRucCheckDigitSubstitution_isNeverRescuedByTheCedulaRule() {
        var overlapping = 0
        for (i in generated) {
            val original = SyntheticIdNumbers.publicRuc(i)
            for (replacement in '0'..'9') {
                if (replacement == original[8]) continue
                val candidate = replaceAt(original, 8, replacement.toString())
                if (!firstTenPassCedulaSum(candidate)) continue
                overlapping++
                assertEquals("publicRuc $i replacement $replacement", IdentificationVerdict.BadCheckDigit, validate(rucType, candidate))
            }
        }
        assertTrue("overlapping cases: $overlapping", overlapping >= 100)
    }

    @Test
    fun wrongLength_reportsExpectedAndActual() {
        val cedula = SyntheticIdNumbers.cedula(1)
        val ruc = SyntheticIdNumbers.naturalRuc(1)
        assertEquals(IdentificationVerdict.WrongLength(10, 9), validate(cedulaType, cedula.dropLast(1)))
        assertEquals(IdentificationVerdict.WrongLength(10, 11), validate(cedulaType, cedula + "0"))
        assertEquals(IdentificationVerdict.WrongLength(13, 12), validate(rucType, ruc.dropLast(1)))
        assertEquals(IdentificationVerdict.WrongLength(13, 10), validate(rucType, cedula))
    }

    @Test
    fun lengthIsCheckedBeforeNumericContent() {
        assertEquals(IdentificationVerdict.WrongLength(10, 3), validate(cedulaType, "abc"))
    }

    @Test
    fun nonAsciiDigit_isNotNumeric() {
        val arabicIndicThree = "٣"
        val cedula = SyntheticIdNumbers.cedula(2)
        val ruc = SyntheticIdNumbers.privateRuc(2)
        assertEquals(IdentificationVerdict.NotNumeric, validate(cedulaType, replaceAt(cedula, 5, arabicIndicThree)))
        assertEquals(IdentificationVerdict.NotNumeric, validate(rucType, replaceAt(ruc, 11, arabicIndicThree)))
        assertEquals(IdentificationVerdict.NotNumeric, validate(cedulaType, replaceAt(cedula, 9, "X")))
    }

    @Test
    fun numericContentIsCheckedBeforeProvince() {
        val value = replaceAt(replaceAt(SyntheticIdNumbers.cedula(3), 0, "0"), 1, "x")
        assertEquals(IdentificationVerdict.NotNumeric, validate(cedulaType, value))
    }

    @Test
    fun province_outsideTheAcceptedSetIsBadProvince() {
        for (province in listOf("00", "25", "26", "99")) {
            val cedula = province + SyntheticIdNumbers.cedula(4).substring(2)
            val ruc = province + SyntheticIdNumbers.privateRuc(4).substring(2)
            assertEquals("cedula province $province", IdentificationVerdict.BadProvince, validate(cedulaType, cedula))
            assertEquals("ruc province $province", IdentificationVerdict.BadProvince, validate(rucType, ruc))
        }
    }

    @Test
    fun province_isCheckedBeforeTheThirdDigit() {
        val value = "00" + replaceAt(SyntheticIdNumbers.cedula(5), 2, "9").substring(2)
        assertEquals(IdentificationVerdict.BadProvince, validate(cedulaType, value))
    }

    @Test
    fun provinces30And50_areAcceptedStructurally() {
        // The check digit is wrong for these rewritten values, which is the verdict that proves the
        // province step let them through.
        for (province in listOf("30", "50")) {
            val cedula = province + SyntheticIdNumbers.cedula(6).substring(2)
            assertFalse(
                "province $province",
                validate(cedulaType, cedula) == IdentificationVerdict.BadProvince,
            )
        }
    }

    @Test
    fun cedula_thirdDigitSixToNineIsBadThirdDigit() {
        for (third in '6'..'9') {
            val value = replaceAt(SyntheticIdNumbers.cedula(7), 2, third.toString())
            assertEquals("third digit $third", IdentificationVerdict.BadThirdDigit, validate(cedulaType, value))
        }
    }

    @Test
    fun ruc_thirdDigitSevenOrEightIsBadThirdDigit() {
        for (third in listOf("7", "8")) {
            val value = replaceAt(SyntheticIdNumbers.privateRuc(8), 2, third)
            assertEquals("third digit $third", IdentificationVerdict.BadThirdDigit, validate(rucType, value))
        }
    }

    @Test
    fun establishmentSuffixOfZeros_isBadEstablishmentSuffix() {
        for (i in 0 until 50) {
            assertEquals(
                "naturalRuc $i",
                IdentificationVerdict.BadEstablishmentSuffix,
                validate(rucType, SyntheticIdNumbers.cedula(i) + "000"),
            )
            assertEquals(
                "privateRuc $i",
                IdentificationVerdict.BadEstablishmentSuffix,
                validate(rucType, SyntheticIdNumbers.privateRuc(i).substring(0, 10) + "000"),
            )
            assertEquals(
                "publicRuc $i",
                IdentificationVerdict.BadEstablishmentSuffix,
                validate(rucType, SyntheticIdNumbers.publicRuc(i).substring(0, 9) + "0000"),
            )
        }
    }

    @Test
    fun checkDigitIsCheckedBeforeTheEstablishmentSuffix() {
        val value = SyntheticIdNumbers.withWrongCheckDigit(SyntheticIdNumbers.privateRuc(9), 9).substring(0, 10) + "000"
        assertEquals(IdentificationVerdict.BadCheckDigit, validate(rucType, value))
    }

    @Test
    fun consumidorFinal_isValidOnlyForExactlyThirteenNines() {
        assertEquals(IdentificationVerdict.Valid, validate("07", "9".repeat(13)))
        assertEquals(IdentificationVerdict.NotConsumidorFinal, validate("07", "9".repeat(12)))
        assertEquals(IdentificationVerdict.NotConsumidorFinal, validate("07", "9".repeat(14)))
        assertEquals(IdentificationVerdict.NotConsumidorFinal, validate("07", "9".repeat(12) + "8"))
        assertEquals(IdentificationVerdict.NotConsumidorFinal, validate("07", ""))
        assertEquals(IdentificationVerdict.NotConsumidorFinal, validate("07", SyntheticIdNumbers.privateRuc(1)))
    }

    @Test
    fun isConsumidorFinal_matchesTheSameConstant() {
        assertTrue(IdentificationValidator.isConsumidorFinal(SyntheticIdNumbers.consumidorFinal()))
        assertFalse(IdentificationValidator.isConsumidorFinal("9".repeat(12)))
        assertFalse(IdentificationValidator.isConsumidorFinal(SyntheticIdNumbers.publicRuc(1)))
    }

    @Test
    fun passportAndForeign_haveNoCheckDigit() {
        assertEquals(IdentificationVerdict.NoCheckDigit, validate("06", "AB123456"))
        assertEquals(IdentificationVerdict.NoCheckDigit, validate("08", "XY-98765"))
        assertEquals(IdentificationVerdict.NoCheckDigit, validate("06", "A".repeat(20)))
    }

    @Test
    fun passportAndForeign_blankAndTooLong() {
        assertEquals(IdentificationVerdict.Blank, validate("06", ""))
        assertEquals(IdentificationVerdict.Blank, validate("08", "   "))
        assertEquals(IdentificationVerdict.TooLong(20, 21), validate("06", "A".repeat(21)))
    }

    @Test
    fun tooLong_countsUtf16UnitsNotCodePoints() {
        val twentyOneUnits = "A".repeat(19) + "😀"
        assertEquals(IdentificationVerdict.TooLong(20, 21), validate("08", twentyOneUnits))
    }

    @Test
    fun blankCedulaOrRuc_isBlank() {
        assertEquals(IdentificationVerdict.Blank, validate(cedulaType, ""))
        assertEquals(IdentificationVerdict.Blank, validate(rucType, "   "))
    }

    @Test
    fun unknownType_isUnknownType() {
        for (type in listOf("09", "01", "", "4", "005")) {
            assertEquals("type '$type'", IdentificationVerdict.UnknownType, validate(type, SyntheticIdNumbers.cedula(1)))
        }
    }
}
