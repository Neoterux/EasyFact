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
import com.neoterux.easyfact.invoicing.result.EngineWarning
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.text.SanitizerError
import com.neoterux.easyfact.invoicing.totals.BuyerGuardError
import com.neoterux.easyfact.invoicing.totals.TotalsError
import com.neoterux.easyfact.invoicing.verify.XsdGate
import com.neoterux.easyfact.testing.SyntheticIdNumbers
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Builders shared by [InvoiceMapperTest] and [KeyInvarianceTest]: every parameter has a synthetic default,
 * so a test names only what it varies. No identifier-shaped literal is written here; the numbers come from
 * `SyntheticIdentifiers` and `SyntheticIdNumbers`, and the access key is derived from the request's identity
 * exactly as Phase 6 will derive it at allocation.
 */
internal object MapperFixtures {

    val issueDate: LocalDate = LocalDate.of(2026, 10, 27)

    const val DEFAULT_ADDRESS = "Calle Falsa 123"
    const val DEFAULT_SEQUENTIAL = 123L

    fun key(
        issuerRuc: String = SyntheticIdentifiers.RUC,
        environment: Environment = Environment.TEST,
        establishment: String = "001",
        emissionPoint: String = "002",
        sequential: Long = DEFAULT_SEQUENTIAL,
        date: LocalDate = issueDate,
    ): AccessKey = when (
        val outcome = KeyDerivation.accessKey(issuerRuc, environment.code, establishment, emissionPoint, sequential, date)
    ) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw AssertionError("expected a key, got ${outcome.error}")
    }

    fun issuer(
        tradeName: String? = null,
        accountingRequired: Boolean = false,
        specialTaxpayerNumber: String? = null,
        rimpeLegend: RimpeLegend = RimpeLegend.NONE,
        withholdingAgent: WithholdingAgentResolution? = null,
        providerRuc: String? = null,
        legalName: String = "Test Emisor S.A.",
    ): IssuerProfile = IssuerProfile(
        ruc = SyntheticIdentifiers.RUC,
        legalName = legalName,
        tradeName = tradeName,
        headOfficeAddress = DEFAULT_ADDRESS,
        establishment = "001",
        emissionPoint = "002",
        accountingRequired = accountingRequired,
        specialTaxpayerNumber = specialTaxpayerNumber,
        rimpeLegend = rimpeLegend,
        withholdingAgent = withholdingAgent,
        providerRuc = providerRuc,
    )

    fun buyer(
        idType: BuyerIdType = BuyerIdType.CEDULA,
        identification: String = SyntheticIdNumbers.cedula(1),
        legalName: String = SyntheticIdentifiers.CUSTOMER_NAME,
        billingEmail: String = SyntheticIdentifiers.BILLING_EMAIL,
        address: String? = null,
        phone: String? = null,
    ): Buyer = Buyer(idType, identification, legalName, billingEmail, address, phone)

    fun line(
        code: String? = "SRV-01",
        description: String = "Servicio profesional de prueba",
        quantity: String = "1",
        unitPrice: String = "100",
        ivaRateCode: String = "4",
    ): ResolvedLine = ResolvedLine(code, description, BigDecimal(quantity), BigDecimal(unitPrice), ivaRateCode)

    fun consumidorFinalBuyer(): Buyer = buyer(
        idType = BuyerIdType.CONSUMIDOR_FINAL,
        identification = SyntheticIdNumbers.consumidorFinal(),
        legalName = "CONSUMIDOR FINAL",
    )

    fun request(
        issuer: IssuerProfile = issuer(),
        buyer: Buyer = buyer(),
        lines: List<ResolvedLine> = listOf(line()),
        payment: PaymentTerms = PaymentTerms(PaymentMethod.WITHOUT_FINANCIAL_SYSTEM),
        environment: Environment = Environment.TEST,
        date: LocalDate = issueDate,
        sequential: Long = DEFAULT_SEQUENTIAL,
        templateFields: List<TemplateField> = emptyList(),
        accessKey: AccessKey = key(issuer.ruc, environment, issuer.establishment, issuer.emissionPoint, sequential, date),
    ): InvoiceRequest = InvoiceRequest(
        issuer = issuer,
        buyer = buyer,
        lines = lines,
        payment = payment,
        environment = environment,
        issueDate = date,
        sequential = sequential,
        accessKey = accessKey,
        templateFields = templateFields,
    )

    /** The mapped invoice, or an assertion error that names [variant] and the (value-free) error. */
    fun mappedOk(variant: String, request: InvoiceRequest): Computed<MappedInvoice> =
        when (val outcome = InvoiceMapper.map(request)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("variant $variant: expected a mapped invoice, got ${outcome.error}")
        }

    /** The refusal, or an assertion error that names [variant]. */
    fun refusal(variant: String, request: InvoiceRequest): MappingError =
        when (val outcome = InvoiceMapper.map(request)) {
            is Outcome.Ok -> throw AssertionError("variant $variant: expected a refusal, got a mapped invoice")
            is Outcome.Err -> outcome.error
        }

    fun xml(mapped: Computed<MappedInvoice>): String = String(mapped.value.document.root, Charsets.UTF_8)

    /** The `nombre` of every `campoAdicional`, in document order. */
    fun additionalNames(xml: String): List<String> =
        Regex("<campoAdicional nombre=\"([^\"]*)\">").findAll(xml).map { it.groupValues[1] }.toList()
}

/**
 * ROADMAP SC1 and the mapping decisions of plan 03-08 (SRI-01, SRI-02, NFR-04): the full plain-invoice
 * mapping, every variant measured against the unmodified XSD gate.
 *
 * Assertion messages name the variant, never a value: the documents hold generated identification numbers.
 */
class InvoiceMapperTest {

    private val marker = "\uE000"
    private val addressName = "Dirección"
    private val phoneName = "Teléfono"
    private val emptyElement = Regex("<([A-Za-z]+)></\\1>")
    private val selfClosing = Regex("<[A-Za-z]+/>")

    /** The shape every mapped document must have, whatever the variant. */
    private fun assertShape(variant: String, xml: String) {
        assertTrue("variant $variant: no formaPago", xml.contains("<formaPago>"))
        assertTrue("variant $variant: no propina 0.00", xml.contains("<propina>0.00</propina>"))
        assertTrue("variant $variant: no totalDescuento 0.00", xml.contains("<totalDescuento>0.00</totalDescuento>"))
        assertFalse("variant $variant: an empty element", emptyElement.containsMatchIn(xml))
        assertFalse("variant $variant: a self-closing element", selfClosing.containsMatchIn(xml))
    }

    private fun templateFields(count: Int): List<TemplateField> =
        (1..count).map { TemplateField("Campo $it", "Valor $it") }

    // --- SC1: schema validity of every variant ----------------------------------------------------

    @Test
    fun theMinimalInvoiceMapsAndPassesTheGate() {
        val mapped = MapperFixtures.mappedOk("minimal", MapperFixtures.request())

        XsdGate.validate(mapped.value.document.octets())
        assertShape("minimal", MapperFixtures.xml(mapped))
    }

    @Test
    fun aZeroAndAFifteenPercentLineMapAndPassTheGate() {
        val request = MapperFixtures.request(
            lines = listOf(
                MapperFixtures.line(code = "A-1", ivaRateCode = "0"),
                MapperFixtures.line(code = "B-2", ivaRateCode = "4", unitPrice = "40"),
            ),
        )
        val mapped = MapperFixtures.mappedOk("mixed rates", request)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("mixed rates", MapperFixtures.xml(mapped))
    }

    @Test
    fun theEntrepreneurLegendIsWrittenAndPassesTheGate() {
        val request = MapperFixtures.request(issuer = MapperFixtures.issuer(rimpeLegend = RimpeLegend.EMPRENDEDOR))
        val mapped = MapperFixtures.mappedOk("rimpe entrepreneur", request)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("rimpe entrepreneur", MapperFixtures.xml(mapped))
        assertTrue(
            MapperFixtures.xml(mapped).contains("<contribuyenteRimpe>CONTRIBUYENTE RÉGIMEN RIMPE</contribuyenteRimpe>"),
        )
    }

    @Test
    fun thePopularBusinessLegendIsWrittenAndPassesTheGate() {
        val request = MapperFixtures.request(issuer = MapperFixtures.issuer(rimpeLegend = RimpeLegend.NEGOCIO_POPULAR))
        val mapped = MapperFixtures.mappedOk("rimpe popular business", request)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("rimpe popular business", MapperFixtures.xml(mapped))
        assertTrue(
            MapperFixtures.xml(mapped).contains(
                "<contribuyenteRimpe>CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE</contribuyenteRimpe>",
            ),
        )
    }

    @Test
    fun noLegendWritesNoContribuyenteRimpeElement() {
        val mapped = MapperFixtures.mappedOk("no legend", MapperFixtures.request())

        assertFalse(MapperFixtures.xml(mapped).contains("contribuyenteRimpe"))
    }

    @Test
    fun aWithholdingAgentIsWrittenWithoutLeadingZerosAndPassesTheGate() {
        val agent = (WithholdingAgentResolution.of("00012") as Outcome.Ok).value
        val request = MapperFixtures.request(issuer = MapperFixtures.issuer(withholdingAgent = agent))
        val mapped = MapperFixtures.mappedOk("withholding agent", request)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("withholding agent", MapperFixtures.xml(mapped))
        assertTrue(MapperFixtures.xml(mapped).contains("<agenteRetencion>12</agenteRetencion>"))
    }

    @Test
    fun aSpecialTaxpayerIsWrittenAndPassesTheGate() {
        val request = MapperFixtures.request(issuer = MapperFixtures.issuer(specialTaxpayerNumber = "SYN12345"))
        val mapped = MapperFixtures.mappedOk("special taxpayer", request)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("special taxpayer", MapperFixtures.xml(mapped))
        assertTrue(MapperFixtures.xml(mapped).contains("<contribuyenteEspecial>SYN12345</contribuyenteEspecial>"))
    }

    @Test
    fun aProviderRucIsWrittenAsAnAdditionalFieldAndPassesTheGate() {
        val request = MapperFixtures.request(issuer = MapperFixtures.issuer(providerRuc = SyntheticIdentifiers.RUC))
        val mapped = MapperFixtures.mappedOk("with provider ruc", request)
        val xml = MapperFixtures.xml(mapped)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("with provider ruc", xml)
        assertTrue(xml.contains("<campoAdicional nombre=\"RUC Proveedor\">${SyntheticIdentifiers.RUC}</campoAdicional>"))
    }

    @Test
    fun noProviderRucMeansNoRucProveedorEntry() {
        val mapped = MapperFixtures.mappedOk("without provider ruc", MapperFixtures.request())

        XsdGate.validate(mapped.value.document.octets())
        assertFalse(MapperFixtures.xml(mapped).contains("RUC Proveedor"))
    }

    @Test
    fun aCreditTermIsWrittenAndPassesTheGate() {
        val request = MapperFixtures.request(
            payment = PaymentTerms(PaymentMethod.OTHER_WITH_FINANCIAL_SYSTEM, CreditTerm(BigDecimal("30"), "dias")),
        )
        val mapped = MapperFixtures.mappedOk("credit term", request)
        val xml = MapperFixtures.xml(mapped)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("credit term", xml)
        assertTrue(xml.contains("<plazo>"))
        assertTrue(xml.contains("<unidadTiempo>dias</unidadTiempo>"))
    }

    @Test
    fun fifteenAdditionalEntriesMapAndPassTheGate() {
        val request = MapperFixtures.request(
            issuer = MapperFixtures.issuer(providerRuc = SyntheticIdentifiers.RUC),
            buyer = MapperFixtures.buyer(address = "Avenida Falsa 456", phone = "02 000 0000"),
            templateFields = templateFields(11),
        )
        val mapped = MapperFixtures.mappedOk("fifteen additional entries", request)

        XsdGate.validate(mapped.value.document.octets())
        assertEquals(15, MapperFixtures.additionalNames(MapperFixtures.xml(mapped)).size)
    }

    @Test
    fun aConsumidorFinalInvoiceAtFiftyMapsAndPassesTheGate() {
        val request = MapperFixtures.request(
            buyer = MapperFixtures.consumidorFinalBuyer(),
            lines = listOf(ResolvedLine.lumpSum("CF-1", "Servicio de prueba", BigDecimal("50.00"), "0")),
        )
        val mapped = MapperFixtures.mappedOk("consumidor final at 50.00", request)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("consumidor final at 50.00", MapperFixtures.xml(mapped))
        assertTrue(MapperFixtures.xml(mapped).contains("<tipoIdentificacionComprador>07</tipoIdentificacionComprador>"))
    }

    @Test
    fun aLumpSumLineMapsAndPassesTheGate() {
        val request = MapperFixtures.request(
            lines = listOf(ResolvedLine.lumpSum("LS-1", "Honorarios del mes", BigDecimal("250.00"), "0")),
        )
        val mapped = MapperFixtures.mappedOk("lump sum", request)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("lump sum", MapperFixtures.xml(mapped))
        assertTrue(MapperFixtures.xml(mapped).contains("<importeTotal>250.00</importeTotal>"))
    }

    @Test
    fun aZeroQuantityLineMapsPassesTheGateAndWarns() {
        val request = MapperFixtures.request(
            lines = listOf(MapperFixtures.line(), MapperFixtures.line(code = "B-2", quantity = "0")),
        )
        val mapped = MapperFixtures.mappedOk("zero quantity", request)

        XsdGate.validate(mapped.value.document.octets())
        assertShape("zero quantity", MapperFixtures.xml(mapped))
        assertTrue(mapped.warnings.contains(EngineWarning.ZeroQuantity(1)))
    }

    // --- infoAdicional (D-18, D-19) ---------------------------------------------------------------

    @Test
    fun additionalFieldsAreWrittenInTheDecidedOrder() {
        val request = MapperFixtures.request(
            issuer = MapperFixtures.issuer(providerRuc = SyntheticIdentifiers.RUC),
            buyer = MapperFixtures.buyer(address = "Avenida Falsa 456", phone = "02 000 0000"),
            templateFields = listOf(TemplateField("Paciente", "Persona de prueba"), TemplateField("Periodo", "Octubre 2026")),
        )
        val names = MapperFixtures.additionalNames(MapperFixtures.xml(MapperFixtures.mappedOk("order", request)))

        assertEquals(listOf("Email", addressName, phoneName, "RUC Proveedor", "Paciente", "Periodo"), names)
    }

    @Test
    fun anAbsentAddressAndPhoneAreSkippedWithoutGapsInTheOrder() {
        val request = MapperFixtures.request(
            buyer = MapperFixtures.buyer(phone = "02 000 0000"),
            templateFields = listOf(TemplateField("Paciente", "Persona de prueba")),
        )
        val names = MapperFixtures.additionalNames(MapperFixtures.xml(MapperFixtures.mappedOk("sparse", request)))

        assertEquals(listOf("Email", phoneName, "Paciente"), names)
    }

    @Test
    fun aBlankAddressIsAbsentNotAnEmptyEntry() {
        val request = MapperFixtures.request(buyer = MapperFixtures.buyer(address = "   ", phone = ""))
        val xml = MapperFixtures.xml(MapperFixtures.mappedOk("blank optional", request))

        assertEquals(listOf("Email"), MapperFixtures.additionalNames(xml))
    }

    @Test
    fun theSixteenthAdditionalEntryIsRefusedBeforeTheWriter() {
        val request = MapperFixtures.request(
            issuer = MapperFixtures.issuer(providerRuc = SyntheticIdentifiers.RUC),
            buyer = MapperFixtures.buyer(address = "Avenida Falsa 456", phone = "02 000 0000"),
            templateFields = templateFields(12),
        )

        assertEquals(MappingError.TooManyAdditionalFields(15, 16), MapperFixtures.refusal("sixteen entries", request))
    }

    // --- provider RUC (D-19) ------------------------------------------------------------------------

    @Test
    fun aProviderRucThatIsNotThirteenAsciiDigitsIsMalformed() {
        val tooShort = SyntheticIdentifiers.RUC.dropLast(1)
        val tooLong = SyntheticIdentifiers.RUC + "1"
        val withLetter = SyntheticIdentifiers.RUC.dropLast(1) + "A"
        val arabicIndic = "٩".repeat(13)
        for ((variant, value) in listOf(
            "too short" to tooShort,
            "too long" to tooLong,
            "letter" to withLetter,
            "arabic-indic digits" to arabicIndic,
            "empty" to "",
            "inner space" to (SyntheticIdentifiers.RUC.take(5) + " " + SyntheticIdentifiers.RUC.drop(5)),
        )) {
            val request = MapperFixtures.request(issuer = MapperFixtures.issuer(providerRuc = value))
            assertEquals(variant, MappingError.ProviderRucMalformed, MapperFixtures.refusal("provider ruc $variant", request))
        }
    }

    @Test
    fun aProviderRucWithSurroundingSpacesIsTrimmed() {
        val request = MapperFixtures.request(issuer = MapperFixtures.issuer(providerRuc = " ${SyntheticIdentifiers.RUC} "))
        val xml = MapperFixtures.xml(MapperFixtures.mappedOk("trimmed provider ruc", request))

        assertTrue(xml.contains("<campoAdicional nombre=\"RUC Proveedor\">${SyntheticIdentifiers.RUC}</campoAdicional>"))
    }

    // --- consumidor final (D-31) ---------------------------------------------------------------------

    @Test
    fun aConsumidorFinalInvoiceJustAboveFiftyIsRefused() {
        val request = MapperFixtures.request(
            buyer = MapperFixtures.consumidorFinalBuyer(),
            lines = listOf(ResolvedLine.lumpSum("CF-1", "Servicio de prueba", BigDecimal("50.01"), "0")),
        )

        assertEquals(
            MappingError.BuyerGuard(BuyerGuardError.ConsumidorFinalCapExceeded),
            MapperFixtures.refusal("consumidor final at 50.01", request),
        )
    }

    @Test
    fun theCapIsReadOnTheTotalWithIvaNotOnTheBase() {
        val request = MapperFixtures.request(
            buyer = MapperFixtures.consumidorFinalBuyer(),
            lines = listOf(ResolvedLine.lumpSum("CF-1", "Servicio de prueba", BigDecimal("45.00"), "4")),
        )

        assertEquals(
            MappingError.BuyerGuard(BuyerGuardError.ConsumidorFinalCapExceeded),
            MapperFixtures.refusal("consumidor final 45.00 plus iva", request),
        )
    }

    @Test
    fun buyerTypeSevenWithAnotherIdentificationIsRefused() {
        val request = MapperFixtures.request(
            buyer = MapperFixtures.buyer(idType = BuyerIdType.CONSUMIDOR_FINAL, identification = SyntheticIdNumbers.cedula(1)),
        )

        assertEquals(
            MappingError.ConsumidorFinalIdentificationMismatch,
            MapperFixtures.refusal("type 07 with a cedula", request),
        )
    }

    @Test
    fun theConsumidorFinalIdentificationUnderAnotherTypeIsRefusedWhateverTheTotal() {
        val anonymous = SyntheticIdNumbers.consumidorFinal()
        for (type in listOf(BuyerIdType.RUC, BuyerIdType.CEDULA, BuyerIdType.PASSPORT, BuyerIdType.FOREIGN)) {
            for (amount in listOf("10.00", "500.00")) {
                val request = MapperFixtures.request(
                    buyer = MapperFixtures.buyer(idType = type, identification = anonymous),
                    lines = listOf(ResolvedLine.lumpSum("LS-1", "Servicio de prueba", BigDecimal(amount), "0")),
                )

                assertEquals(
                    "type ${type.code} with the anonymous identification at $amount",
                    MappingError.ConsumidorFinalIdentificationMismatch,
                    MapperFixtures.refusal("anonymous identification under ${type.code} at $amount", request),
                )
            }
        }
    }

    @Test
    fun otherBuyerTypesAreNotCappedAtFifty() {
        val request = MapperFixtures.request(
            lines = listOf(ResolvedLine.lumpSum("LS-1", "Honorarios del mes", BigDecimal("500.00"), "0")),
        )

        XsdGate.validate(MapperFixtures.mappedOk("cedula above fifty", request).value.document.octets())
    }

    // --- sanitiser, preview marker, optional text (D-34, D-37) ---------------------------------------------

    @Test
    fun aDescriptionWithTheMarkerIsRefusedAsAnUnsupportedCharacter() {
        val request = MapperFixtures.request(lines = listOf(MapperFixtures.line(description = "Servicio $marker de prueba")))

        assertEquals(
            MappingError.Text(SanitizerError.UnsupportedCharacter("descripcion")),
            MapperFixtures.refusal("marker in description", request),
        )
    }

    @Test
    fun aTypedHorizontalEllipsisIsLegitimateTextAndIsWritten() {
        // Phone keyboards replace "..." with U+2026; it must not be mistaken for the preview marker.
        val request = MapperFixtures.request(
            buyer = MapperFixtures.buyer(legalName = "Test Customer\u2026 S.A."),
            lines = listOf(MapperFixtures.line(description = "Servicio\u2026 de prueba")),
        )
        val xml = String(MapperFixtures.mappedOk("typed ellipsis", request).value.document.octets(), Charsets.UTF_8)

        assertTrue("ellipsis in description", xml.contains("<descripcion>Servicio\u2026 de prueba</descripcion>"))
        assertTrue("ellipsis in buyer name", xml.contains("<razonSocialComprador>Test Customer\u2026 S.A.</razonSocialComprador>"))
    }

    @Test
    fun theMarkerIsRefusedInEveryTextFieldClassWithItsOwnFieldName() {
        val withMarker = "Texto $marker"
        val variants = listOf(
            "issuer legal name" to ("razonSocial" to MapperFixtures.request(issuer = MapperFixtures.issuer(legalName = withMarker))),
            "trade name" to ("nombreComercial" to MapperFixtures.request(issuer = MapperFixtures.issuer(tradeName = withMarker))),
            "buyer legal name" to ("razonSocialComprador" to MapperFixtures.request(buyer = MapperFixtures.buyer(legalName = withMarker))),
            "billing email" to ("campoAdicional" to MapperFixtures.request(buyer = MapperFixtures.buyer(billingEmail = withMarker))),
            "buyer address" to ("campoAdicional" to MapperFixtures.request(buyer = MapperFixtures.buyer(address = withMarker))),
            "buyer phone" to ("campoAdicional" to MapperFixtures.request(buyer = MapperFixtures.buyer(phone = withMarker))),
            "line code" to ("codigoPrincipal" to MapperFixtures.request(lines = listOf(MapperFixtures.line(code = withMarker)))),
            "template field name" to ("nombre" to MapperFixtures.request(templateFields = listOf(TemplateField(withMarker, "valor")))),
            "template field value" to ("campoAdicional" to MapperFixtures.request(templateFields = listOf(TemplateField("Campo", withMarker)))),
        )
        for ((variant, expected) in variants) {
            assertEquals(
                variant,
                MappingError.Text(SanitizerError.UnsupportedCharacter(expected.first)),
                MapperFixtures.refusal(variant, expected.second),
            )
        }
    }

    @Test
    fun theMarkerConstantIsAPrivateUseCodePoint() {
        assertEquals(marker, com.neoterux.easyfact.invoicing.placeholder.PlaceholderResolver.PENDING_SEQUENCE_MARKER)
    }

    @Test
    fun aTabAndALineFeedInADescriptionAreWrittenAsOneSpace() {
        val request = MapperFixtures.request(lines = listOf(MapperFixtures.line(description = "Servicio\tde\nprueba")))
        val mapped = MapperFixtures.mappedOk("tab and line feed", request)

        XsdGate.validate(mapped.value.document.octets())
        assertTrue(MapperFixtures.xml(mapped).contains("<descripcion>Servicio de prueba</descripcion>"))
    }

    @Test
    fun aBlankTradeNameWritesNoNombreComercialElement() {
        val request = MapperFixtures.request(issuer = MapperFixtures.issuer(tradeName = "   "))
        val mapped = MapperFixtures.mappedOk("blank trade name", request)

        XsdGate.validate(mapped.value.document.octets())
        assertFalse(MapperFixtures.xml(mapped).contains("nombreComercial"))
    }

    @Test
    fun aBlankLineCodeWritesNoCodigoPrincipalElement() {
        val request = MapperFixtures.request(lines = listOf(MapperFixtures.line(code = " ")))
        val mapped = MapperFixtures.mappedOk("blank line code", request)

        XsdGate.validate(mapped.value.document.octets())
        assertFalse(MapperFixtures.xml(mapped).contains("codigoPrincipal"))
    }

    @Test
    fun textsAreWrittenTrimmedAndCollapsed() {
        val request = MapperFixtures.request(buyer = MapperFixtures.buyer(legalName = "  Test   Customer  S.A. "))
        val mapped = MapperFixtures.mappedOk("collapsed spaces", request)

        assertTrue(MapperFixtures.xml(mapped).contains("<razonSocialComprador>Test Customer S.A.</razonSocialComprador>"))
    }

    @Test
    fun aTextAtItsLimitMapsAndOneAboveIsRefusedWithTheFacetLength() {
        val atLimit = MapperFixtures.request(lines = listOf(MapperFixtures.line(description = "D".repeat(300))))
        val overLimit = MapperFixtures.request(lines = listOf(MapperFixtures.line(description = "D".repeat(301))))

        XsdGate.validate(MapperFixtures.mappedOk("description at 300", atLimit).value.document.octets())
        assertEquals(
            MappingError.Text(SanitizerError.TooLong("descripcion", 300, 301)),
            MapperFixtures.refusal("description at 301", overLimit),
        )
    }

    @Test
    fun everyTextFieldUsesItsOwnFacetLimit() {
        val variants = listOf(
            Triple("issuer legal name", "razonSocial", MapperFixtures.request(issuer = MapperFixtures.issuer(legalName = "L".repeat(301)))) to 300,
            Triple("buyer legal name", "razonSocialComprador", MapperFixtures.request(buyer = MapperFixtures.buyer(legalName = "L".repeat(301)))) to 300,
            Triple("line code", "codigoPrincipal", MapperFixtures.request(lines = listOf(MapperFixtures.line(code = "C".repeat(26))))) to 25,
            Triple("buyer identification", "identificacionComprador", MapperFixtures.request(buyer = MapperFixtures.buyer(idType = BuyerIdType.PASSPORT, identification = "P".repeat(21)))) to 20,
            Triple("special taxpayer", "contribuyenteEspecial", MapperFixtures.request(issuer = MapperFixtures.issuer(specialTaxpayerNumber = "T".repeat(14)))) to 13,
        )
        for ((case, limit) in variants) {
            val (variant, field, request) = case
            val actual = limit + 1
            assertEquals(variant, MappingError.Text(SanitizerError.TooLong(field, limit, actual)), MapperFixtures.refusal(variant, request))
        }
    }

    @Test
    fun aCreditTermUnitIsSanitisedAndLimitedByItsOwnFacet() {
        val tooLong = MapperFixtures.request(
            payment = PaymentTerms(PaymentMethod.WITHOUT_FINANCIAL_SYSTEM, CreditTerm(BigDecimal("30"), "u".repeat(11))),
        )
        val blank = MapperFixtures.request(
            payment = PaymentTerms(PaymentMethod.WITHOUT_FINANCIAL_SYSTEM, CreditTerm(BigDecimal("30"), "  ")),
        )

        assertEquals(
            MappingError.Text(SanitizerError.TooLong("unidadTiempo", 10, 11)),
            MapperFixtures.refusal("credit unit too long", tooLong),
        )
        assertEquals(MappingError.Text(SanitizerError.Empty("unidadTiempo")), MapperFixtures.refusal("credit unit blank", blank))
    }

    @Test
    fun anEmptyMandatoryTextAndAControlCharacterAreRefusedWithTheirField() {
        val empty = MapperFixtures.request(lines = listOf(MapperFixtures.line(description = " ")))
        val control = MapperFixtures.request(buyer = MapperFixtures.buyer(legalName = "Test\u0007Customer"))

        assertEquals(MappingError.Text(SanitizerError.Empty("descripcion")), MapperFixtures.refusal("empty description", empty))
        assertEquals(
            MappingError.Text(SanitizerError.ControlCharacter("razonSocialComprador")),
            MapperFixtures.refusal("control character", control),
        )
    }

    @Test
    fun theWritersFacetsRemainTheBackstopForWhatTheSanitiserAccepts() {
        val request = MapperFixtures.request(issuer = MapperFixtures.issuer(specialTaxpayerNumber = "ab"))

        assertTrue(MapperFixtures.refusal("special taxpayer below its minimum", request) is MappingError.Writer)
    }

    // --- accents and environment (D-12, D-17) ------------------------------------------------------------

    @Test
    fun accentedTextIsWrittenAsLiteralUtf8() {
        val name = "Ñandú Cía. Médica"
        val mapped = MapperFixtures.mappedOk("accents", MapperFixtures.request(buyer = MapperFixtures.buyer(legalName = name)))
        val octets = mapped.value.document.octets()
        val asText = String(octets, Charsets.UTF_8)

        XsdGate.validate(octets)
        assertTrue(asText.contains("<razonSocialComprador>$name</razonSocialComprador>"))
        assertFalse(asText.contains("&aacute;"))
        assertFalse(asText.contains("&#"))
    }

    @Test
    fun theTestEnvironmentWritesTheRealBuyerDataUnchanged() {
        val request = MapperFixtures.request(environment = Environment.TEST)
        val xml = MapperFixtures.xml(MapperFixtures.mappedOk("test environment", request))

        assertTrue(xml.contains("<ambiente>1</ambiente>"))
        assertTrue(xml.contains("<razonSocialComprador>${SyntheticIdentifiers.CUSTOMER_NAME}</razonSocialComprador>"))
        assertTrue(xml.contains("<identificacionComprador>${SyntheticIdNumbers.cedula(1)}</identificacionComprador>"))
        assertFalse(xml.contains("PRUEBAS SERVICIO"))
    }

    // --- what the mapping never produces (D-14, D-15, D-16) -----------------------------------------------

    @Test
    fun theMappingNeverProducesTheUnsupportedBlocks() {
        val request = MapperFixtures.request(
            issuer = MapperFixtures.issuer(rimpeLegend = RimpeLegend.EMPRENDEDOR, providerRuc = SyntheticIdentifiers.RUC),
            buyer = MapperFixtures.buyer(address = "Avenida Falsa 456", phone = "02 000 0000"),
            templateFields = templateFields(2),
        )
        val xml = MapperFixtures.xml(MapperFixtures.mappedOk("unsupported blocks", request))
        val never = listOf(
            "reembolsos", "retenciones", "comercioExterior", "totalSubsidio", "compensaciones", "maquinaFiscal",
            "tipoNegociable", "codigoAuxiliar", "unidadMedida", "detallesAdicionales", "direccionComprador",
        )

        for (name in never) assertFalse("element $name appears", xml.contains("<$name"))
    }

    @Test
    fun theEstablishmentAddressIsTheHeadOfficeAddress() {
        val xml = MapperFixtures.xml(MapperFixtures.mappedOk("establishment address", MapperFixtures.request()))

        assertTrue(xml.contains("<dirMatriz>${MapperFixtures.DEFAULT_ADDRESS}</dirMatriz>"))
        assertTrue(xml.contains("<dirEstablecimiento>${MapperFixtures.DEFAULT_ADDRESS}</dirEstablecimiento>"))
    }

    // --- order of the refusals ---------------------------------------------------------------------------------

    @Test
    fun aTextRefusalComesBeforeAProviderRucRefusal() {
        val request = MapperFixtures.request(
            issuer = MapperFixtures.issuer(providerRuc = "not digits"),
            lines = listOf(MapperFixtures.line(description = " ")),
        )

        assertEquals(MappingError.Text(SanitizerError.Empty("descripcion")), MapperFixtures.refusal("text before provider", request))
    }

    @Test
    fun aProviderRucRefusalComesBeforeAnArithmeticRefusal() {
        val request = MapperFixtures.request(
            issuer = MapperFixtures.issuer(providerRuc = "not digits"),
            lines = listOf(MapperFixtures.line(ivaRateCode = "99")),
        )

        assertEquals(MappingError.ProviderRucMalformed, MapperFixtures.refusal("provider before totals", request))
    }

    @Test
    fun anArithmeticRefusalComesBeforeAConsumidorFinalRefusal() {
        val request = MapperFixtures.request(
            buyer = MapperFixtures.buyer(idType = BuyerIdType.CONSUMIDOR_FINAL, identification = SyntheticIdNumbers.cedula(1)),
            lines = listOf(MapperFixtures.line(ivaRateCode = "99")),
        )

        assertEquals(
            MappingError.Totals(TotalsError.UnknownIvaCode(0, "99")),
            MapperFixtures.refusal("totals before consumidor final", request),
        )
    }

    @Test
    fun theIdentificationMismatchComesBeforeTheCapCheck() {
        val request = MapperFixtures.request(
            buyer = MapperFixtures.buyer(idType = BuyerIdType.CONSUMIDOR_FINAL, identification = SyntheticIdNumbers.cedula(1)),
            lines = listOf(ResolvedLine.lumpSum("CF-1", "Servicio de prueba", BigDecimal("500.00"), "0")),
        )

        assertEquals(
            MappingError.ConsumidorFinalIdentificationMismatch,
            MapperFixtures.refusal("mismatch before cap", request),
        )
    }

    // --- NFR-04: nothing a user typed reaches a diagnostic ------------------------------------------------------

    @Test
    fun noRefusalRendersTheTextThatCausedIt() {
        val secret = "ZZsecretZZ"
        val requests = listOf(
            MapperFixtures.request(lines = listOf(MapperFixtures.line(description = secret.repeat(40)))),
            MapperFixtures.request(lines = listOf(MapperFixtures.line(description = "$secret $marker"))),
            MapperFixtures.request(buyer = MapperFixtures.buyer(legalName = "$secret\u0007")),
            MapperFixtures.request(issuer = MapperFixtures.issuer(providerRuc = secret)),
            MapperFixtures.request(templateFields = (1..16).map { TemplateField("$secret $it", secret) }),
            MapperFixtures.request(
                buyer = MapperFixtures.buyer(idType = BuyerIdType.CONSUMIDOR_FINAL, identification = secret),
            ),
        )

        for ((index, request) in requests.withIndex()) {
            val rendered = MapperFixtures.refusal("refusal $index", request).toString()
            assertFalse("refusal $index renders typed text", rendered.contains(secret))
        }
    }

    @Test
    fun aMappedInvoiceAndItsComputedWrapperRenderNoDocumentContent() {
        val mapped = MapperFixtures.mappedOk("rendering", MapperFixtures.request())

        assertEquals("MappedInvoice(redacted)", mapped.value.toString())
        assertFalse(mapped.toString().contains(SyntheticIdentifiers.CUSTOMER_NAME))
        assertFalse(mapped.toString().contains(SyntheticIdentifiers.RUC))
        assertNotNull(mapped.value.document)
    }
}
