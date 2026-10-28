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

package com.neoterux.easyfact.invoicing.accesskey

import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.result.errorOrNull
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.time.LocalDate
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 49-digit access key. Expected check digits are computed here by a formulation written
 * independently of the production one, never copied from a specification sample: the SRI's own
 * sample key carries an incorrect check digit.
 */
class AccessKeyTest {

    private val issueDate: LocalDate = LocalDate.of(2026, 10, 27)

    private fun build(
        issueDate: LocalDate = this.issueDate,
        documentTypeCode: String = "01",
        issuerRuc: String = SyntheticIdentifiers.RUC,
        environmentCode: String = "1",
        establishment: String = "001",
        emissionPoint: String = "003",
        sequential: Long = 990064L,
        numericCode: String = "12345678",
        emissionTypeCode: String = "1",
    ): Outcome<AccessKey, AccessKeyError> = AccessKey.of(
        issueDate, documentTypeCode, issuerRuc, environmentCode, establishment, emissionPoint, sequential, numericCode, emissionTypeCode,
    )

    private fun key(outcome: Outcome<AccessKey, AccessKeyError> = build()): AccessKey =
        (outcome as? Outcome.Ok)?.value ?: throw AssertionError("expected a key, got $outcome")

    /**
     * The second formulation. The weighted sum runs left to right and each weight is derived from
     * the digit's distance to the end of the 48 digits, instead of from a cycling counter. The
     * result is read from the remainder directly: remainder 0 gives raw 11 (digit 0), remainder 1
     * gives raw 10 (digit 1), anything else gives 11 minus the remainder.
     */
    private fun remainder(first48: String): Int =
        first48.indices.sumOf { i -> (first48[i] - '0') * (2 + (47 - i) % 6) } % 11

    private fun expectedDigit(first48: String): Int = when (val m = remainder(first48)) {
        0 -> 0
        1 -> 1
        else -> 11 - m
    }

    private fun random48(random: Random): String = buildString { repeat(48) { append(('0' + random.nextInt(10))) } }

    // --- layout -----------------------------------------------------------------------------

    @Test
    fun aKeyIs49CharactersAndEntirelyNumeric() {
        val digits = key().digits
        assertEquals(49, digits.length)
        assertTrue(digits.all { it in '0'..'9' })
    }

    @Test
    fun everyFieldSitsAtItsDocumentedOffset() {
        val digits = key().digits
        assertEquals("27102026", digits.substring(0, 8))
        assertEquals("01", digits.substring(8, 10))
        assertEquals(SyntheticIdentifiers.RUC, digits.substring(10, 23))
        assertEquals("1", digits.substring(23, 24))
        assertEquals("001003", digits.substring(24, 30))
        assertEquals("000990064", digits.substring(30, 39))
        assertEquals("12345678", digits.substring(39, 47))
        assertEquals("1", digits.substring(47, 48))
        assertEquals(expectedDigit(digits.substring(0, 48)), digits[48] - '0')
    }

    @Test
    fun characterIndex23IsTheEnvironmentDigitForEnvironmentOne() {
        val key = key(build(environmentCode = "1"))
        assertEquals('1', key.digits[23])
        assertEquals('1', key.environmentDigit)
    }

    @Test
    fun characterIndex23IsTheEnvironmentDigitForEnvironmentTwo() {
        val key = key(build(environmentCode = "2"))
        assertEquals('2', key.digits[23])
        assertEquals('2', key.environmentDigit)
    }

    @Test
    fun theIssuerRucSegmentIsIndices10Through22() {
        assertEquals(SyntheticIdentifiers.RUC, key().issuerRucSegment)
    }

    @Test
    fun everySegmentAccessorReturnsExactlyItsOwnSegment() {
        // Each value is different from its neighbours, so an off-by-one range cannot pass by luck.
        val key = key(
            build(
                issueDate = LocalDate.of(2012, 3, 5),
                documentTypeCode = "07",
                environmentCode = "2",
                establishment = "014",
                emissionPoint = "025",
                sequential = 123_456_789L,
                numericCode = "87654321",
                emissionTypeCode = "2",
            ),
        )
        assertEquals("05032012", key.issueDateSegment)
        assertEquals("07", key.documentTypeSegment)
        assertEquals(SyntheticIdentifiers.RUC, key.issuerRucSegment)
        assertEquals('2', key.environmentDigit)
        assertEquals("014", key.establishmentSegment)
        assertEquals("025", key.emissionPointSegment)
        assertEquals("123456789", key.sequentialSegment)
        assertEquals('2', key.emissionTypeDigit)
    }

    @Test
    fun theSequentialSegmentIsTheZeroPaddedSequential() {
        assertEquals("000000007", key(build(sequential = 7L)).sequentialSegment)
    }

    @Test
    fun theSegmentAccessorsAndTheNumericCodeTileTheKeyWithoutOverlap() {
        val key = key()
        val rebuilt = key.issueDateSegment + key.documentTypeSegment + key.issuerRucSegment + key.environmentDigit +
            key.establishmentSegment + key.emissionPointSegment + key.sequentialSegment + "12345678" + key.emissionTypeDigit
        assertEquals(key.digits.substring(0, 48), rebuilt)
    }

    @Test
    fun theSegmentAccessorsDoNotRenderThroughToString() {
        val rendered = key().toString()
        assertFalse(rendered.contains(key().issueDateSegment))
        assertFalse(rendered.contains(key().sequentialSegment))
    }

    @Test
    fun theSequentialIsZeroPaddedToNineDigits() {
        assertEquals("000000007", key(build(sequential = 7L)).digits.substring(30, 39))
        assertEquals("999999999", key(build(sequential = 999_999_999L)).digits.substring(30, 39))
        assertEquals("000000000", key(build(sequential = 0L)).digits.substring(30, 39))
    }

    @Test
    fun theDateIsDayMonthYearWithLeadingZeros() {
        assertEquals("05032012", key(build(issueDate = LocalDate.of(2012, 3, 5))).digits.substring(0, 8))
    }

    // --- check digit ------------------------------------------------------------------------

    @Test
    fun rawResultTenIsWrittenAsOne() {
        val random = Random(20261027L)
        val vector = generateSequence { random48(random) }.first { remainder(it) == 1 }
        // The second formulation says the raw result is 11 - 1 = 10, which the scheme writes as 1.
        assertEquals(1, expectedDigit(vector))
        assertEquals(1, AccessKey.checkDigit(vector))
    }

    @Test
    fun rawResultElevenIsWrittenAsZero() {
        val random = Random(20261028L)
        val vector = generateSequence { random48(random) }.first { remainder(it) == 0 }
        // The second formulation says the raw result is 11 - 0 = 11, which the scheme writes as 0.
        assertEquals(0, expectedDigit(vector))
        assertEquals(0, AccessKey.checkDigit(vector))
    }

    @Test
    fun checkDigitAgreesWithTheSecondFormulationOverAThousandInputs() {
        val random = Random(1L)
        val seen = mutableSetOf<Int>()
        repeat(1000) {
            val vector = random48(random)
            val expected = expectedDigit(vector)
            assertEquals("input $it", expected, AccessKey.checkDigit(vector))
            seen += expected
        }
        // Guards the cross-check against a degenerate generator: every digit 0 to 9 must occur.
        assertEquals((0..9).toSet(), seen)
    }

    @Test
    fun checkDigitRejectsAnInputThatIsNot48Digits() {
        assertThrows(IllegalArgumentException::class.java) { AccessKey.checkDigit("1".repeat(47)) }
        assertThrows(IllegalArgumentException::class.java) { AccessKey.checkDigit("1".repeat(49)) }
        assertThrows(IllegalArgumentException::class.java) { AccessKey.checkDigit("1".repeat(47) + "x") }
    }

    // --- the key is derived from identity, never from content -------------------------------

    @Test
    fun theSameInputsAlwaysGiveTheSameKey() {
        assertEquals(key(), key())
        assertEquals(key().hashCode(), key().hashCode())
    }

    @Test
    fun aDifferentSequentialGivesADifferentKey() {
        assertNotEquals(key(build(sequential = 1L)), key(build(sequential = 2L)))
    }

    @Test
    fun theGeneratorAdmitsNoLineItemAmountDescriptionOrCustomerValue() {
        val of = AccessKey.Companion::class.java.methods.single { it.name == "of" }
        assertEquals(9, of.parameterCount)
        val admitted = setOf(LocalDate::class.java, String::class.java, java.lang.Long.TYPE)
        assertTrue(of.parameterTypes.joinToString { it.name }, of.parameterTypes.all { it in admitted })
        assertEquals(1, of.parameterTypes.count { it == LocalDate::class.java })
        assertEquals(1, of.parameterTypes.count { it == java.lang.Long.TYPE })
        assertEquals(7, of.parameterTypes.count { it == String::class.java })
    }

    @Test
    fun theKeyDoesNotRenderItsDigitsWhenPrinted() {
        val key = key()
        assertFalse(key.toString().contains(key.digits))
        assertFalse(key.toString().contains(SyntheticIdentifiers.RUC))
    }

    // --- typed errors: one test per variant, and no malformed key ---------------------------

    @Test
    fun aRucThatIsNot13DigitsIsATypedError() {
        assertEquals(
            AccessKeyError.SegmentWrongLength("issuerRuc", 13, 12),
            build(issuerRuc = SyntheticIdentifiers.RUC.dropLast(1)).errorOrNull(),
        )
        assertEquals(
            AccessKeyError.SegmentWrongLength("issuerRuc", 13, 14),
            build(issuerRuc = SyntheticIdentifiers.RUC + "1").errorOrNull(),
        )
    }

    @Test
    fun aNonNumericSegmentIsATypedError() {
        assertEquals(AccessKeyError.SegmentNotNumeric("issuerRuc"), build(issuerRuc = "99999x9999001").errorOrNull())
        assertEquals(AccessKeyError.SegmentNotNumeric("numericCode"), build(numericCode = "1234567a").errorOrNull())
        assertEquals(AccessKeyError.SegmentNotNumeric("environmentCode"), build(environmentCode = "x").errorOrNull())
        assertEquals(AccessKeyError.SegmentNotNumeric("establishment"), build(establishment = "00 ").errorOrNull())
    }

    @Test
    fun aDigitThatIsNotAnAsciiDigitIsNotNumeric() {
        // U+0661 is an Arabic-Indic digit: `Char.isDigit()` says yes, the SRI says no.
        assertEquals(AccessKeyError.SegmentNotNumeric("emissionTypeCode"), build(emissionTypeCode = "١").errorOrNull())
    }

    @Test
    fun aSequentialAboveNineDigitsIsATypedError() {
        assertEquals(AccessKeyError.SequentialOutOfRange(1_000_000_000L), build(sequential = 1_000_000_000L).errorOrNull())
        assertEquals(AccessKeyError.SequentialOutOfRange(-1L), build(sequential = -1L).errorOrNull())
    }

    @Test
    fun everyFixedLengthSegmentIsLengthChecked() {
        assertEquals(AccessKeyError.SegmentWrongLength("documentTypeCode", 2, 1), build(documentTypeCode = "1").errorOrNull())
        assertEquals(AccessKeyError.SegmentWrongLength("environmentCode", 1, 2), build(environmentCode = "12").errorOrNull())
        assertEquals(AccessKeyError.SegmentWrongLength("establishment", 3, 2), build(establishment = "01").errorOrNull())
        assertEquals(AccessKeyError.SegmentWrongLength("emissionPoint", 3, 4), build(emissionPoint = "0003").errorOrNull())
        assertEquals(AccessKeyError.SegmentWrongLength("numericCode", 8, 7), build(numericCode = "1234567").errorOrNull())
        assertEquals(AccessKeyError.SegmentWrongLength("emissionTypeCode", 1, 0), build(emissionTypeCode = "").errorOrNull())
    }

    @Test
    fun aYearOutsideFourDigitsIsATypedErrorNotAMalformedKey() {
        val error = build(issueDate = LocalDate.of(10000, 1, 1)).errorOrNull()
        assertTrue("got $error", error is AccessKeyError.SegmentWrongLength || error is AccessKeyError.SegmentNotNumeric)
    }

    @Test
    fun noErrorRendersTheRucOrAKey() {
        val rendered = build(issuerRuc = SyntheticIdentifiers.RUC.dropLast(1)).errorOrNull().toString()
        assertFalse(rendered.contains(SyntheticIdentifiers.RUC.dropLast(1)))
    }
}
