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
 * A `factura` at schema version 1.1.0: the `infoTributaria` / `infoFactura` / `detalles` spine
 * and the document-level optional blocks that follow it.
 *
 * Type and property names are English; the SRI's Spanish element names appear only as string
 * constants in [FacturaWriter], where they are protocol identifiers (AGENTS.md section 2.1).
 * Each property's KDoc names the element it feeds, so the mapping stays auditable.
 *
 * Money is [BigDecimal] and is given its scale at serialization by [Money], never before: a
 * pre-formatted string, or a `Double`, would let a binary-floating-point rounding error into a
 * legally binding total through the back door.
 *
 * **A `null` or empty optional is absent, not empty.** The writer omits the element entirely
 * rather than emitting it with no content: the schema's `minLength` and pattern facets reject
 * empty content, and an empty element is a silent data loss at the domain boundary.
 *
 * Elements deliberately left unmodelled are listed in the plan 02-05 summary. Where this model
 * and the published XSD disagree, the XSD wins: plan 02-09 vendors it and fails the build on a
 * mismatch.
 */
data class Factura(
    val taxInfo: TaxInfo,
    val invoiceInfo: InvoiceInfo,
    /** `detalles`; at least one `detalle`. */
    val lines: List<InvoiceLine>,
    /** `reembolsos`; the element is absent when this is empty. */
    val refunds: List<Refund> = emptyList(),
    /** `retenciones`; the element is absent when this is empty. */
    val withholdings: List<Withholding> = emptyList(),
    /** `tipoNegociable` / `correo`; the block is absent when this is `null`. */
    val negotiableEmail: String? = null,
    /** `maquinaFiscal`; the block is absent when this is `null`. */
    val fiscalMachine: FiscalMachine? = null,
    /** `infoAdicional`; the element is absent when this is empty, and capped at fifteen entries. */
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
    /** `nombreComercial`; omitted when `null`. */
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
    /** `agenteRetencion`: the withholding-agent resolution number; omitted when `null`. */
    val withholdingAgentResolution: String? = null,
    /** `contribuyenteRimpe`: the RIMPE regime legend of Annex 22; omitted when `null`. */
    val rimpeRegimeLegend: String? = null,
)

/**
 * `infoFactura`: the buyer, the totals and the payments.
 *
 * Properties are declared in the schema's `xsd:sequence` order, which is also the order the
 * writer emits them in. Every optional one carries a default, so a caller that needs only the
 * mandatory document names only the mandatory fields — and every call site uses named arguments,
 * which is what lets the declaration keep schema order rather than "defaults last" order.
 */
data class InvoiceInfo(
    /** `fechaEmision`, written as `dd/MM/yyyy`. */
    val issueDate: LocalDate,
    /** `dirEstablecimiento` */
    val establishmentAddress: String,
    /** `contribuyenteEspecial`: the resolution number; omitted when `null`. */
    val specialTaxpayerNumber: String? = null,
    /** `obligadoContabilidad`, written as `SI` or `NO`. */
    val accountingRequired: Boolean,
    /** `comercioExterior` and the Incoterm, port and country elements; omitted when `null`. */
    val exportInfo: ExportInfo? = null,
    /** `tipoIdentificacionComprador` */
    val buyerIdentificationTypeCode: String,
    /** `guiaRemision`; omitted when `null`. */
    val dispatchNote: String? = null,
    /** `razonSocialComprador` */
    val buyerLegalName: String,
    /** `identificacionComprador` */
    val buyerIdentification: String,
    /** `direccionComprador`; omitted when `null`. */
    val buyerAddress: String? = null,
    /** `totalSinImpuestos` */
    val totalWithoutTaxes: BigDecimal,
    /** `totalSubsidio`; omitted when `null`. */
    val totalSubsidy: BigDecimal? = null,
    /** `incoTermTotalSinImpuestos`; omitted when `null`. */
    val incoTermTotalWithoutTaxes: String? = null,
    /** `totalDescuento`: emitted even when zero, because the schema requires it. */
    val totalDiscount: BigDecimal,
    /** `codDocReembolso` and the three reimbursement totals; all four omitted when `null`. */
    val refundTotals: RefundTotals? = null,
    /** `totalConImpuestos` */
    val taxTotals: List<TaxTotal>,
    /** `compensaciones`; the element is absent when this is empty. */
    val compensations: List<Compensation> = emptyList(),
    /** `propina`: emitted even when zero, because the schema requires it. */
    val tip: BigDecimal,
    /** `fleteInternacional`; omitted when `null`. */
    val internationalFreight: BigDecimal? = null,
    /** `seguroInternacional`; omitted when `null`. */
    val internationalInsurance: BigDecimal? = null,
    /** `gastosAduaneros`; omitted when `null`. */
    val customsCharges: BigDecimal? = null,
    /** `gastosTransporteOtros`; omitted when `null`. */
    val otherTransportCharges: BigDecimal? = null,
    /** `importeTotal` */
    val grandTotal: BigDecimal,
    /** `moneda` */
    val currency: String,
    /** `placa`; omitted when `null`. */
    val licensePlate: String? = null,
    /** `pagos`; mandatory, and must carry at least one `pago`. */
    val payments: List<Payment>,
    /** `valorRetIva`; omitted when `null`. */
    val ivaWithheldValue: BigDecimal? = null,
    /** `valorRetRenta`; omitted when `null`. */
    val incomeTaxWithheldValue: BigDecimal? = null,
)

/** One `detalle`. */
data class InvoiceLine(
    /** `codigoPrincipal`; omitted when `null`. */
    val mainCode: String? = null,
    /** `codigoAuxiliar`; omitted when `null`. */
    val auxiliaryCode: String? = null,
    /** `descripcion` */
    val description: String,
    /** `cantidad`, written with six decimals. */
    val quantity: BigDecimal,
    /** `precioUnitario`, written with six decimals. */
    val unitPrice: BigDecimal,
    /** `precioSinSubsidio`; omitted when `null`. */
    val priceWithoutSubsidy: BigDecimal? = null,
    /** `descuento` */
    val discount: BigDecimal,
    /** `precioTotalSinImpuesto` */
    val totalWithoutTaxes: BigDecimal,
    /** `detallesAdicionales`; the element is absent when this is empty. */
    val additionalDetails: List<LineDetailField> = emptyList(),
    /** `impuestos` */
    val taxes: List<LineTax>,
)
