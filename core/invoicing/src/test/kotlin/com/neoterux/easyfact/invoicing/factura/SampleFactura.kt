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

import com.neoterux.easyfact.invoicing.accesskey.AccessKey
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.math.BigDecimal
import java.time.LocalDate

/**
 * A synthetic factura for tests. Every value is a placeholder; the free-text values are chosen so
 * that each escaping rule is exercised: `&`, `<`, `>`, a double quote and non-ASCII letters.
 */
object SampleFactura {

    /** A valid 49-digit key, generated rather than written down so that no identifier-shaped literal sits in the source. */
    private val accessKey: AccessKey = (
        AccessKey.of(
            issueDate = LocalDate.of(2026, 10, 27),
            documentTypeCode = "01",
            issuerRuc = SyntheticIdentifiers.RUC,
            environmentCode = "1",
            establishment = "001",
            emissionPoint = "003",
            sequential = 990064L,
            numericCode = "12345678",
            emissionTypeCode = "1",
        ) as Outcome.Ok
        ).value

    fun factura(): Factura = Factura(
        taxInfo = TaxInfo(
            environmentCode = "1",
            emissionTypeCode = "1",
            legalName = "Test Emisor S.A. & Cia",
            tradeName = "Test Comercial",
            ruc = SyntheticIdentifiers.RUC,
            accessKey = accessKey,
            documentTypeCode = "01",
            establishmentCode = "001",
            emissionPointCode = "003",
            sequential = "000990064",
            headOfficeAddress = "Calle Falsa 123 <N>",
        ),
        invoiceInfo = InvoiceInfo(
            issueDate = LocalDate.of(2026, 10, 27),
            establishmentAddress = "Calle Falsa 456",
            accountingRequired = false,
            buyerIdentificationTypeCode = "05",
            buyerLegalName = "Cliente de prueba Ñandú",
            buyerIdentification = SyntheticIdentifiers.RUC_BASE,
            totalWithoutTaxes = BigDecimal("100"),
            totalDiscount = BigDecimal("0"),
            taxTotals = listOf(TaxTotal("2", "0", BigDecimal("100"), BigDecimal("0"))),
            tip = BigDecimal("0"),
            grandTotal = BigDecimal("100"),
            currency = "DOLAR",
            payments = listOf(Payment("01", BigDecimal("100"))),
        ),
        lines = listOf(
            InvoiceLine(
                mainCode = "001",
                description = "Servicio de prueba \"ABC\" > 1",
                quantity = BigDecimal("1"),
                unitPrice = BigDecimal("100"),
                discount = BigDecimal("0"),
                totalWithoutTaxes = BigDecimal("100"),
                taxes = listOf(LineTax("2", "0", BigDecimal("0"), BigDecimal("100"), BigDecimal("0"))),
            ),
        ),
        additionalFields = listOf(AdditionalField("Email", SyntheticIdentifiers.BILLING_EMAIL)),
    )

    /** The fixture must always be valid, so a failure here is a bug in the fixture and throws. */
    /**
     * A factura that populates every optional block the model covers, so that a test can assert
     * the full `xsd:sequence` rather than only the mandatory skeleton.
     *
     * Values stay synthetic and structurally plausible; nothing here is arithmetically meaningful
     * beyond what the ordering and presence assertions need.
     */
    fun fullyPopulated(): Factura {
        val base = factura()
        return base.copy(
            taxInfo = base.taxInfo.copy(
                withholdingAgentResolution = "001",
                rimpeRegimeLegend = "CONTRIBUYENTE RÉGIMEN RIMPE",
            ),
            invoiceInfo = base.invoiceInfo.copy(
                specialTaxpayerNumber = "12345",
                exportInfo = ExportInfo(
                    code = "EXPORTADOR",
                    incoTerm = "A",
                    incoTermPlace = "Test Port",
                    countryOfOrigin = "593",
                    portOfLoading = "Test Loading",
                    portOfDestination = "Test Destination",
                    countryOfDestination = "840",
                    countryOfAcquisition = "840",
                ),
                dispatchNote = "001-003-000000001",
                buyerAddress = "Calle Falsa 789",
                totalSubsidy = BigDecimal("0"),
                incoTermTotalWithoutTaxes = "A",
                refundTotals = RefundTotals(
                    documentTypeCode = "01",
                    documentCount = BigDecimal("1"),
                    taxableBase = BigDecimal("1"),
                    taxAmount = BigDecimal("0"),
                ),
                taxTotals = listOf(
                    TaxTotal(
                        taxCode = "2",
                        rateCode = "0",
                        taxableBase = BigDecimal("100"),
                        amount = BigDecimal("0"),
                        additionalDiscount = BigDecimal("0"),
                        ivaRefundValue = BigDecimal("0"),
                    ),
                ),
                compensations = listOf(Compensation("1", BigDecimal("0"), BigDecimal("0"))),
                internationalFreight = BigDecimal("0"),
                internationalInsurance = BigDecimal("0"),
                customsCharges = BigDecimal("0"),
                otherTransportCharges = BigDecimal("0"),
                licensePlate = "TEST0001",
                payments = listOf(
                    Payment("01", BigDecimal("100"), term = BigDecimal("30"), termUnit = "dias"),
                ),
                ivaWithheldValue = BigDecimal("0"),
                incomeTaxWithheldValue = BigDecimal("0"),
            ),
            lines = listOf(
                base.lines.first().copy(
                    auxiliaryCode = "AUX1",
                    priceWithoutSubsidy = BigDecimal("100"),
                    additionalDetails = listOf(LineDetailField("Periodo", "2026-10")),
                ),
            ),
            refunds = listOf(
                Refund(
                    providerIdentificationTypeCode = "04",
                    providerIdentification = SyntheticIdentifiers.RUC,
                    providerPaymentCountryCode = "593",
                    providerTypeCode = "01",
                    documentTypeCode = "01",
                    establishmentCode = "001",
                    emissionPointCode = "003",
                    sequential = "000000001",
                    issueDate = LocalDate.of(2026, 10, 27),
                    authorizationNumber = accessKey.digits,
                    taxes = listOf(
                        RefundTax("2", "0", BigDecimal("0"), BigDecimal("1"), BigDecimal("0")),
                    ),
                ),
            ),
            withholdings = listOf(Withholding("4", "327", BigDecimal("0"), BigDecimal("0"))),
            negotiableEmail = SyntheticIdentifiers.BILLING_EMAIL,
        )
    }

    /**
     * [fullyPopulated] plus the four optional elements it leaves out: `maquinaFiscal`,
     * `unidadMedida`, the `totalImpuesto` `tarifa` and `compensacionesReembolso`. It carries every
     * element and attribute of the vendored schema.
     *
     * [fullyPopulated] is left as it is because its bytes are pinned.
     */
    fun everyElement(): Factura {
        val f = fullyPopulated()
        return f.copy(
            fiscalMachine = FiscalMachine(brand = "Test Marca", model = "Test Modelo", serial = "TEST-SERIE-0001"),
            lines = listOf(f.lines.single().copy(unitOfMeasure = "unidad")),
            invoiceInfo = f.invoiceInfo.copy(
                taxTotals = listOf(f.invoiceInfo.taxTotals.single().copy(rate = BigDecimal("0"))),
            ),
            refunds = listOf(
                f.refunds.single().copy(
                    compensations = listOf(Compensation("1", BigDecimal("0"), BigDecimal("0"))),
                ),
            ),
        )
    }

    fun document(): CanonicalDocument = write(factura())

    /** Unwraps the writer's outcome; the message never carries a document value. */
    fun write(factura: Factura): CanonicalDocument = when (val outcome = FacturaWriter.write(factura)) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw IllegalArgumentException("the sample factura is not writable: ${outcome.error}")
    }
}
