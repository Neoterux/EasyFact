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
 * [taxableBase] and [amount] are computed by `com.neoterux.easyfact.invoicing.totals.InvoiceCalculator`
 * under D-21: the group `valor` is round2 of the group base times the rate, computed independently of
 * the per-line `valor`s, so the sum of the per-line values may differ from the group value by a cent,
 * as in the SRI's own free invoicer. This type only carries what the calculator produced; [Money] still
 * applies scale at emission.
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
    /** `tarifa`; omitted when `null`. */
    val rate: BigDecimal? = null,
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
