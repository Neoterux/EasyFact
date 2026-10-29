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

import com.neoterux.easyfact.invoicing.factura.LineTax
import com.neoterux.easyfact.invoicing.factura.TaxTotal
import com.neoterux.easyfact.invoicing.result.Computed
import com.neoterux.easyfact.invoicing.result.EngineWarning
import com.neoterux.easyfact.invoicing.result.Outcome
import java.math.BigDecimal
import java.math.RoundingMode

/** One line as the calculator sees it. The totals package does not depend on `mapping`. */
data class LineInput(
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    /** The SRI `codigoPorcentaje`. */
    val ivaRateCode: String,
)

/**
 * One line after arithmetic: [quantity] and [unitPrice] as they will be written (six decimals), the
 * [base] (`precioTotalSinImpuesto`) and the line's own [tax].
 */
data class CalculatedLine(
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val base: BigDecimal,
    val tax: LineTax,
)

/**
 * The arithmetic of one invoice.
 *
 * Every amount is held at its final scale (two decimals for money, six for quantity and unit price),
 * because [BigDecimal.equals] is scale-sensitive and data-class equality must be deterministic.
 * [lineIvaSum] is the sum of the per-line IVA values, exposed as a diagnostic: D-21 lets it differ by
 * a cent from the group values in [taxTotals], and that difference is accepted, not corrected.
 */
data class InvoiceTotals(
    val lines: List<CalculatedLine>,
    val taxTotals: List<TaxTotal>,
    val totalWithoutTaxes: BigDecimal,
    val tip: BigDecimal,
    val grandTotal: BigDecimal,
    val lineIvaSum: BigDecimal,
)

/** Why the arithmetic could not be done. No variant carries an amount or any caller text, only a position. */
sealed interface TotalsError {

    /** The invoice has no lines. */
    data object NoLines : TotalsError

    /** The line at [lineIndex] has a negative quantity. */
    data class NegativeQuantity(val lineIndex: Int) : TotalsError

    /** The line at [lineIndex] has a negative unit price. */
    data class NegativeUnitPrice(val lineIndex: Int) : TotalsError

    /**
     * The `codigoPorcentaje` of the line at [lineIndex] is not in [IvaRateTable]. The refused string is
     * deliberately not carried: it is free caller text, and this type is rendered into diagnostics.
     */
    data class UnknownIvaCode(val lineIndex: Int) : TotalsError

    /** The line at [lineIndex] is IVA diferenciado, which has no fixed rate to compute with. */
    data class IvaCodeNotComputable(val lineIndex: Int) : TotalsError
}

/**
 * Line and group arithmetic (SRI-03), mirroring the SRI's own free invoicer (D-21):
 *
 * - `quantity` and `unitPrice` are rounded to six decimals first, and `lineBase = round2(q x p)` is
 *   computed from the rounded values, so the SRI's recomputation from what it receives always agrees
 *   (D-23);
 * - each line's IVA is `round2(lineBase x rate)`;
 * - each `totalImpuesto`, one per `codigoPorcentaje`, is `round2(sum of lineBase at that code x rate)`,
 *   computed **independently** and never as the sum of the per-line IVA values, so the two may differ
 *   by a cent (see [groupIva]);
 * - `importeTotal` is the sum of the bases plus the sum of the group IVA plus the tip, which is always
 *   zero (D-22, D-25).
 *
 * All rounding is [RoundingMode.HALF_UP]. This object builds [TaxTotal] itself; the retired Phase 2
 * grouping helper, which summed per-line amounts (the opposite of D-21), no longer exists. Numbers
 * become strings only in `Money`, at emission.
 */
object InvoiceCalculator {

    private const val AMOUNT_SCALE = 2
    private const val PRECISE_SCALE = 6
    private const val IVA_TAX_CODE = "2"
    private const val HUNDRED_EXPONENT = 2

    /**
     * Computes the lines, the groups and the totals, or names the first line that cannot be computed.
     *
     * Refusals are decided before any arithmetic: an empty list, then each line in order, checking its
     * quantity sign, its unit price sign and its IVA code, and returning the first failure. The signs are
     * read from the values as entered, so a quantity that would round to zero is still refused when it
     * is negative. A negative lump-sum amount arrives here as a negative unit price.
     *
     * A zero quantity, a zero line total and a zero invoice total do not refuse (D-27): they are returned
     * as warnings beside the result, in line order and then the invoice-level one.
     *
     * Groups are ordered by `codigoPorcentaje` compared as an integer (0, 2, 4, 10), never as a string,
     * and are independent of the order of the lines; the lines keep the order they were given in.
     */
    fun calculate(lines: List<LineInput>): Outcome<Computed<InvoiceTotals>, TotalsError> {
        if (lines.isEmpty()) return Outcome.Err(TotalsError.NoLines)
        val rates = ArrayList<IvaRate>(lines.size)
        for ((index, line) in lines.withIndex()) {
            if (line.quantity.signum() < 0) return Outcome.Err(TotalsError.NegativeQuantity(index))
            if (line.unitPrice.signum() < 0) return Outcome.Err(TotalsError.NegativeUnitPrice(index))
            val rate = IvaRateTable.lookup(line.ivaRateCode)
                ?: return Outcome.Err(TotalsError.UnknownIvaCode(index))
            if (rate.kind == IvaRateKind.DIFFERENTIATED) return Outcome.Err(TotalsError.IvaCodeNotComputable(index))
            rates += rate
        }

        val calculated = lines.mapIndexed { index, line -> calculateLine(line, rates[index]) }

        val groups = calculated
            .groupBy { it.tax.rateCode }
            .toSortedMap(compareBy<String> { it.toInt() })
            .map { (code, members) ->
                val percent = checkNotNull(IvaRateTable.lookup(code)?.percent) { "rate vanished between passes" }
                val bases = members.map { it.base }
                TaxTotal(
                    taxCode = IVA_TAX_CODE,
                    rateCode = code,
                    taxableBase = sum(bases),
                    amount = groupIva(bases, percent),
                )
            }

        val totalWithoutTaxes = sum(calculated.map { it.base })
        val tip = BigDecimal.ZERO.setScale(AMOUNT_SCALE)
        val grandTotal = totalWithoutTaxes.add(sum(groups.map { it.amount })).add(tip)
        val totals = InvoiceTotals(
            lines = calculated,
            taxTotals = groups,
            totalWithoutTaxes = totalWithoutTaxes,
            tip = tip,
            grandTotal = grandTotal,
            lineIvaSum = sum(calculated.map { it.tax.amount }),
        )
        return Outcome.Ok(Computed(totals, warningsOf(totals)))
    }

    /** D-27: the non-blocking conditions, per line in order and then the invoice-level one. */
    private fun warningsOf(totals: InvoiceTotals): List<EngineWarning> {
        val warnings = ArrayList<EngineWarning>()
        for ((index, line) in totals.lines.withIndex()) {
            if (line.quantity.signum() == 0) {
                warnings += EngineWarning.ZeroQuantity(index)
            } else if (line.base.signum() == 0) {
                warnings += EngineWarning.ZeroLineTotal(index)
            }
        }
        if (totals.grandTotal.signum() == 0) warnings += EngineWarning.ZeroInvoiceTotal
        return warnings
    }

    private fun calculateLine(line: LineInput, rate: IvaRate): CalculatedLine {
        val percent = checkNotNull(rate.percent) { "checked by the caller" }
        val quantity = line.quantity.setScale(PRECISE_SCALE, RoundingMode.HALF_UP)
        val unitPrice = line.unitPrice.setScale(PRECISE_SCALE, RoundingMode.HALF_UP)
        val base = round2(quantity.multiply(unitPrice))
        val tax = LineTax(
            taxCode = IVA_TAX_CODE,
            rateCode = rate.code,
            rate = percent.setScale(AMOUNT_SCALE),
            taxableBase = base,
            amount = ivaOf(base, percent),
        )
        return CalculatedLine(quantity = quantity, unitPrice = unitPrice, base = base, tax = tax)
    }

    /**
     * The `totalImpuesto/valor` of one rate group: `round2(sum(bases) x percent / 100)`.
     *
     * This is the single place that decides how a group's IVA is obtained, and it deliberately does
     * not read the per-line IVA values (D-21). If Phase 6's owner-run test-environment session
     * (register item 7) shows the SRI expects the sum of the per-line values instead, this one function
     * is what changes; [InvoiceTotals.lineIvaSum] already exposes that other number.
     */
    private fun groupIva(bases: List<BigDecimal>, percent: BigDecimal): BigDecimal = ivaOf(sum(bases), percent)

    /** `round2(base x percent / 100)`, where [percent] is 15 for 15%. */
    private fun ivaOf(base: BigDecimal, percent: BigDecimal): BigDecimal =
        round2(base.multiply(percent).movePointLeft(HUNDRED_EXPONENT))

    private fun round2(value: BigDecimal): BigDecimal = value.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)

    private fun sum(values: List<BigDecimal>): BigDecimal =
        values.fold(BigDecimal.ZERO.setScale(AMOUNT_SCALE)) { total, value -> total.add(value) }
}
