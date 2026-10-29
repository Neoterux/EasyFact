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

package com.neoterux.easyfact.invoicing.totals

import com.neoterux.easyfact.invoicing.result.Computed
import com.neoterux.easyfact.invoicing.result.EngineWarning
import com.neoterux.easyfact.invoicing.result.Outcome
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-derived vectors for the totals contract (SRI-03, D-21 to D-28).
 *
 * Every expected value is a literal string and every actual value is rendered with `toPlainString`,
 * which is scale-sensitive: a value left at the wrong scale fails here instead of in the document.
 * Every `BigDecimal` is built from a string. Each vector carries its derivation next to it, so a
 * reader can check the arithmetic without running anything.
 *
 * What reconciles exactly (no tolerance): the bases, `importeTotal` and the group bases. What may
 * differ by cents by design (D-21): the sum of the per-line IVA values against the group IVA.
 */
class InvoiceCalculatorTest {

    private fun line(quantity: String, unitPrice: String, code: String): LineInput =
        LineInput(BigDecimal(quantity), BigDecimal(unitPrice), code)

    private fun computed(lines: List<LineInput>): Computed<InvoiceTotals> =
        when (val outcome = InvoiceCalculator.calculate(lines)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected totals, got ${outcome.error}")
        }

    private fun error(lines: List<LineInput>): TotalsError =
        when (val outcome = InvoiceCalculator.calculate(lines)) {
            is Outcome.Ok -> throw AssertionError("expected an error, got ${outcome.value}")
            is Outcome.Err -> outcome.error
        }

    /**
     * The three identities that hold exactly for every vector (SRI-03): the invoice base is the sum of
     * the line bases; each group base is the sum of the bases at that code; `importeTotal` is the base
     * plus the group IVA plus the tip. Nothing here reads the per-line IVA values.
     */
    private fun assertReconciles(totals: InvoiceTotals) {
        val lineBases = totals.lines.fold(BigDecimal("0.00")) { acc, line -> acc.add(line.base) }
        assertEquals(lineBases.toPlainString(), totals.totalWithoutTaxes.toPlainString())
        for (group in totals.taxTotals) {
            val basesAtCode = totals.lines
                .filter { it.tax.rateCode == group.rateCode }
                .fold(BigDecimal("0.00")) { acc, line -> acc.add(line.base) }
            assertEquals("group ${group.rateCode}", basesAtCode.toPlainString(), group.taxableBase.toPlainString())
        }
        val groupIva = totals.taxTotals.fold(BigDecimal("0.00")) { acc, group -> acc.add(group.amount) }
        assertEquals("0.00", totals.tip.toPlainString())
        assertEquals(
            lineBases.add(groupIva).add(totals.tip).toPlainString(),
            totals.grandTotal.toPlainString(),
        )
    }

    private fun groupIvaOf(totals: InvoiceTotals): String =
        totals.taxTotals.fold(BigDecimal("0.00")) { acc, group -> acc.add(group.amount) }.toPlainString()

    // --- D-21: the per-line sum and the group value diverge ----------------------------------------

    @Test
    fun perLineAndGroupIvaDivergeByOneCentAsTheSriInvoicerDoes() {
        // Three lines of 1 x 0.10 at 15%.
        // Per line: 0.10 x 15% = 0.015 -> 0.02 (half rounds up), so the per-line sum is 0.06.
        // Group: base 0.30 x 15% = 0.045 -> 0.05, computed independently of the per-line values.
        // importeTotal = 0.30 + 0.05 = 0.35.
        val totals = computed(List(3) { line("1", "0.10", "4") }).value
        for (calculated in totals.lines) {
            assertEquals("0.10", calculated.base.toPlainString())
            assertEquals("0.02", calculated.tax.amount.toPlainString())
        }
        assertEquals("0.06", totals.lineIvaSum.toPlainString())
        assertEquals(1, totals.taxTotals.size)
        assertEquals("0.30", totals.taxTotals[0].taxableBase.toPlainString())
        assertEquals("0.05", totals.taxTotals[0].amount.toPlainString())
        assertEquals("0.30", totals.totalWithoutTaxes.toPlainString())
        assertEquals("0.35", totals.grandTotal.toPlainString())
        assertTrue(totals.lineIvaSum.compareTo(totals.taxTotals[0].amount) != 0)
        assertReconciles(totals)
    }

    @Test
    fun sixLinesOfThreeCentsDivergeByThreeCents() {
        // 6 x (1 x 0.03) at 15%. Per line: 0.03 x 15% = 0.0045 -> 0.00, so the per-line sum is 0.00.
        // Group: base 0.18 x 15% = 0.027 -> 0.03. importeTotal = 0.18 + 0.03 = 0.21.
        val totals = computed(List(6) { line("1", "0.03", "4") }).value
        for (calculated in totals.lines) assertEquals("0.00", calculated.tax.amount.toPlainString())
        assertEquals("0.00", totals.lineIvaSum.toPlainString())
        assertEquals("0.18", totals.taxTotals[0].taxableBase.toPlainString())
        assertEquals("0.03", totals.taxTotals[0].amount.toPlainString())
        assertEquals("0.21", totals.grandTotal.toPlainString())
        assertTrue(totals.lineIvaSum.compareTo(totals.taxTotals[0].amount) != 0)
        assertReconciles(totals)
    }

    @Test
    fun twoHalfCentBasesRoundUpAndDivergeByOneCent() {
        // 2 x (1 x 0.035) at 15%. Base: 0.035 -> 0.04 each (half rounds up), so the group base is 0.08.
        // Per line: 0.04 x 15% = 0.006 -> 0.01, so the per-line sum is 0.02.
        // Group: 0.08 x 15% = 0.012 -> 0.01. importeTotal = 0.08 + 0.01 = 0.09.
        val totals = computed(List(2) { line("1", "0.035", "4") }).value
        for (calculated in totals.lines) {
            assertEquals("0.04", calculated.base.toPlainString())
            assertEquals("0.01", calculated.tax.amount.toPlainString())
        }
        assertEquals("0.02", totals.lineIvaSum.toPlainString())
        assertEquals("0.08", totals.taxTotals[0].taxableBase.toPlainString())
        assertEquals("0.01", totals.taxTotals[0].amount.toPlainString())
        assertEquals("0.09", totals.grandTotal.toPlainString())
        assertTrue(totals.lineIvaSum.compareTo(totals.taxTotals[0].amount) != 0)
        assertReconciles(totals)
    }

    // --- vectors where the two readings agree ------------------------------------------------------

    @Test
    fun threeLinesOfThirtyThreeThirtyThreeAgreeBothWays() {
        // 3 x (1 x 33.33) at 15%. Per line: 4.9995 -> 5.00; the per-line sum is 15.00.
        // Group: base 99.99 x 15% = 14.9985 -> 15.00. importeTotal = 99.99 + 15.00 = 114.99.
        val totals = computed(List(3) { line("1", "33.33", "4") }).value
        for (calculated in totals.lines) assertEquals("5.00", calculated.tax.amount.toPlainString())
        assertEquals("15.00", totals.lineIvaSum.toPlainString())
        assertEquals("99.99", totals.taxTotals[0].taxableBase.toPlainString())
        assertEquals("15.00", totals.taxTotals[0].amount.toPlainString())
        assertEquals("114.99", totals.grandTotal.toPlainString())
        assertReconciles(totals)
    }

    @Test
    fun aFractionalQuantityTimesAFractionalPriceRoundsTheBaseUp() {
        // 1 x (1.5 x 10.005): 1.5 x 10.005 = 15.0075 -> 15.01. IVA: 15.01 x 15% = 2.2515 -> 2.25.
        // importeTotal = 15.01 + 2.25 = 17.26.
        val totals = computed(listOf(line("1.5", "10.005", "4"))).value
        assertEquals("15.01", totals.lines[0].base.toPlainString())
        assertEquals("2.25", totals.lines[0].tax.amount.toPlainString())
        assertEquals("2.25", totals.taxTotals[0].amount.toPlainString())
        assertEquals("2.25", totals.lineIvaSum.toPlainString())
        assertEquals("17.26", totals.grandTotal.toPlainString())
        assertReconciles(totals)
    }

    @Test
    fun aHalfCentBaseFromAThreeUnitQuantityRoundsUp() {
        // 1 x (3 x 0.335): 3 x 0.335 = 1.005 -> 1.01. IVA: 1.01 x 15% = 0.1515 -> 0.15.
        // importeTotal = 1.01 + 0.15 = 1.16.
        val totals = computed(listOf(line("3", "0.335", "4"))).value
        assertEquals("1.01", totals.lines[0].base.toPlainString())
        assertEquals("0.15", totals.taxTotals[0].amount.toPlainString())
        assertEquals("0.15", totals.lineIvaSum.toPlainString())
        assertEquals("1.16", totals.grandTotal.toPlainString())
        assertReconciles(totals)
    }

    // --- rounding boundaries -----------------------------------------------------------------------

    @Test
    fun aBaseOfExactlyHalfACentRoundsUpAndOneStepBelowRoundsDown() {
        // 1 x 0.005 = 0.005 -> 0.01 (half up). 1 x 0.004999 = 0.004999 -> 0.00.
        assertEquals("0.01", computed(listOf(line("1", "0.005", "0"))).value.lines[0].base.toPlainString())
        assertEquals("0.00", computed(listOf(line("1", "0.004999", "0"))).value.lines[0].base.toPlainString())
    }

    @Test
    fun aBaseHalfwayBetweenTwoEvenCentsStillRoundsUpNotToEven() {
        // 1 x 0.125 = 0.125 -> 0.13 under HALF_UP; HALF_EVEN would give 0.12.
        // 1 x 0.135 = 0.135 -> 0.14 under both, so both neighbours of the even/odd split are pinned.
        assertEquals("0.13", computed(listOf(line("1", "0.125", "0"))).value.lines[0].base.toPlainString())
        assertEquals("0.14", computed(listOf(line("1", "0.135", "0"))).value.lines[0].base.toPlainString())
    }

    @Test
    fun anIvaOfExactlyHalfACentRoundsUp() {
        // 0.10 x 15% = 0.015 -> 0.02. A single line also has a group of 0.015 -> 0.02.
        val totals = computed(listOf(line("1", "0.10", "4"))).value
        assertEquals("0.02", totals.lines[0].tax.amount.toPlainString())
        assertEquals("0.02", totals.taxTotals[0].amount.toPlainString())
        assertEquals("0.12", totals.grandTotal.toPlainString())
    }

    @Test
    fun aGroupIvaOfAHalfCentRoundsUpWhereHalfEvenWouldNot() {
        // Base 0.30 x 15% = 0.045 -> 0.05 under HALF_UP; HALF_EVEN would give 0.04.
        val totals = computed(listOf(line("1", "0.30", "4"))).value
        assertEquals("0.05", totals.taxTotals[0].amount.toPlainString())
        assertEquals("0.35", totals.grandTotal.toPlainString())
    }

    // --- precision (D-23) --------------------------------------------------------------------------

    @Test
    fun quantityAndUnitPriceAreRoundedToSixDecimalsHalfUpBeforeMultiplying() {
        // 1.0000005 -> 1.000001 under HALF_UP (HALF_EVEN would give 1.000000). 1.000001 x 1 = 1.000001 -> 1.00.
        val first = computed(listOf(line("1.0000005", "1", "0"))).value.lines[0]
        assertEquals("1.000001", first.quantity.toPlainString())
        assertEquals("1.000000", first.unitPrice.toPlainString())
        assertEquals("1.00", first.base.toPlainString())

        // 0.0000005 -> 0.000001 as a unit price; 1 x 0.000001 = 0.000001 -> 0.00.
        val second = computed(listOf(line("1", "0.0000005", "0"))).value.lines[0]
        assertEquals("1.000000", second.quantity.toPlainString())
        assertEquals("0.000001", second.unitPrice.toPlainString())
        assertEquals("0.00", second.base.toPlainString())
    }

    @Test
    fun theBaseIsComputedFromTheRoundedQuantityAndPriceNotTheRawOnes() {
        // Quantity 0.9999995 rounds half up to 1.000000 and price 0.005 stays 0.005000, so the base is
        // 1.000000 x 0.005000 = 0.005 -> 0.01. Multiplying the raw values instead would give
        // 0.9999995 x 0.005, a hair under half a cent, -> 0.00, which the SRI would not recompute.
        val line = computed(listOf(line("0.9999995", "0.005", "0"))).value.lines[0]
        assertEquals("1.000000", line.quantity.toPlainString())
        assertEquals("0.01", line.base.toPlainString())
    }

    // --- grouping, ordering, tax kinds -------------------------------------------------------------

    @Test
    fun twoLinesAtTheSameCodeMergeIntoOneGroup() {
        // 10.00 + 5.00 = 15.00 at 15%: group IVA 2.25. A 0% line of 20.00 forms the second group.
        // totalSinImpuestos = 35.00; importeTotal = 35.00 + 0.00 + 2.25 = 37.25.
        val totals = computed(
            listOf(line("1", "10.00", "4"), line("1", "20.00", "0"), line("1", "5.00", "4")),
        ).value
        assertEquals(listOf("0", "4"), totals.taxTotals.map { it.rateCode })
        assertEquals("20.00", totals.taxTotals[0].taxableBase.toPlainString())
        assertEquals("0.00", totals.taxTotals[0].amount.toPlainString())
        assertEquals("15.00", totals.taxTotals[1].taxableBase.toPlainString())
        assertEquals("2.25", totals.taxTotals[1].amount.toPlainString())
        assertEquals("35.00", totals.totalWithoutTaxes.toPlainString())
        assertEquals("37.25", totals.grandTotal.toPlainString())
        assertEquals(listOf("4", "0", "4"), totals.lines.map { it.tax.rateCode })
        assertReconciles(totals)
    }

    @Test
    fun aZeroPercentAndAFifteenPercentInvoiceYieldsExactlyTwoGroups() {
        // 1 x 100.00 at 0% -> base 100.00, IVA 0.00. 2 x 25.50 at 15% -> base 51.00, IVA 7.65.
        // totalSinImpuestos = 151.00; importeTotal = 151.00 + 0.00 + 7.65 = 158.65.
        val totals = computed(listOf(line("1", "100.00", "0"), line("2", "25.50", "4"))).value
        assertEquals(2, totals.taxTotals.size)
        assertEquals("100.00", totals.taxTotals[0].taxableBase.toPlainString())
        assertEquals("0.00", totals.taxTotals[0].amount.toPlainString())
        assertEquals("51.00", totals.taxTotals[1].taxableBase.toPlainString())
        assertEquals("7.65", totals.taxTotals[1].amount.toPlainString())
        assertEquals("151.00", totals.totalWithoutTaxes.toPlainString())
        assertEquals("158.65", totals.grandTotal.toPlainString())
        assertReconciles(totals)
    }

    @Test
    fun groupsAreOrderedByTheNumericCodeNotTheString() {
        // As strings "10" sorts before "2"; as integers the order is 2, 4, 10.
        // 10.00 each: code 2 (12%) 1.20, code 4 (15%) 1.50, code 10 (13%) 1.30; base 30.00;
        // importeTotal = 30.00 + 1.20 + 1.50 + 1.30 = 34.00.
        val lines = listOf(line("1", "10.00", "10"), line("1", "10.00", "2"), line("1", "10.00", "4"))
        val totals = computed(lines).value
        assertEquals(listOf("2", "4", "10"), totals.taxTotals.map { it.rateCode })
        assertEquals(listOf("1.20", "1.50", "1.30"), totals.taxTotals.map { it.amount.toPlainString() })
        assertEquals("34.00", totals.grandTotal.toPlainString())
        assertEquals(listOf("10", "2", "4"), totals.lines.map { it.tax.rateCode })
        assertReconciles(totals)
    }

    @Test
    fun groupOrderDoesNotDependOnTheOrderOfTheLines() {
        val codes = listOf("0", "2", "4", "10")
        val orders = listOf(codes, codes.reversed(), listOf("4", "10", "0", "2"), listOf("10", "4", "2", "0"))
        for (order in orders) {
            val totals = computed(order.map { line("1", "10.00", it) }).value
            assertEquals("order $order", codes, totals.taxTotals.map { it.rateCode })
            assertEquals("order $order", order, totals.lines.map { it.tax.rateCode })
        }
    }

    @Test
    fun noObjetoAndExentoDeclareTheFullBaseWithAZeroRateAndAmount() {
        // Codes 6 and 7: tarifa 0.00 and valor 0.00 on the full 40.00 base; importeTotal 40.00.
        for (code in listOf("6", "7")) {
            val totals = computed(listOf(line("1", "40.00", code))).value
            assertEquals("0.00", totals.lines[0].tax.rate.toPlainString())
            assertEquals("0.00", totals.lines[0].tax.amount.toPlainString())
            assertEquals("40.00", totals.lines[0].tax.taxableBase.toPlainString())
            assertEquals(code, totals.taxTotals[0].rateCode)
            assertEquals("40.00", totals.taxTotals[0].taxableBase.toPlainString())
            assertEquals("0.00", totals.taxTotals[0].amount.toPlainString())
            assertEquals("40.00", totals.grandTotal.toPlainString())
            assertReconciles(totals)
        }
    }

    @Test
    fun theLineRateIsWrittenAsTwoDecimals() {
        val totals = computed(listOf(line("1", "10.00", "4"), line("1", "10.00", "5"))).value
        assertEquals(listOf("15.00", "5.00"), totals.lines.map { it.tax.rate.toPlainString() })
    }

    @Test
    fun aLumpSumIsOneLineOfQuantityOneAtTheEnteredAmount() {
        // D-26: LineInput(1, 250.00, "0") -> base 250.00, IVA 0.00, importeTotal 250.00.
        val totals = computed(listOf(LineInput(BigDecimal("1"), BigDecimal("250.00"), "0"))).value
        assertEquals("250.00", totals.lines[0].base.toPlainString())
        assertEquals("0.00", totals.lines[0].tax.amount.toPlainString())
        assertEquals("250.00", totals.grandTotal.toPlainString())
        assertReconciles(totals)
    }

    @Test
    fun aSingleLineIsAValidInvoiceWithoutWarnings() {
        val result = computed(listOf(line("1", "10.00", "4")))
        assertEquals(emptyList<EngineWarning>(), result.warnings)
        assertEquals("11.50", result.value.grandTotal.toPlainString())
    }

    // --- refusals (D-27, D-29) ---------------------------------------------------------------------

    @Test
    fun anEmptyLineListIsRefused() {
        assertEquals(TotalsError.NoLines, error(emptyList()))
    }

    @Test
    fun aNegativeQuantityNamesItsLine() {
        val lines = listOf(line("1", "1.00", "4"), line("1", "1.00", "4"), line("-1", "1.00", "4"))
        assertEquals(TotalsError.NegativeQuantity(2), error(lines))
    }

    @Test
    fun aNegativeUnitPriceNamesItsLine() {
        assertEquals(TotalsError.NegativeUnitPrice(0), error(listOf(line("1", "-0.01", "4"))))
    }

    @Test
    fun aNegativeLumpSumAmountIsANegativeUnitPrice() {
        val lines = listOf(line("1", "10.00", "0"), line("1", "-250.00", "0"))
        assertEquals(TotalsError.NegativeUnitPrice(1), error(lines))
    }

    @Test
    fun aQuantityBelowOneMillionthIsStillNegativeBeforeRounding() {
        // The sign is read from the value as entered; rounding to six decimals would hide it as zero.
        assertEquals(TotalsError.NegativeQuantity(0), error(listOf(line("-0.0000001", "1.00", "4"))))
    }

    @Test
    fun theFirstErrorInLineOrderIsTheOneReturned() {
        // Line 0 has a negative price and line 1 a negative quantity: line 0 wins.
        assertEquals(
            TotalsError.NegativeUnitPrice(0),
            error(listOf(line("1", "-1.00", "4"), line("-1", "1.00", "4"))),
        )
        // On one line the quantity is checked before the price, and both before the code.
        assertEquals(TotalsError.NegativeQuantity(0), error(listOf(line("-1", "-1.00", "9"))))
        assertEquals(TotalsError.NegativeUnitPrice(0), error(listOf(line("1", "-1.00", "9"))))
        // An unknown code on an earlier line comes before a negative quantity on a later one.
        assertEquals(
            TotalsError.UnknownIvaCode(0, "9"),
            error(listOf(line("1", "1.00", "9"), line("-1", "1.00", "4"))),
        )
    }

    @Test
    fun anUnknownCodeIsRefusedWithItsPositionAndCode() {
        assertEquals(TotalsError.UnknownIvaCode(1, "9"), error(listOf(line("1", "1.00", "4"), line("1", "1.00", "9"))))
        assertEquals(TotalsError.UnknownIvaCode(0, " 4"), error(listOf(line("1", "1.00", " 4"))))
    }

    @Test
    fun ivaDiferenciadoIsNotComputable() {
        assertEquals(TotalsError.IvaCodeNotComputable(0, "8"), error(listOf(line("1", "1.00", "8"))))
    }

    // --- warnings (D-27): the result is Ok and says what it noticed --------------------------------

    @Test
    fun aZeroQuantityOnTheOnlyLineWarnsAboutTheQuantityAndTheTotal() {
        val result = computed(listOf(line("0", "10.00", "4")))
        assertEquals(listOf(EngineWarning.ZeroQuantity(0), EngineWarning.ZeroInvoiceTotal), result.warnings)
        assertEquals("0.00", result.value.grandTotal.toPlainString())
    }

    @Test
    fun aQuantityThatRoundsToZeroIsAZeroQuantity() {
        // 0.0000004 -> 0.000000 at six decimals.
        val result = computed(listOf(line("1", "5.00", "0"), line("0.0000004", "5.00", "0")))
        assertEquals(listOf(EngineWarning.ZeroQuantity(1)), result.warnings)
    }

    @Test
    fun aZeroUnitPriceWarnsAboutTheLineTotalOnly() {
        val result = computed(listOf(line("1", "10.00", "0"), line("1", "0.00", "0")))
        assertEquals(listOf(EngineWarning.ZeroLineTotal(1)), result.warnings)
        assertEquals("10.00", result.value.grandTotal.toPlainString())
    }

    @Test
    fun aBaseThatRoundsToZeroWarnsAboutTheLineTotal() {
        // 0.001 x 1 = 0.001 -> 0.00, and the quantity itself is not zero.
        val result = computed(listOf(line("0.001", "1", "0"), line("1", "3.00", "0")))
        assertEquals(listOf(EngineWarning.ZeroLineTotal(0)), result.warnings)
    }

    @Test
    fun warningsComeInLineOrderThenTheInvoiceLevelOne() {
        // Line 0: zero quantity. Line 1: zero line total. The invoice total is then zero too.
        val result = computed(listOf(line("0", "10.00", "4"), line("1", "0.00", "4")))
        assertEquals(
            listOf(EngineWarning.ZeroQuantity(0), EngineWarning.ZeroLineTotal(1), EngineWarning.ZeroInvoiceTotal),
            result.warnings,
        )
        assertReconciles(result.value)
    }

    @Test
    fun aNonZeroInvoiceRaisesNoZeroTotalWarning() {
        val result = computed(listOf(line("1", "0.01", "0")))
        assertEquals(emptyList<EngineWarning>(), result.warnings)
        assertEquals("0.01", result.value.grandTotal.toPlainString())
    }
}
