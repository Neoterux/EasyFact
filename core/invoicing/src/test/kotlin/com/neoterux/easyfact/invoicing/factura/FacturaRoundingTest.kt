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

import java.math.BigDecimal
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every expected value here is a literal string. Recomputing it through `setScale` would make
 * the test agree with the code under test by construction and prove nothing: the point is to pin
 * what the document will actually carry.
 *
 * The boundary cases are the ones where a cent can appear or vanish, at every scale the writer
 * uses, in both directions, and for a negative value as well as a positive one.
 */
class FacturaRoundingTest {

    // --- amounts: two decimals ---------------------------------------------------------------

    @Test
    fun anAmountExactlyOnTheHalfBoundaryRoundsAwayFromZero() {
        assertEquals("0.13", Money.amount(BigDecimal("0.125")))
        assertEquals("0.14", Money.amount(BigDecimal("0.135")))
        assertEquals("-0.13", Money.amount(BigDecimal("-0.125")))
        assertEquals("-0.14", Money.amount(BigDecimal("-0.135")))
    }

    @Test
    fun anAmountBelowTheHalfBoundaryRoundsDown() {
        assertEquals("0.12", Money.amount(BigDecimal("0.1249999")))
        assertEquals("-0.12", Money.amount(BigDecimal("-0.1249999")))
    }

    @Test
    fun anAmountIsRoundedAndNotTruncated() {
        // Truncation would give 1.99 and 0.99; both are a cent short.
        assertEquals("2.00", Money.amount(BigDecimal("1.999999999")))
        assertEquals("1.00", Money.amount(BigDecimal("0.999")))
        assertEquals("-2.00", Money.amount(BigDecimal("-1.999999999")))
    }

    @Test
    fun aZeroAmountCarriesTheScaleAndNoSign() {
        assertEquals("0.00", Money.amount(BigDecimal.ZERO))
        assertEquals("0.00", Money.amount(BigDecimal("0")))
        assertEquals("0.00", Money.amount(BigDecimal("0.000")))
        // Half-up on a negative half that falls inside the first cent still rounds away from zero.
        assertEquals("-0.01", Money.amount(BigDecimal("-0.005")))
    }

    // --- quantities and unit prices: six decimals --------------------------------------------

    @Test
    fun aQuantityExactlyOnTheHalfBoundaryOfTheSeventhDecimalRoundsAwayFromZero() {
        assertEquals("1.234568", Money.quantity(BigDecimal("1.2345675")))
        assertEquals("1.234567", Money.quantity(BigDecimal("1.2345674")))
        assertEquals("-1.234568", Money.quantity(BigDecimal("-1.2345675")))
    }

    @Test
    fun aUnitPriceExactlyOnTheHalfBoundaryOfTheSeventhDecimalRoundsAwayFromZero() {
        assertEquals("12.345679", Money.unitPrice(BigDecimal("12.3456785")))
        assertEquals("12.345678", Money.unitPrice(BigDecimal("12.3456784")))
        assertEquals("-12.345679", Money.unitPrice(BigDecimal("-12.3456785")))
    }

    @Test
    fun aQuantityAndAUnitPriceAreRoundedAndNotTruncated() {
        assertEquals("1.000000", Money.quantity(BigDecimal("0.9999999")))
        assertEquals("1.000000", Money.unitPrice(BigDecimal("0.9999999")))
    }

    @Test
    fun aWholeQuantityAndUnitPriceStillCarrySixDecimals() {
        assertEquals("1.000000", Money.quantity(BigDecimal("1")))
        assertEquals("100.000000", Money.unitPrice(BigDecimal("100")))
    }

    // --- rates: two decimals -----------------------------------------------------------------

    @Test
    fun aRateExactlyOnTheHalfBoundaryRoundsAwayFromZero() {
        assertEquals("15.01", Money.rate(BigDecimal("15.005")))
        assertEquals("15.00", Money.rate(BigDecimal("15.004")))
    }

    @Test
    fun aZeroRateIsWrittenAtScaleRatherThanAsABareZero() {
        assertEquals("0.00", Money.rate(BigDecimal.ZERO))
    }

    // --- never an exponent, never a locale separator ------------------------------------------

    @Test
    fun aValueThatToStringWouldWriteAsAnExponentIsWrittenPlain() {
        // BigDecimal("1E+2").toString() is "1E+2"; the SRI's pattern facets reject an exponent.
        assertEquals("1E+2", BigDecimal("1E+2").toString())
        assertEquals("100.00", Money.amount(BigDecimal("1E+2")))
        assertEquals("10000000.000000", Money.quantity(BigDecimal("1E+7")))
        assertEquals("0.00", Money.amount(BigDecimal("1E-8")))
        assertEquals("0.000000", Money.quantity(BigDecimal("1E-8")))
    }

    @Test
    fun theDefaultLocaleCannotChangeTheSeparatorOrAddGrouping() {
        val original = Locale.getDefault()
        try {
            // A locale whose decimal separator is a comma and whose grouping separator is a dot.
            Locale.setDefault(Locale.GERMANY)
            assertEquals("1234.57", Money.amount(BigDecimal("1234.565")))
            assertEquals("1234.000000", Money.quantity(BigDecimal("1234")))
        } finally {
            Locale.setDefault(original)
        }
    }

    // --- the API itself ------------------------------------------------------------------------

    @Test
    fun thereIsNoEntryPointForADoubleAFloatOrAPreFormattedString() {
        val forbidden = setOf(
            java.lang.Double.TYPE,
            java.lang.Float.TYPE,
            java.lang.Double::class.java,
            java.lang.Float::class.java,
            java.lang.String::class.java,
        )
        val offenders = Money::class.java.methods
            .filter { it.declaringClass == Money::class.java }
            .filter { method -> method.parameterTypes.any { it in forbidden } }
            .map { it.name }
        assertTrue("Money must take BigDecimal only, but found $offenders", offenders.isEmpty())
    }
}
