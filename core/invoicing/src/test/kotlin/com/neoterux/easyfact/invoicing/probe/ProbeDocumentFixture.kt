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

package com.neoterux.easyfact.invoicing.probe

import com.neoterux.easyfact.invoicing.accesskey.AccessKey
import com.neoterux.easyfact.invoicing.factura.Factura
import com.neoterux.easyfact.invoicing.factura.FacturaWriter
import com.neoterux.easyfact.invoicing.factura.InvoiceInfo
import com.neoterux.easyfact.invoicing.factura.InvoiceLine
import com.neoterux.easyfact.invoicing.factura.LineTax
import com.neoterux.easyfact.invoicing.factura.Payment
import com.neoterux.easyfact.invoicing.factura.TaxInfo
import com.neoterux.easyfact.invoicing.factura.TaxTotal
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The one place the probe's document is built.
 *
 * [document] and [canonicalOctets] emit through [FacturaWriter], never by hand or by string
 * assembly, and both the CI shape test and the submit path call them. What CI pins is therefore,
 * by construction, what the writer emits and what the owner submits: there is no second document
 * that could drift from the first.
 *
 * Every default below is synthetic. The real RUC, establishment code, emission point and
 * sequential come from [ProbeConfig] at submission time through [Values.copy]; nothing in this
 * file is ever swapped for a real value, and the free-text fields the owner may want to match to
 * the registered issuer come from the same external file.
 *
 * The buyer is the SRI's final-consumer placeholder (identification type `07`), so no customer is
 * involved and no real person's data can appear in the document. Its identification is thirteen
 * nines, built at run time so no identifier-shaped literal sits in this source.
 */
object ProbeDocumentFixture {

    /** The values a probe document is built from. Defaults are synthetic and fixed, so the shape test is deterministic. */
    data class Values(
        val issueDate: LocalDate = LocalDate.of(2026, 10, 27),
        val environmentCode: String = "1",
        val issuerRuc: String = SyntheticIdentifiers.RUC,
        val establishmentCode: String = "001",
        val emissionPointCode: String = "001",
        val sequential: Long = 1L,
        val issuerLegalName: String = "Test Issuer S.A.",
        val headOfficeAddress: String = "Test Street 1",
    )

    private const val NUMERIC_CODE = "12345678"
    private const val FINAL_CONSUMER_NAME = "CONSUMIDOR FINAL"
    private val FINAL_CONSUMER_IDENTIFICATION = "9".repeat(13)

    /** The key for [values]; a failure here is a bug in the fixture values, so it throws. */
    fun accessKey(values: Values = Values()): AccessKey =
        when (
            val outcome = AccessKey.of(
                issueDate = values.issueDate,
                documentTypeCode = "01",
                issuerRuc = values.issuerRuc,
                environmentCode = values.environmentCode,
                establishment = values.establishmentCode,
                emissionPoint = values.emissionPointCode,
                sequential = values.sequential,
                numericCode = NUMERIC_CODE,
                emissionTypeCode = "1",
            )
        ) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw IllegalArgumentException("probe fixture values do not make an access key: ${outcome.error}")
        }

    fun factura(values: Values = Values()): Factura {
        val one = BigDecimal("1.00")
        val zero = BigDecimal("0.00")
        return Factura(
            taxInfo = TaxInfo(
                environmentCode = values.environmentCode,
                emissionTypeCode = "1",
                legalName = values.issuerLegalName,
                tradeName = "Test Trade Name",
                ruc = values.issuerRuc,
                accessKey = accessKey(values).digits,
                documentTypeCode = "01",
                establishmentCode = values.establishmentCode,
                emissionPointCode = values.emissionPointCode,
                sequential = values.sequential.toString().padStart(9, '0'),
                headOfficeAddress = values.headOfficeAddress,
            ),
            invoiceInfo = InvoiceInfo(
                issueDate = values.issueDate,
                establishmentAddress = values.headOfficeAddress,
                accountingRequired = false,
                buyerIdentificationTypeCode = "07",
                buyerLegalName = FINAL_CONSUMER_NAME,
                buyerIdentification = FINAL_CONSUMER_IDENTIFICATION,
                totalWithoutTaxes = one,
                totalDiscount = zero,
                taxTotals = listOf(TaxTotal("2", "0", one, zero)),
                tip = zero,
                grandTotal = one,
                currency = "DOLAR",
                payments = listOf(Payment("01", one)),
            ),
            lines = listOf(
                InvoiceLine(
                    mainCode = "001",
                    description = "Test service",
                    quantity = BigDecimal("1"),
                    unitPrice = one,
                    discount = zero,
                    totalWithoutTaxes = one,
                    taxes = listOf(LineTax("2", "0", zero, one, zero)),
                ),
            ),
        )
    }

    /** A failure here is a bug in the fixture values, so it throws rather than returning an outcome. */
    fun document(values: Values = Values()): CanonicalDocument =
        when (val outcome = FacturaWriter.write(factura(values))) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw IllegalArgumentException("probe fixture values do not make a document: ${outcome.error}")
        }

    /** The octets of [document] as the writer emitted them: the declaration and the unsigned root. */
    fun canonicalOctets(values: Values = Values()): ByteArray = document(values).octets()
}
