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

import java.math.BigDecimal

/** What a `codigoPorcentaje` means for arithmetic. */
enum class IvaRateKind {
    /** A fixed percentage applies, possibly zero. */
    RATED,

    /** No objeto de impuesto: the full base is declared with a zero rate and a zero amount. */
    NOT_SUBJECT,

    /** Exento de IVA: the full base is declared with a zero rate and a zero amount. */
    EXEMPT,

    /** IVA diferenciado: the rate depends on the goods and the engine cannot know it. */
    DIFFERENTIATED,
}

/** One row of the catalogue: the SRI [code], its [percent] (null when there is no fixed rate) and its [kind]. */
data class IvaRate(val code: String, val percent: BigDecimal?, val kind: IvaRateKind)

/**
 * The IVA `codigoPorcentaje` catalogue (D-29): a plain code-to-rate table transcribed from Ficha
 * v2.34 Table 17 (p.30).
 *
 * **There are no effective dates.** D-29 asked for them, and the owner decided on 2026-10-28 that the
 * table carries none: the Ficha's table gives code and percentage only, and the app applies the rate
 * the code names. That decision supersedes D-29's "effective dates" clause, so this table has no date
 * fields of any kind. A rate change is an app update with a test, never a user edit.
 *
 * Percentages are the percentage itself (15 means 15%), constructed from strings.
 */
object IvaRateTable {

    /** Ficha v2.34 Table 17 (p.30): code 0, 0%. */
    private val ZERO = IvaRate("0", BigDecimal("0"), IvaRateKind.RATED)

    /** Ficha v2.34 Table 17 (p.30): code 2, 12%. */
    private val TWELVE = IvaRate("2", BigDecimal("12"), IvaRateKind.RATED)

    /** Ficha v2.34 Table 17 (p.30): code 3, 14%. */
    private val FOURTEEN = IvaRate("3", BigDecimal("14"), IvaRateKind.RATED)

    /** Ficha v2.34 Table 17 (p.30): code 4, 15%. */
    private val FIFTEEN = IvaRate("4", BigDecimal("15"), IvaRateKind.RATED)

    /** Ficha v2.34 Table 17 (p.30): code 5, 5%. */
    private val FIVE = IvaRate("5", BigDecimal("5"), IvaRateKind.RATED)

    /** Ficha v2.34 Table 17 (p.30): code 6, no objeto de impuesto. */
    private val NOT_SUBJECT = IvaRate("6", BigDecimal("0"), IvaRateKind.NOT_SUBJECT)

    /** Ficha v2.34 Table 17 (p.30): code 7, exento de IVA. */
    private val EXEMPT = IvaRate("7", BigDecimal("0"), IvaRateKind.EXEMPT)

    /** Ficha v2.34 Table 17 (p.30): code 8, IVA diferenciado, no fixed rate. */
    private val DIFFERENTIATED = IvaRate("8", null, IvaRateKind.DIFFERENTIATED)

    /** Ficha v2.34 Table 17 (p.30): code 10, 13%. */
    private val THIRTEEN = IvaRate("10", BigDecimal("13"), IvaRateKind.RATED)

    /** Every known code, in the table's order. */
    val entries: List<IvaRate> = listOf(ZERO, TWELVE, FOURTEEN, FIFTEEN, FIVE, NOT_SUBJECT, EXEMPT, DIFFERENTIATED, THIRTEEN)

    private val byCode: Map<String, IvaRate> = entries.associateBy { it.code }

    /** The row for [code], or `null` when the SRI catalogue as transcribed here does not know it. */
    fun lookup(code: String): IvaRate? = byCode[code]
}
