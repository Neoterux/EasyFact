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

package com.neoterux.easyfact.invoicing.mapping

import com.neoterux.easyfact.invoicing.factura.AdditionalField
import com.neoterux.easyfact.invoicing.factura.Factura
import com.neoterux.easyfact.invoicing.factura.FacturaError
import com.neoterux.easyfact.invoicing.factura.FacturaWriter
import com.neoterux.easyfact.invoicing.factura.InvoiceInfo
import com.neoterux.easyfact.invoicing.factura.InvoiceLine
import com.neoterux.easyfact.invoicing.factura.Payment
import com.neoterux.easyfact.invoicing.factura.TaxInfo
import com.neoterux.easyfact.invoicing.result.Computed
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.totals.InvoiceCalculator
import com.neoterux.easyfact.invoicing.totals.InvoiceTotals
import com.neoterux.easyfact.invoicing.totals.LineInput
import com.neoterux.easyfact.invoicing.totals.TotalsError
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import java.math.BigDecimal

/** What [InvoiceMapper.map] produces: the model, the canonical bytes written from it and the arithmetic behind both. */
data class MappedInvoice(
    val factura: Factura,
    val document: CanonicalDocument,
    val totals: InvoiceTotals,
)

/**
 * Why an [InvoiceRequest] could not be mapped. Plan 03-08 adds the sanitiser, field-limit and
 * `infoAdicional` variants.
 */
sealed interface MappingError {

    /** The arithmetic refused the lines. */
    data class Totals(val error: TotalsError) : MappingError

    /** The writer refused the mapped [Factura]. */
    data class Writer(val error: FacturaError) : MappingError
}

/**
 * Maps engine input onto Phase 2's [Factura] and [FacturaWriter] (SRI-01).
 *
 * The mapping is plain and total over what it supports (D-14): `infoTributaria`, `infoFactura` with
 * its totals, `totalConImpuestos`, `pagos`, `propina` 0.00 and `totalDescuento` 0.00, the `detalles`,
 * and `infoAdicional`. It never sets refunds, withholdings, export fields, subsidy, compensations,
 * negotiable email or fiscal machine, although the writer supports them.
 *
 * **The access key is an input.** [InvoiceRequest.accessKey] was derived once at allocation and
 * persisted; this mapper never derives or recomputes one (D-11). The writer additionally refuses a key
 * that disagrees with the document it is written into.
 *
 * Field names such as `Email` are document content, not source identifiers, and so are the permitted
 * exception to the English-only rule (AGENTS.md section 2.1), exactly like the SRI's element names.
 */
object InvoiceMapper {

    private const val EMISSION_TYPE_NORMAL = "1"
    private const val DOCUMENT_TYPE_FACTURA = "01"
    private const val CURRENCY = "DOLAR"
    private const val SEQUENTIAL_DIGITS = 9
    private const val EMAIL_FIELD = "Email"
    private val ZERO_AMOUNT: BigDecimal = BigDecimal.ZERO.setScale(2)

    /** Maps [request], or says whether the arithmetic or the writer refused it. */
    fun map(request: InvoiceRequest): Outcome<Computed<MappedInvoice>, MappingError> {
        val computed = when (
            val outcome = InvoiceCalculator.calculate(
                request.lines.map { LineInput(it.quantity, it.unitPrice, it.ivaRateCode) },
            )
        ) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return Outcome.Err(MappingError.Totals(outcome.error))
        }
        val totals = computed.value

        val factura = Factura(
            taxInfo = taxInfo(request),
            invoiceInfo = invoiceInfo(request, totals),
            lines = request.lines.mapIndexed { index, line ->
                val calculated = totals.lines[index]
                InvoiceLine(
                    mainCode = line.code,
                    description = line.description,
                    quantity = calculated.quantity,
                    unitPrice = calculated.unitPrice,
                    discount = ZERO_AMOUNT,
                    totalWithoutTaxes = calculated.base,
                    taxes = listOf(calculated.tax),
                )
            },
            additionalFields = listOf(AdditionalField(EMAIL_FIELD, request.buyer.billingEmail)),
        )

        return when (val written = FacturaWriter.write(factura)) {
            is Outcome.Ok -> Outcome.Ok(Computed(MappedInvoice(factura, written.value, totals), computed.warnings))
            is Outcome.Err -> Outcome.Err(MappingError.Writer(written.error))
        }
    }

    private fun taxInfo(request: InvoiceRequest): TaxInfo = TaxInfo(
        environmentCode = request.environment.code,
        emissionTypeCode = EMISSION_TYPE_NORMAL,
        legalName = request.issuer.legalName,
        tradeName = request.issuer.tradeName,
        ruc = request.issuer.ruc,
        accessKey = request.accessKey,
        documentTypeCode = DOCUMENT_TYPE_FACTURA,
        establishmentCode = request.issuer.establishment,
        emissionPointCode = request.issuer.emissionPoint,
        sequential = request.sequential.toString().padStart(SEQUENTIAL_DIGITS, '0'),
        headOfficeAddress = request.issuer.headOfficeAddress,
    )

    private fun invoiceInfo(request: InvoiceRequest, totals: InvoiceTotals): InvoiceInfo = InvoiceInfo(
        issueDate = request.issueDate,
        // D-15: a single establishment, so the establishment address is the head-office address.
        establishmentAddress = request.issuer.headOfficeAddress,
        specialTaxpayerNumber = request.issuer.specialTaxpayerNumber,
        accountingRequired = request.issuer.accountingRequired,
        buyerIdentificationTypeCode = request.buyer.idType.code,
        buyerLegalName = request.buyer.legalName,
        buyerIdentification = request.buyer.identification,
        totalWithoutTaxes = totals.totalWithoutTaxes,
        // D-24: no discount in v1, written as 0.00 although the input has no field for it.
        totalDiscount = ZERO_AMOUNT,
        taxTotals = totals.taxTotals,
        // D-25: no tip in v1.
        tip = totals.tip,
        grandTotal = totals.grandTotal,
        currency = CURRENCY,
        payments = listOf(
            Payment(
                methodCode = request.payment.method.code,
                total = totals.grandTotal,
                term = request.payment.creditTerm?.term,
                termUnit = request.payment.creditTerm?.unit,
            ),
        ),
    )
}
