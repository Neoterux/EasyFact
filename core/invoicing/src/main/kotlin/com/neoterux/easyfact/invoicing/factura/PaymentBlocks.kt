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
 * The payment and refund blocks of a `factura`: `pagos` / `pago`, and the document-level
 * `reembolsos` with its `reembolsoDetalle` lines.
 *
 * Type and property names are English (AGENTS.md section 2.1); the Spanish element names live in
 * [FacturaWriter] as string constants and nowhere else. Optional values are declared last so they
 * can carry defaults; emission order is the schema's `xsd:sequence` and belongs to the writer.
 */

/** One `pago`. `pagos` is mandatory and must carry at least one of these. */
data class Payment(
    /** `formaPago` */
    val methodCode: String,
    /** `total` */
    val total: BigDecimal,
    /** `plazo`; omitted when `null`. */
    val term: BigDecimal? = null,
    /** `unidadTiempo`; omitted when `null`. */
    val termUnit: String? = null,
)

/**
 * One `reembolsoDetalle`: a document the issuer is passing through as a reimbursement, with the
 * provider it came from and the tax it carried.
 */
data class Refund(
    /** `tipoIdentificacionProveedorReembolso` */
    val providerIdentificationTypeCode: String,
    /** `identificacionProveedorReembolso` */
    val providerIdentification: String,
    /** `codPaisPagoProveedorReembolso` */
    val providerPaymentCountryCode: String,
    /** `tipoProveedorReembolso` */
    val providerTypeCode: String,
    /** `codDocReembolso` */
    val documentTypeCode: String,
    /** `estabDocReembolso` */
    val establishmentCode: String,
    /** `ptoEmiDocReembolso` */
    val emissionPointCode: String,
    /** `secuencialDocReembolso` */
    val sequential: String,
    /** `fechaEmisionDocReembolso`, written as `dd/MM/yyyy`. */
    val issueDate: LocalDate,
    /** `numeroautorizacionDocReemb` */
    val authorizationNumber: String,
    /**
     * `detalleImpuestos` / `detalleImpuesto`; at least one. An empty list is refused with
     * [FacturaError.EmptyCollection].
     */
    val taxes: List<RefundTax>,
    /** `compensacionesReembolso`; the element is absent when this is empty. */
    val compensations: List<Compensation> = emptyList(),
) {
    /** Renders no field: the provider's identification is third-party data (NFR-04). */
    override fun toString(): String = "Refund(redacted)"
}

/** One `detalleImpuesto` of a [Refund]. */
data class RefundTax(
    /** `codigo` */
    val taxCode: String,
    /** `codigoPorcentaje` */
    val rateCode: String,
    /** `tarifa` */
    val rate: BigDecimal,
    /** `baseImponibleReembolso` */
    val taxableBase: BigDecimal,
    /** `impuestoReembolso` */
    val amount: BigDecimal,
)

/**
 * The four `infoFactura` totals that summarise a `reembolsos` block.
 *
 * They are one value because the schema treats them as a set: a document either passes
 * reimbursements through, and then declares all four, or it does not and omits all four.
 */
data class RefundTotals(
    /** `codDocReembolso` */
    val documentTypeCode: String,
    /** `totalComprobantesReembolso` */
    val documentCount: BigDecimal,
    /** `totalBaseImponibleReembolso` */
    val taxableBase: BigDecimal,
    /** `totalImpuestoReembolso` */
    val taxAmount: BigDecimal,
)
