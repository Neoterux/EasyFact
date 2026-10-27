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
import java.time.LocalDate

/**
 * The mandatory skeleton of a `factura` at schema version 1.1.0: the minimum that the SRI schema
 * requires and that the signing path needs in order to be proven end to end.
 *
 * Type and property names are English; the SRI's Spanish element names appear only as strings in
 * [FacturaWriter], where they are protocol identifiers. The optional blocks of the schema are
 * added to this model without changing the seam between the writer and the signer.
 *
 * Money is [BigDecimal] and is given its scale at serialization, never before: a pre-formatted
 * string would let a `Double`-derived value in through the back door.
 */
data class Factura(
    val taxInfo: TaxInfo,
    val invoiceInfo: InvoiceInfo,
    val lines: List<InvoiceLine>,
    val additionalFields: List<AdditionalField> = emptyList(),
)

/** `infoTributaria`: who issues the document and which document it is. */
data class TaxInfo(
    /** `ambiente`: `1` test, `2` production. */
    val environmentCode: String,
    /** `tipoEmision`: always `1` under the offline scheme. */
    val emissionTypeCode: String,
    /** `razonSocial` */
    val legalName: String,
    /** `nombreComercial`; omitted from the document when `null`. */
    val tradeName: String?,
    val ruc: String,
    /** `claveAcceso`: the 49-digit key. */
    val accessKey: String,
    /** `codDoc` */
    val documentTypeCode: String,
    /** `estab` */
    val establishmentCode: String,
    /** `ptoEmi` */
    val emissionPointCode: String,
    /** `secuencial`: nine digits. */
    val sequential: String,
    /** `dirMatriz` */
    val headOfficeAddress: String,
)

/** `infoFactura`: the buyer, the totals and the payments. */
data class InvoiceInfo(
    /** `fechaEmision`, written as `dd/MM/yyyy`. */
    val issueDate: LocalDate,
    /** `dirEstablecimiento` */
    val establishmentAddress: String,
    /** `obligadoContabilidad`, written as `SI` or `NO`. */
    val accountingRequired: Boolean,
    /** `tipoIdentificacionComprador` */
    val buyerIdentificationTypeCode: String,
    /** `razonSocialComprador` */
    val buyerLegalName: String,
    /** `identificacionComprador` */
    val buyerIdentification: String,
    /** `totalSinImpuestos` */
    val totalWithoutTaxes: BigDecimal,
    /** `totalDescuento` */
    val totalDiscount: BigDecimal,
    /** `totalConImpuestos` */
    val taxTotals: List<TaxTotal>,
    /** `propina` */
    val tip: BigDecimal,
    /** `importeTotal` */
    val grandTotal: BigDecimal,
    /** `moneda` */
    val currency: String,
    /** `pagos` */
    val payments: List<Payment>,
)

/** One `totalImpuesto` of `totalConImpuestos`. */
data class TaxTotal(
    /** `codigo`: `2` is IVA. */
    val taxCode: String,
    /** `codigoPorcentaje` */
    val rateCode: String,
    /** `baseImponible` */
    val taxableBase: BigDecimal,
    /** `valor` */
    val amount: BigDecimal,
)

/** One `pago`. */
data class Payment(
    /** `formaPago` */
    val methodCode: String,
    /** `total` */
    val total: BigDecimal,
)

/** One `detalle`. */
data class InvoiceLine(
    /** `codigoPrincipal` */
    val mainCode: String,
    /** `descripcion` */
    val description: String,
    /** `cantidad`, written with six decimals. */
    val quantity: BigDecimal,
    /** `precioUnitario`, written with six decimals. */
    val unitPrice: BigDecimal,
    /** `descuento` */
    val discount: BigDecimal,
    /** `precioTotalSinImpuesto` */
    val totalWithoutTaxes: BigDecimal,
    /** `impuestos` */
    val taxes: List<LineTax>,
)

/** One `impuesto` of a line. */
data class LineTax(
    /** `codigo` */
    val taxCode: String,
    /** `codigoPorcentaje` */
    val rateCode: String,
    /** `tarifa` */
    val rate: BigDecimal,
    /** `baseImponible` */
    val taxableBase: BigDecimal,
    /** `valor` */
    val amount: BigDecimal,
)

/** One `campoAdicional` of `infoAdicional`. */
data class AdditionalField(val name: String, val value: String)
