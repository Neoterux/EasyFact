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

import com.neoterux.easyfact.invoicing.accesskey.AccessKey
import com.neoterux.easyfact.invoicing.accesskey.KeyDerivation
import com.neoterux.easyfact.invoicing.result.Computed
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.totals.TotalsError
import com.neoterux.easyfact.invoicing.verify.XsdGate
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Phase 3 tracer: one synthetic line travels from [InvoiceRequest] through the calculator and
 * [InvoiceMapper] to `FacturaWriter`, and the canonical octets pass the unmodified XSD gate (SC1).
 */
class TracerEndToEndTest {

    private val issueDate: LocalDate = LocalDate.of(2026, 10, 27)
    private val headOfficeAddress = "Calle Falsa 123"

    private fun key(): AccessKey = when (
        val outcome = KeyDerivation.accessKey(
            issuerRuc = SyntheticIdentifiers.RUC,
            environmentCode = Environment.TEST.code,
            establishment = "001",
            emissionPoint = "002",
            sequential = 123L,
            issueDate = issueDate,
        )
    ) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw AssertionError("expected a key, got ${outcome.error}")
    }

    private fun request(ivaRateCode: String = "4"): InvoiceRequest = InvoiceRequest(
        issuer = IssuerProfile(
            ruc = SyntheticIdentifiers.RUC,
            legalName = "Test Emisor S.A.",
            tradeName = null,
            headOfficeAddress = headOfficeAddress,
            establishment = "001",
            emissionPoint = "002",
            accountingRequired = false,
        ),
        buyer = Buyer(
            idType = BuyerIdType.CEDULA,
            identification = SyntheticIdentifiers.RUC_BASE,
            legalName = SyntheticIdentifiers.CUSTOMER_NAME,
            billingEmail = SyntheticIdentifiers.BILLING_EMAIL,
        ),
        lines = listOf(
            ResolvedLine(
                code = "SRV-01",
                description = "Servicio profesional de prueba",
                quantity = BigDecimal("1"),
                unitPrice = BigDecimal("100"),
                ivaRateCode = ivaRateCode,
            ),
        ),
        payment = PaymentTerms(PaymentMethod.WITHOUT_FINANCIAL_SYSTEM),
        environment = Environment.TEST,
        issueDate = issueDate,
        sequential = 123L,
        accessKey = key(),
    )

    private fun mapped(): Computed<MappedInvoice> = when (val outcome = InvoiceMapper.map(request())) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw AssertionError("expected a mapped invoice, got ${outcome.error}")
    }

    @Test
    fun oneLineMapsToOctetsThatPassTheUnmodifiedXsdGate() {
        val document = mapped().value.document

        XsdGate.validate(document.octets())
    }

    @Test
    fun theRootCarriesTheComputedTotalsAndTheFixedZeroFields() {
        val xml = String(mapped().value.document.root, Charsets.UTF_8)

        assertTrue(xml, xml.contains("<totalSinImpuestos>100.00</totalSinImpuestos>"))
        assertTrue(xml, xml.contains("<valor>15.00</valor>"))
        assertTrue(xml, xml.contains("<importeTotal>115.00</importeTotal>"))
        assertTrue(xml, xml.contains("<propina>0.00</propina>"))
        assertTrue(xml, xml.contains("<totalDescuento>0.00</totalDescuento>"))
        assertTrue(xml, xml.contains("<formaPago>01</formaPago>"))
        assertTrue(xml, xml.contains("<dirEstablecimiento>$headOfficeAddress</dirEstablecimiento>"))
    }

    @Test
    fun theSinglePaymentCarriesTheGrandTotalAndNoCreditTerm() {
        val xml = String(mapped().value.document.root, Charsets.UTF_8)

        assertTrue(xml, xml.contains("<pago><formaPago>01</formaPago><total>115.00</total></pago>"))
        assertFalse(xml, xml.contains("<plazo>"))
    }

    @Test
    fun anUnknownIvaCodeIsRefusedWithItsLineIndex() {
        val outcome = InvoiceMapper.map(request(ivaRateCode = "99"))

        assertEquals(Outcome.Err(MappingError.Totals(TotalsError.UnknownIvaCode(0))), outcome)
    }

    @Test
    fun anIvaCodeWithNoFixedRateIsRefusedAsNotComputable() {
        val outcome = InvoiceMapper.map(request(ivaRateCode = "8"))

        assertEquals(Outcome.Err(MappingError.Totals(TotalsError.IvaCodeNotComputable(0))), outcome)
    }

    @Test
    fun theInputTypesRenderNoFieldValue() {
        val request = request()

        assertEquals("InvoiceRequest(redacted)", request.toString())
        assertEquals("IssuerProfile(redacted)", request.issuer.toString())
        assertEquals("Buyer(redacted)", request.buyer.toString())
    }
}
