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
import java.math.RoundingMode

/**
 * The one place a number becomes a string in a legally binding document.
 *
 * Every function takes a [BigDecimal] and nothing else. There is deliberately no entry point for
 * a `Double`, a `Float` or a caller-formatted `String`, and none may be added: a `Double`-derived
 * value carries a binary-floating-point rounding error that no later step can detect, and a
 * pre-formatted string is the same hole with a different shape, because the caller's formatter is
 * then the thing that decides a total. AGENTS.md section 5 states the rule; this object is where
 * it is enforced, so the rest of the engine can hold full precision and let scale happen once.
 *
 * Scale is applied here, at the serialization boundary, and nowhere earlier. Rounding is
 * [RoundingMode.HALF_UP], which is the SRI's rule and rounds a half away from zero in both
 * directions.
 *
 * Output is always `toPlainString`, never `toString`: a [BigDecimal] whose scale is negative or
 * whose unscaled value is small enough renders in scientific notation through `toString`, and the
 * SRI's pattern facets reject an exponent. `toPlainString` is also locale-independent by
 * construction — it emits `.` as the separator and no grouping whatever the default locale is —
 * which is the [java.util.Locale.ROOT] behaviour the document requires, obtained without a
 * formatter that could be reconfigured.
 *
 * One function per field class rather than one per scale: a future scale change is then a
 * one-line edit against the field it concerns, and the call site reads as the document does.
 */
object Money {

    /** `totalSinImpuestos`, `importeTotal`, `baseImponible`, `valor`, `descuento`, `total`, ... */
    private const val AMOUNT_SCALE = 2

    /** `cantidad`. */
    private const val QUANTITY_SCALE = 6

    /** `precioUnitario`, `precioSinSubsidio`. */
    private const val UNIT_PRICE_SCALE = 6

    /** `tarifa`: a percentage, not an amount, but written with the same two decimals. */
    private const val RATE_SCALE = 2

    /** A monetary amount, two decimals. */
    fun amount(value: BigDecimal): String = render(value, AMOUNT_SCALE)

    /** A `cantidad`, six decimals under schema 1.1.0. */
    fun quantity(value: BigDecimal): String = render(value, QUANTITY_SCALE)

    /**
     * A unit price, six decimals under schema 1.1.0.
     *
     * Six and not two is deliberate: rounding a unit price before it is multiplied is what makes
     * `cantidad` times `precioUnitario` disagree with `precioTotalSinImpuesto`, and the SRI
     * rejects that disagreement. Plan 02-09 settles the scale against the vendored XSD.
     */
    fun unitPrice(value: BigDecimal): String = render(value, UNIT_PRICE_SCALE)

    /** A `tarifa` percentage, two decimals. */
    fun rate(value: BigDecimal): String = render(value, RATE_SCALE)

    private fun render(value: BigDecimal, scale: Int): String =
        value.setScale(scale, RoundingMode.HALF_UP).toPlainString()
}
