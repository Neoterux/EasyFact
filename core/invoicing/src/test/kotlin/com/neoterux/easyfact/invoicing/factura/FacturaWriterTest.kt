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

import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.signing.FixedIdSource
import com.neoterux.easyfact.invoicing.signing.Rfc2253Jdk
import com.neoterux.easyfact.invoicing.signing.TestIdentities
import com.neoterux.easyfact.invoicing.signing.XadesBesSigner
import com.neoterux.easyfact.invoicing.verify.C14nIdentityOracle
import com.neoterux.easyfact.invoicing.verify.Jsr105Verifier
import com.neoterux.easyfact.invoicing.verify.XsdGate
import java.math.BigDecimal
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacturaWriterTest {

    private fun rootXml(factura: Factura = SampleFactura.factura()): String =
        String(SampleFactura.write(factura).root, Charsets.UTF_8)

    @Test
    fun prologIsTheDeclarationFollowedByOneLineFeed() {
        val document = SampleFactura.document()
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n", String(document.prolog, Charsets.UTF_8))
    }

    @Test
    fun rootStartsAndEndsWithTheFacturaElement() {
        val xml = rootXml()
        assertTrue(xml.startsWith("<factura"))
        assertTrue(xml.endsWith("</factura>"))
    }

    @Test
    fun rootCarriesLowercaseIdAndVersionInC14nOrder() {
        assertTrue(rootXml().startsWith("<factura id=\"comprobante\" version=\"1.1.0\">"))
    }

    @Test
    fun outputIsCompactWithNoInterElementWhitespace() {
        val xml = rootXml()
        assertFalse(xml.contains(">\n"))
        assertFalse(xml.contains("> <"))
        assertFalse(xml.contains("\r"))
        assertFalse(xml.contains("/>"))
    }

    @Test
    fun sectionsAreInSchemaOrder() {
        val xml = rootXml()
        val order = listOf("<infoTributaria>", "<infoFactura>", "<detalles>", "<infoAdicional>").map { xml.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun infoTributariaChildrenAreInSchemaOrder() {
        val xml = rootXml()
        val names = listOf(
            "ambiente", "tipoEmision", "razonSocial", "nombreComercial", "ruc", "claveAcceso",
            "codDoc", "estab", "ptoEmi", "secuencial", "dirMatriz",
        )
        val positions = names.map { xml.indexOf("<$it>") }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun tradeNameIsOmittedWhenAbsent() {
        val base = SampleFactura.factura()
        assertFalse(rootXml(base.copy(taxInfo = base.taxInfo.copy(tradeName = null))).contains("nombreComercial"))
    }

    @Test
    fun infoAdicionalIsOmittedWhenThereAreNoFields() {
        assertFalse(rootXml(SampleFactura.factura().copy(additionalFields = emptyList())).contains("infoAdicional"))
    }

    @Test
    fun additionalFieldNameIsAnAttributeAndItsValueIsText() {
        assertTrue(rootXml().contains("<campoAdicional nombre=\"Email\">billing@example.com</campoAdicional>"))
    }

    @Test
    fun moneyIsWrittenWithTwoDecimalsHalfUpAndNeverInScientificNotation() {
        val base = SampleFactura.factura()
        val info = base.invoiceInfo.copy(
            totalWithoutTaxes = BigDecimal("0.125"), // rounds up to 0.13
            totalDiscount = BigDecimal("0.124"), // rounds down to 0.12
            tip = BigDecimal("1E+2"), // a plain 100.00, not 1E+2
            grandTotal = BigDecimal("2.675"), // rounds up to 2.68
        )
        val xml = rootXml(base.copy(invoiceInfo = info))
        assertTrue(xml.contains("<totalSinImpuestos>0.13</totalSinImpuestos>"))
        assertTrue(xml.contains("<totalDescuento>0.12</totalDescuento>"))
        assertTrue(xml.contains("<propina>100.00</propina>"))
        assertTrue(xml.contains("<importeTotal>2.68</importeTotal>"))
    }

    @Test
    fun quantityAndUnitPriceAreWrittenWithSixDecimals() {
        val xml = rootXml()
        assertTrue(xml.contains("<cantidad>1.000000</cantidad>"))
        assertTrue(xml.contains("<precioUnitario>100.000000</precioUnitario>"))
    }

    /**
     * Pinned deliberately against the phase plan, which named two decimals for the unit price.
     * Rounding a unit price to two before it is multiplied is what makes `cantidad` times
     * `precioUnitario` disagree with `precioTotalSinImpuesto`, and the SRI rejects that
     * disagreement, so the wider scale is the safer of the two. Changing it is a decision about
     * SRI arithmetic rather than a formatting tweak, and it has to break this test first.
     */
    @Test
    fun aUnitPriceKeepsSixDecimalsAndRoundsHalfUpAtTheSeventh() {
        val base = SampleFactura.factura()
        val line = base.lines.first().copy(
            quantity = BigDecimal("2.5"),
            unitPrice = BigDecimal("12.3456785"), // half-up at the seventh decimal
            totalWithoutTaxes = BigDecimal("30.86"),
        )
        val xml = rootXml(base.copy(lines = listOf(line)))
        assertTrue(xml, xml.contains("<cantidad>2.500000</cantidad>"))
        assertTrue(xml, xml.contains("<precioUnitario>12.345679</precioUnitario>"))
        // The line total stays money-scaled at two, which is the SRI's rule for an amount.
        assertTrue(xml, xml.contains("<precioTotalSinImpuesto>30.86</precioTotalSinImpuesto>"))
    }

    @Test
    fun issueDateIsDayMonthYear() {
        assertTrue(rootXml().contains("<fechaEmision>27/10/2026</fechaEmision>"))
    }

    @Test
    fun freeTextIsEscapedAndNonAsciiIsKept() {
        val xml = rootXml()
        assertTrue(xml.contains("<razonSocial>Test Emisor S.A. &amp; Cia</razonSocial>"))
        assertTrue(xml.contains("<dirMatriz>Calle Falsa 123 &lt;N&gt;</dirMatriz>"))
        assertTrue(xml.contains("Cliente de prueba Ñandú"))
        assertTrue(xml.contains("Servicio de prueba \"ABC\" &gt; 1"))
    }

    @Test
    fun paymentsFollowTheCurrencyInInfoFactura() {
        val xml = rootXml()
        assertTrue(xml.indexOf("<moneda>") < xml.indexOf("<pagos>"))
        assertTrue(xml.contains("<pagos><pago><formaPago>01</formaPago><total>100.00</total></pago></pagos>"))
    }

    // ---- the full 1.1.0 shape (plan 02-05, task 2) ------------------------------------------

    private fun fullXml(): String = String(SampleFactura.write(SampleFactura.fullyPopulated()).root, Charsets.UTF_8)

    /** The element names of [xml] in document order, including repeats. */
    private fun elementNames(xml: String): List<String> =
        Regex("<([A-Za-z][A-Za-z0-9.-]*)[ >]").findAll(xml).map { it.groupValues[1] }.toList()

    private fun error(factura: Factura): FacturaError =
        (FacturaWriter.write(factura) as Outcome.Err).error

    private fun assertNoElement(xml: String, name: String) {
        assertFalse("<$name> must be absent entirely", xml.contains("<$name>") || xml.contains("<$name "))
    }

    @Test
    fun aMandatoryOnlyDocumentEmitsNoConditionalElementAtAll() {
        val minimal = SampleFactura.factura().let {
            it.copy(
                taxInfo = it.taxInfo.copy(tradeName = null),
                additionalFields = emptyList(),
            )
        }
        val xml = String(SampleFactura.write(minimal).root, Charsets.UTF_8)
        val conditional = listOf(
            "nombreComercial", "agenteRetencion", "contribuyenteRimpe", "contribuyenteEspecial",
            "comercioExterior", "incoTermFactura", "lugarIncoTerm", "paisOrigen", "puertoEmbarque",
            "puertoDestino", "paisDestino", "paisAdquisicion", "guiaRemision", "direccionComprador",
            "totalSubsidio", "incoTermTotalSinImpuestos", "codDocReembolso",
            "totalComprobantesReembolso", "totalBaseImponibleReembolso", "totalImpuestoReembolso",
            "compensaciones", "fleteInternacional", "seguroInternacional", "gastosAduaneros",
            "gastosTransporteOtros", "placa", "valorRetIva", "valorRetRenta", "codigoAuxiliar",
            "precioSinSubsidio", "detallesAdicionales", "descuentoAdicional", "valorDevolucionIva",
            "plazo", "unidadTiempo", "reembolsos", "retenciones",
            "tipoNegociable", "infoAdicional",
        )
        conditional.forEach { assertNoElement(xml, it) }
        // An empty element is never written to stand in for an absent one.
        assertFalse(xml, Regex("<([A-Za-z][A-Za-z0-9.-]*)></\\1>").containsMatchIn(xml))
    }

    @Test
    fun totalDescuentoAndPropinaAreEmittedEvenWhenZero() {
        val base = SampleFactura.factura()
        val xml = String(
            SampleFactura.write(
                base.copy(invoiceInfo = base.invoiceInfo.copy(totalDiscount = BigDecimal.ZERO, tip = BigDecimal.ZERO)),
            ).root,
            Charsets.UTF_8,
        )
        assertTrue(xml, xml.contains("<totalDescuento>0.00</totalDescuento>"))
        assertTrue(xml, xml.contains("<propina>0.00</propina>"))
    }

    @Test
    fun aPaymentEmitsItsChildrenInSchemaOrder() {
        val base = SampleFactura.factura()
        val payment = Payment("01", BigDecimal("100"), term = BigDecimal("30"), termUnit = "dias")
        val xml = String(
            SampleFactura.write(base.copy(invoiceInfo = base.invoiceInfo.copy(payments = listOf(payment)))).root,
            Charsets.UTF_8,
        )
        assertTrue(
            xml,
            xml.contains(
                "<pagos><pago><formaPago>01</formaPago><total>100.00</total>" +
                    "<plazo>30.00</plazo><unidadTiempo>dias</unidadTiempo></pago></pagos>",
            ),
        )
    }

    @Test
    fun totalConImpuestosEmitsOneTotalImpuestoPerGroupWithItsFourMandatoryChildren() {
        val base = SampleFactura.factura()
        val totals = listOf(
            TaxTotal("2", "0", BigDecimal("100"), BigDecimal("0")),
            TaxTotal("2", "4", BigDecimal("50"), BigDecimal("7.5")),
        )
        val xml = String(
            SampleFactura.write(base.copy(invoiceInfo = base.invoiceInfo.copy(taxTotals = totals))).root,
            Charsets.UTF_8,
        )
        assertEquals(2, Regex("<totalImpuesto>").findAll(xml).count())
        assertTrue(
            xml,
            xml.contains(
                "<totalImpuesto><codigo>2</codigo><codigoPorcentaje>0</codigoPorcentaje>" +
                    "<baseImponible>100.00</baseImponible><valor>0.00</valor></totalImpuesto>",
            ),
        )
        assertTrue(
            xml,
            xml.contains(
                "<totalImpuesto><codigo>2</codigo><codigoPorcentaje>4</codigoPorcentaje>" +
                    "<baseImponible>50.00</baseImponible><valor>7.50</valor></totalImpuesto>",
            ),
        )
    }

    @Test
    fun theOptionalChildrenOfATotalImpuestoAreEmittedInSchemaOrderWhenPresent() {
        val base = SampleFactura.factura()
        val total = TaxTotal(
            taxCode = "2",
            rateCode = "0",
            taxableBase = BigDecimal("100"),
            amount = BigDecimal("0"),
            additionalDiscount = BigDecimal("1"),
            ivaRefundValue = BigDecimal("2"),
        )
        val xml = String(
            SampleFactura.write(base.copy(invoiceInfo = base.invoiceInfo.copy(taxTotals = listOf(total)))).root,
            Charsets.UTF_8,
        )
        assertTrue(
            xml,
            xml.contains(
                "<totalImpuesto><codigo>2</codigo><codigoPorcentaje>0</codigoPorcentaje>" +
                    "<descuentoAdicional>1.00</descuentoAdicional><baseImponible>100.00</baseImponible>" +
                    "<valor>0.00</valor><valorDevolucionIva>2.00</valorDevolucionIva></totalImpuesto>",
            ),
        )
    }

    @Test
    fun aLineEmitsDetallesAdicionalesWhenPresentAndOmitsTheElementWhenAbsent() {
        val base = SampleFactura.factura()
        assertNoElement(rootXml(base), "detallesAdicionales")
        val line = base.lines.first().copy(additionalDetails = listOf(LineDetailField("Periodo", "2026-10")))
        val xml = String(SampleFactura.write(base.copy(lines = listOf(line))).root, Charsets.UTF_8)
        assertTrue(
            xml,
            xml.contains(
                "<detallesAdicionales><detAdicional nombre=\"Periodo\" valor=\"2026-10\">" +
                    "</detAdicional></detallesAdicionales>",
            ),
        )
    }

    @Test
    fun infoAdicionalEmitsOneCampoAdicionalPerEntryEachCarryingItsNombreAttribute() {
        val base = SampleFactura.factura()
        val fields = listOf(AdditionalField("Email", "a@example.com"), AdditionalField("Periodo", "2026-10"))
        val xml = String(SampleFactura.write(base.copy(additionalFields = fields)).root, Charsets.UTF_8)
        assertEquals(2, Regex("<campoAdicional ").findAll(xml).count())
        assertTrue(xml, xml.contains("<campoAdicional nombre=\"Email\">a@example.com</campoAdicional>"))
        assertTrue(xml, xml.contains("<campoAdicional nombre=\"Periodo\">2026-10</campoAdicional>"))
    }

    @Test
    fun theOptionalBlocksAreAbsentOnTheSampleAndPresentOnTheFullyPopulatedDocument() {
        val blocks = listOf(
            "reembolsos", "retenciones", "comercioExterior", "compensaciones",
            "valorRetIva", "valorRetRenta", "tipoNegociable",
        )
        val minimal = rootXml()
        blocks.forEach { assertNoElement(minimal, it) }
        val full = fullXml()
        blocks.forEach { assertTrue("<$it> must be present\n$full", full.contains("<$it>")) }
    }

    @Test
    fun aReembolsoDetalleEmitsItsChildrenInSchemaOrder() {
        val names = elementNames(fullXml())
        val start = names.indexOf("reembolsoDetalle")
        assertTrue(start >= 0)
        val expected = listOf(
            "reembolsoDetalle", "tipoIdentificacionProveedorReembolso", "identificacionProveedorReembolso",
            "codPaisPagoProveedorReembolso", "tipoProveedorReembolso", "codDocReembolso",
            "estabDocReembolso", "ptoEmiDocReembolso", "secuencialDocReembolso",
            "fechaEmisionDocReembolso", "numeroautorizacionDocReemb", "detalleImpuestos",
            "detalleImpuesto", "codigo", "codigoPorcentaje", "tarifa", "baseImponibleReembolso",
            "impuestoReembolso",
        )
        assertEquals(expected, names.subList(start, start + expected.size))
    }

    // ---- typed failures ----------------------------------------------------------------------

    @Test
    fun aFieldLongerThanItsSchemaFacetIsATypedFailure() {
        val base = SampleFactura.factura()
        val tooLong = "A".repeat(301)
        val outcome = error(base.copy(taxInfo = base.taxInfo.copy(legalName = tooLong)))
        assertEquals(FacturaError.FieldTooLong("razonSocial", 300, 301), outcome)
    }

    @Test
    fun aCharacterIllegalInXmlIsATypedFailure() {
        val base = SampleFactura.factura()
        val outcome = error(base.copy(taxInfo = base.taxInfo.copy(legalName = "Test \u0000 S.A.")))
        assertEquals(FacturaError.IllegalCharacter("razonSocial"), outcome)
    }

    @Test
    fun aRequiredFieldThatIsBlankIsATypedFailure() {
        val base = SampleFactura.factura()
        assertEquals(
            FacturaError.BlankField("razonSocialComprador"),
            error(base.copy(invoiceInfo = base.invoiceInfo.copy(buyerLegalName = "   "))),
        )
    }

    @Test
    fun aCodeOutsideItsCatalogueIsATypedFailure() {
        val base = SampleFactura.factura()
        val outcome = error(base.copy(taxInfo = base.taxInfo.copy(environmentCode = "3")))
        assertTrue(outcome.toString(), outcome is FacturaError.CodeOutsideCatalogue)
        assertEquals("ambiente", outcome.element)
    }

    @Test
    fun moreThanFifteenAdditionalFieldsIsATypedFailure() {
        val base = SampleFactura.factura()
        val sixteen = (1..16).map { AdditionalField("Campo$it", "value") }
        assertEquals(
            FacturaError.TooManyEntries("infoAdicional", 15, 16),
            error(base.copy(additionalFields = sixteen)),
        )
    }

    // ---- facets equal the vendored schema (plan 02-13) ---------------------------------------------

    private fun assertWritesAndValidates(factura: Factura) {
        XsdGate.validate(SampleFactura.write(factura).octets())
    }

    @Test
    fun aCampoAdicionalOf300CharactersIsWrittenAndValidates() {
        val base = SampleFactura.factura()
        assertWritesAndValidates(base.copy(additionalFields = listOf(AdditionalField("Notes", "n".repeat(300)))))
    }

    @Test
    fun aCampoAdicionalOf301CharactersIsATypedFailure() {
        val base = SampleFactura.factura()
        assertEquals(
            FacturaError.FieldTooLong("campoAdicional", 300, 301),
            error(base.copy(additionalFields = listOf(AdditionalField("Notes", "n".repeat(301))))),
        )
    }

    @Test
    fun aCorreoOf101CharactersIsATypedFailure() {
        val base = SampleFactura.factura()
        val email = "a".repeat(101 - "@example.com".length) + "@example.com"
        assertEquals(101, email.length)
        assertEquals(FacturaError.FieldTooLong("correo", 100, 101), error(base.copy(negotiableEmail = email)))
    }

    @Test
    fun aCorreoOf100CharactersIsWrittenAndValidates() {
        val base = SampleFactura.factura()
        val email = "a".repeat(100 - "@example.com".length) + "@example.com"
        assertWritesAndValidates(base.copy(negotiableEmail = email))
    }

    @Test
    fun anEightDigitAgenteRetencionIsWrittenAndValidates() {
        val base = SampleFactura.factura()
        assertWritesAndValidates(base.copy(taxInfo = base.taxInfo.copy(withholdingAgentResolution = "12345678")))
    }

    @Test
    fun aTenLetterIncoTermFacturaIsWrittenAndValidates() {
        val base = SampleFactura.factura()
        val export = ExportInfo(code = "EXPORTADOR", incoTerm = "ABCDEFGHIJ")
        assertWritesAndValidates(base.copy(invoiceInfo = base.invoiceInfo.copy(exportInfo = export)))
    }

    @Test
    fun aThreeDigitCodDocReembolsoIsWrittenAndValidatesInBothPlaces() {
        val full = SampleFactura.fullyPopulated()
        val totals = checkNotNull(full.invoiceInfo.refundTotals).copy(documentTypeCode = "123")
        val refund = full.refunds.single().copy(documentTypeCode = "123")
        assertWritesAndValidates(
            full.copy(invoiceInfo = full.invoiceInfo.copy(refundTotals = totals), refunds = listOf(refund)),
        )
    }

    @Test
    fun aContribuyenteEspecialShorterThanThreeIsATypedFailure() {
        val base = SampleFactura.factura()
        assertEquals(
            FacturaError.FieldTooShort("contribuyenteEspecial", 3, 2),
            error(base.copy(invoiceInfo = base.invoiceInfo.copy(specialTaxpayerNumber = "12"))),
        )
    }

    @Test
    fun aContribuyenteEspecialOfThreeIsWrittenAndValidates() {
        val base = SampleFactura.factura()
        assertWritesAndValidates(base.copy(invoiceInfo = base.invoiceInfo.copy(specialTaxpayerNumber = "123")))
    }

    @Test
    fun aRefundAuthorizationNumberOfNineDigitsIsATypedFailure() {
        val full = SampleFactura.fullyPopulated()
        val refund = full.refunds.single().copy(authorizationNumber = "123456789")
        assertEquals(
            FacturaError.FieldTooShort("numeroautorizacionDocReemb", 10, 9),
            error(full.copy(refunds = listOf(refund))),
        )
    }

    private fun lineWithDetails(count: Int): Factura {
        val base = SampleFactura.factura()
        val details = (1..count).map { LineDetailField("Detail$it", "value") }
        return base.copy(lines = listOf(base.lines.single().copy(additionalDetails = details)))
    }

    @Test
    fun fourDetAdicionalEntriesOnOneLineIsATypedFailure() {
        assertEquals(FacturaError.TooManyEntries("detallesAdicionales", 3, 4), error(lineWithDetails(4)))
    }

    @Test
    fun threeDetAdicionalEntriesAreWrittenAndValidate() {
        assertWritesAndValidates(lineWithDetails(3))
    }

    /** A supplementary character is one schema character and two UTF-16 units. */
    private val supplementary = "\uD83D\uDE00"

    @Test
    fun aRazonSocialOf300CodePointsWithASupplementaryCharacterIsWrittenAndValidates() {
        val base = SampleFactura.factura()
        val name = "A".repeat(299) + supplementary
        assertEquals(300, name.codePointCount(0, name.length))
        assertEquals(301, name.length)
        assertWritesAndValidates(base.copy(taxInfo = base.taxInfo.copy(legalName = name)))
    }

    @Test
    fun aRazonSocialOf301CodePointsIsATypedFailureCountedInCodePoints() {
        val base = SampleFactura.factura()
        val name = "A".repeat(300) + supplementary
        assertEquals(
            FacturaError.FieldTooLong("razonSocial", 300, 301),
            error(base.copy(taxInfo = base.taxInfo.copy(legalName = name))),
        )
    }

    @Test
    fun aFailureNeverCarriesTheOffendingValue() {
        val base = SampleFactura.factura()
        val secret = "Z".repeat(301)
        val rendered = error(base.copy(taxInfo = base.taxInfo.copy(legalName = secret))).toString()
        assertFalse(rendered, rendered.contains(secret))
        assertFalse(rendered, rendered.contains("ZZZ"))
        val tooShort = error(base.copy(invoiceInfo = base.invoiceInfo.copy(specialTaxpayerNumber = "zq"))).toString()
        assertTrue(tooShort, tooShort.startsWith("FieldTooShort("))
        assertFalse(tooShort, tooShort.contains("zq"))
    }

    // ---- the full document still signs and verifies -------------------------------------------

    @Test
    fun theFullyPopulatedDocumentIsAC14nIdentity() {
        val root = SampleFactura.write(SampleFactura.fullyPopulated()).root
        assertArrayEquals(root, C14nIdentityOracle.canonicalize(root))
    }

    @Test
    fun theFullyPopulatedDocumentStillSignsAndValidatesInTheJsr105Oracle() {
        val document = SampleFactura.write(SampleFactura.fullyPopulated())
        val signer = XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, TestIdentities.fixedClock)
        val signed = (signer.sign(document, TestIdentities.plain) as Outcome.Ok).value.octets()
        assertEquals(Jsr105Verifier.Verdict.VALID, Jsr105Verifier.verdict(signed))
        val signedRoot = signed.copyOfRange(document.prolog.size, signed.size)
        assertArrayEquals(signedRoot, C14nIdentityOracle.canonicalize(signedRoot))
    }

    // ---- the writer builds no markup of its own ------------------------------------------------

    @Test
    fun theWriterNeverConcatenatesMarkupItself() {
        val source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/neoterux/easyfact/invoicing/factura/FacturaWriter.kt"),
        )
        val code = source.lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        assertFalse("the writer must emit through CanonicalEmitter only", code.any { it.contains("\"<") })
        assertFalse(code.any { it.contains("\"</") })
    }

    // ---- tax grouping and the schema-order guard (plan 02-05, task 3) ------------------------

    /**
     * Vectors chosen so that rounding each line before summing would give a different answer from
     * summing first. If the grouping ever starts rounding per line, these are what notice.
     */
    private val boundaryVectors: List<Pair<List<String>, String>> = listOf(
        listOf("0.035", "0.035") to "0.15",
        listOf("1.005", "2.005", "3.005") to "0.15",
        listOf("0.005", "0.005", "0.005") to "0.15",
        listOf("33.333333", "33.333333", "33.333334") to "0.15",
        listOf("0.015", "0.015") to "0.00",
    )

    @Test
    fun theLineSumAndTheGroupBaseFormRenderIdenticalStringsAtEveryBoundaryVector() {
        for ((bases, rateText) in boundaryVectors) {
            val rate = BigDecimal(rateText)
            val values = bases.map { BigDecimal(it) }
            val sumOfLineTaxes = values.map { it.multiply(rate) }.fold(BigDecimal.ZERO, BigDecimal::add)
            val taxOnTheGroupBase = values.fold(BigDecimal.ZERO, BigDecimal::add).multiply(rate)
            assertEquals(
                "bases=$bases rate=$rateText",
                Money.amount(sumOfLineTaxes),
                Money.amount(taxOnTheGroupBase),
            )
        }
    }

    @Test
    fun theBoundaryVectorsReallyWouldDisagreeIfEachLineWereRoundedFirst() {
        // Without this, the test above could pass on vectors where no rounding decision exists.
        val disagreeing = boundaryVectors.count { (bases, rateText) ->
            val rate = BigDecimal(rateText)
            val values = bases.map { BigDecimal(it) }
            val roundedPerLine = values
                .map { BigDecimal(Money.amount(it.multiply(rate))) }
                .fold(BigDecimal.ZERO, BigDecimal::add)
            val onTheGroupBase = values.fold(BigDecimal.ZERO, BigDecimal::add).multiply(rate)
            Money.amount(roundedPerLine) != Money.amount(onTheGroupBase)
        }
        assertTrue("at least one vector must have teeth", disagreeing > 0)
    }

    @Test
    fun groupingSumsTheLineBasesAtFullPrecisionAndScalesOnce() {
        val lines = listOf("0.035", "0.035").map { line(taxableBase = it, rate = "0.15") }
        val totals = TaxGrouping.totals(lines)
        assertEquals(1, totals.size)
        assertEquals("0.07", Money.amount(totals.first().taxableBase))
        assertEquals("0.01", Money.amount(totals.first().amount))
    }

    @Test
    fun twoLinesSharingACodePairCollapseIntoOneGroup() {
        val lines = listOf(line(taxableBase = "10", rate = "0.15"), line(taxableBase = "20", rate = "0.15"))
        val totals = TaxGrouping.totals(lines)
        assertEquals(1, totals.size)
        assertEquals("30.00", Money.amount(totals.first().taxableBase))
    }

    @Test
    fun linesWithDifferentCodePairsProduceGroupsOrderedDeterministically() {
        val encountered = listOf(
            line(taxableBase = "10", rate = "0.15", taxCode = "3", rateCode = "4"),
            line(taxableBase = "10", rate = "0.15", taxCode = "2", rateCode = "4"),
            line(taxableBase = "10", rate = "0.00", taxCode = "2", rateCode = "0"),
        )
        val totals = TaxGrouping.totals(encountered)
        assertEquals(listOf("2" to "0", "2" to "4", "3" to "4"), totals.map { it.taxCode to it.rateCode })
        // Encounter order must not matter: the reversed input produces the same groups.
        assertEquals(totals, TaxGrouping.totals(encountered.reversed()))
    }

    @Test
    fun aZeroRateGroupIsEmittedWithAZeroValueRatherThanOmitted() {
        val base = SampleFactura.factura()
        val lines = listOf(line(taxableBase = "100", rate = "0.00"))
        val xml = String(
            SampleFactura.write(
                base.copy(
                    lines = lines,
                    invoiceInfo = base.invoiceInfo.copy(taxTotals = TaxGrouping.totals(lines)),
                ),
            ).root,
            Charsets.UTF_8,
        )
        assertTrue(xml, xml.contains("<totalImpuesto>"))
        assertTrue(
            xml,
            xml.contains(
                "<totalImpuesto><codigo>2</codigo><codigoPorcentaje>0</codigoPorcentaje>" +
                    "<baseImponible>100.00</baseImponible><valor>0.00</valor></totalImpuesto>",
            ),
        )
    }

    private fun line(
        taxableBase: String,
        rate: String,
        taxCode: String = "2",
        rateCode: String = "0",
    ): InvoiceLine {
        val amount = BigDecimal(taxableBase).multiply(BigDecimal(rate))
        return SampleFactura.factura().lines.first().copy(
            taxes = listOf(LineTax(taxCode, rateCode, BigDecimal(rate), BigDecimal(taxableBase), amount)),
        )
    }

    /**
     * The emitted element order of a fully populated document, pinned here in the test source.
     *
     * The authoritative check is the XSD gate plan 02-09 builds; this one exists so that a model
     * refactor that silently reorders a document fails in seconds rather than at the end of a
     * schema validation run. A change here is a change to the document the SRI receives, so it
     * has to be made deliberately.
     */
    private val expectedElementOrder: List<String> = listOf(
            "factura", "infoTributaria", "ambiente", "tipoEmision", "razonSocial", "nombreComercial", "ruc",
            "claveAcceso", "codDoc", "estab", "ptoEmi", "secuencial", "dirMatriz", "agenteRetencion",
            "contribuyenteRimpe", "infoFactura", "fechaEmision", "dirEstablecimiento", "contribuyenteEspecial",
            "obligadoContabilidad", "comercioExterior", "incoTermFactura", "lugarIncoTerm", "paisOrigen",
            "puertoEmbarque", "puertoDestino", "paisDestino", "paisAdquisicion", "tipoIdentificacionComprador",
            "guiaRemision", "razonSocialComprador", "identificacionComprador", "direccionComprador",
            "totalSinImpuestos", "totalSubsidio", "incoTermTotalSinImpuestos", "totalDescuento",
            "codDocReembolso", "totalComprobantesReembolso", "totalBaseImponibleReembolso",
            "totalImpuestoReembolso", "totalConImpuestos", "totalImpuesto", "codigo", "codigoPorcentaje",
            "descuentoAdicional", "baseImponible", "valor", "valorDevolucionIva", "compensaciones",
            "compensacion", "codigo", "tarifa", "valor", "propina", "fleteInternacional",
            "seguroInternacional", "gastosAduaneros", "gastosTransporteOtros", "importeTotal", "moneda",
            "placa", "pagos", "pago", "formaPago", "total", "plazo", "unidadTiempo", "valorRetIva",
            "valorRetRenta", "detalles", "detalle", "codigoPrincipal", "codigoAuxiliar", "descripcion",
            "cantidad", "precioUnitario", "precioSinSubsidio", "descuento", "precioTotalSinImpuesto",
            "detallesAdicionales", "detAdicional", "impuestos", "impuesto", "codigo", "codigoPorcentaje",
            "tarifa", "baseImponible", "valor", "reembolsos", "reembolsoDetalle",
            "tipoIdentificacionProveedorReembolso", "identificacionProveedorReembolso",
            "codPaisPagoProveedorReembolso", "tipoProveedorReembolso", "codDocReembolso", "estabDocReembolso",
            "ptoEmiDocReembolso", "secuencialDocReembolso", "fechaEmisionDocReembolso",
            "numeroautorizacionDocReemb", "detalleImpuestos", "detalleImpuesto", "codigo", "codigoPorcentaje",
            "tarifa", "baseImponibleReembolso", "impuestoReembolso", "retenciones", "retencion", "codigo",
            "codigoPorcentaje", "tarifa", "valor",
            "tipoNegociable", "correo", "infoAdicional", "campoAdicional"
    )

    @Test
    fun theElementOrderOfAFullyPopulatedDocumentIsUnchanged() {
        assertEquals(expectedElementOrder, elementNames(fullXml()))
    }
}
