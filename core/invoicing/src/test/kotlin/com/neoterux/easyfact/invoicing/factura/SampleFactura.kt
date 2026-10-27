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
    private val accessKey: String = (
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
        ).value.digits

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

    fun document(): CanonicalDocument = FacturaWriter.write(factura())
}
