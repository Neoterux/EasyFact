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

/**
 * The tax-bearing blocks of a `factura`: the per-line `impuestos`, the document's
 * `totalConImpuestos`, the `compensaciones` block, the `retenciones` block and the export
 * declaration that `comercioExterior` opens.
 *
 * Type and property names are English (AGENTS.md section 2.1). The SRI's Spanish element names
 * live in [FacturaWriter] as string constants and nowhere else, so the mapping to the
 * specification is auditable in one place; each property's KDoc names the element it feeds.
 *
 * Declaration order is **not** emission order where an optional field would otherwise have to sit
 * in the middle of a constructor: optional values are declared last so they can carry defaults,
 * and the schema's `xsd:sequence` is enforced by the writer, which is also where it is asserted.
 */

/**
 * One `totalImpuesto` of `totalConImpuestos`.
 *
 * Both amounts are held at whatever precision the caller computed; scale is applied once, at
 * emission, by [Money]. See [TaxGrouping] for why that is what makes per-line and group-base
 * rounding agree.
 */
data class TaxTotal(
    /** `codigo`: `2` is IVA. */
    val taxCode: String,
    /** `codigoPorcentaje`; read the catalogue from the specification, never hardcode a rate. */
    val rateCode: String,
    /** `baseImponible` */
    val taxableBase: BigDecimal,
    /** `valor` */
    val amount: BigDecimal,
    /** `descuentoAdicional`; omitted when `null`. */
    val additionalDiscount: BigDecimal? = null,
    /** `valorDevolucionIva`; omitted when `null`. */
    val ivaRefundValue: BigDecimal? = null,
)

/** One `impuesto` of a `detalle`. */
data class LineTax(
    /** `codigo` */
    val taxCode: String,
    /** `codigoPorcentaje` */
    val rateCode: String,
    /** `tarifa`: the percentage, not the amount. */
    val rate: BigDecimal,
    /** `baseImponible` */
    val taxableBase: BigDecimal,
    /** `valor` */
    val amount: BigDecimal,
)

/** One `compensacion` of `compensaciones`. */
data class Compensation(
    /** `codigo` */
    val code: String,
    /** `tarifa` */
    val rate: BigDecimal,
    /** `valor` */
    val amount: BigDecimal,
)

/** One `retencion` of the document-level `retenciones` block. */
data class Withholding(
    /** `codigo` */
    val code: String,
    /** `codigoPorcentaje` */
    val rateCode: String,
    /** `tarifa` */
    val rate: BigDecimal,
    /** `valor` */
    val amount: BigDecimal,
)

/**
 * The export declaration: `comercioExterior` and the Incoterm, port and country elements that
 * follow it in `infoFactura`.
 *
 * Grouping them into one optional value is what makes "emitted when populated, absent otherwise"
 * a property of the type rather than of nine independent null checks.
 */
data class ExportInfo(
    /** `comercioExterior` */
    val code: String,
    /** `incoTermFactura` */
    val incoTerm: String? = null,
    /** `lugarIncoTerm` */
    val incoTermPlace: String? = null,
    /** `paisOrigen` */
    val countryOfOrigin: String? = null,
    /** `puertoEmbarque` */
    val portOfLoading: String? = null,
    /** `puertoDestino` */
    val portOfDestination: String? = null,
    /** `paisDestino` */
    val countryOfDestination: String? = null,
    /** `paisAdquisicion` */
    val countryOfAcquisition: String? = null,
)

/**
 * Collapses the per-line `impuestos` of a document into its `totalConImpuestos`.
 *
 * **Why this is arranged the way it is.** Research flagged an unanswered SRI question: whether
 * IVA is expected to be rounded per line and then summed, or computed once on the group base.
 * Picking one and being wrong is a rejection, and the specification does not say. So the two
 * forms are made *identical by construction* rather than chosen between: the line bases and the
 * line amounts are summed at full precision and scale is applied exactly once, at emission, by
 * [Money]. Multiplication distributes over addition exactly in [BigDecimal], so
 * `rate x sum(base)` and `sum(rate x base)` are the same number before any rounding happens, and
 * therefore render as the same string. The only way the two forms can disagree is if a value is
 * rounded per line *before* being summed, which is precisely what this never does.
 *
 * A builder where both readings agree cannot be wrong either way. Phase 6's probe will confirm
 * which form the SRI expects; nothing here has to change when it does.
 *
 * Group identity is the pair of tax code and percentage code, and group order is a sort on that
 * pair rather than encounter order, so two documents with the same content always produce the
 * same bytes — which matters, because the bytes are what gets signed.
 *
 * Totals *arithmetic* — deciding what a line's base and amount are — stays in Phase 3 (D-08).
 * This is grouping and rendering only: it sums what the caller computed.
 */
object TaxGrouping {

    /** The `totalConImpuestos` entries for [lines], one per distinct tax-code and rate-code pair. */
    fun totals(lines: List<InvoiceLine>): List<TaxTotal> = TODO("02-05 Task 3")
}
