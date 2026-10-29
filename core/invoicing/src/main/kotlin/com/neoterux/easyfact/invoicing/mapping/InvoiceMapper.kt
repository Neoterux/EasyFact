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
import com.neoterux.easyfact.invoicing.identification.IdentificationValidator
import com.neoterux.easyfact.invoicing.placeholder.PlaceholderResolver
import com.neoterux.easyfact.invoicing.result.Computed
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.text.SanitizerError
import com.neoterux.easyfact.invoicing.text.TextSanitizer
import com.neoterux.easyfact.invoicing.totals.BuyerGuardError
import com.neoterux.easyfact.invoicing.totals.ConsumidorFinalGuard
import com.neoterux.easyfact.invoicing.totals.InvoiceCalculator
import com.neoterux.easyfact.invoicing.totals.InvoiceTotals
import com.neoterux.easyfact.invoicing.totals.LineInput
import com.neoterux.easyfact.invoicing.totals.TotalsError
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import java.math.BigDecimal

/**
 * What [InvoiceMapper.map] produces: the model, the canonical bytes written from it and the arithmetic behind both.
 *
 * `toString()` renders no field: the model holds the issuer's RUC, names and the buyer's identification, and a
 * mapped invoice reaches logs and crash reports through `Computed`. The nested [Factura] and its blocks
 * redact themselves too, so a caller that logs `factura` directly is covered as well.
 */
data class MappedInvoice(
    val factura: Factura,
    val document: CanonicalDocument,
    val totals: InvoiceTotals,
) {
    override fun toString(): String = "MappedInvoice(redacted)"
}

/**
 * Why an [InvoiceRequest] could not be mapped.
 *
 * Every variant is value-free (NFR-04): it names a field, a count or a protocol code and never the
 * text, identification or amount the user or the customer supplied, because errors reach diagnostics.
 */
sealed interface MappingError {

    /** The arithmetic refused the lines. */
    data class Totals(val error: TotalsError) : MappingError

    /** The writer refused the mapped [Factura]. */
    data class Writer(val error: FacturaError) : MappingError

    /** The sanitiser refused a text; [error] names the field and, for a length, the two lengths. */
    data class Text(val error: SanitizerError) : MappingError

    /** A buyer-dependent rule refused the invoice (the consumidor-final cap). */
    data class BuyerGuard(val error: BuyerGuardError) : MappingError

    /** `infoAdicional` would hold [actualEntries] entries, more than the schema's [maxEntries]. */
    data class TooManyAdditionalFields(val maxEntries: Int, val actualEntries: Int) : MappingError

    /** A text destined for [field] holds the preview marker of an unassigned sequential (D-37). */
    data class PreviewMarkerInDocument(val field: String) : MappingError

    /** The issuer's provider RUC is set but is not exactly thirteen ASCII digits (D-19). */
    data object ProviderRucMalformed : MappingError

    /**
     * The buyer type and the identification disagree about consumidor final (D-31): the type is consumidor
     * final but the identification is not the consumidor-final one, or the identification is the
     * consumidor-final one under another type.
     */
    data object ConsumidorFinalIdentificationMismatch : MappingError
}

/**
 * Maps engine input onto Phase 2's [Factura] and [FacturaWriter] (SRI-01).
 *
 * The mapping is plain and total over what it supports (D-14): `infoTributaria` with the RIMPE legend and the
 * withholding-agent resolution when the issuer has them, `infoFactura` with its totals, `totalConImpuestos`,
 * `pagos`, `propina` 0.00 and `totalDescuento` 0.00, the `detalles`, and `infoAdicional`. It never sets
 * refunds, withholdings, export fields, subsidy, compensations, negotiable email, fiscal machine, auxiliary
 * codes, units of measure or line details, although the writer supports them. `direccionComprador` stays
 * absent because the buyer's address travels in `infoAdicional` (D-18, research assumption A12).
 *
 * **Order of the refusals; the first failure wins.** Every document text is sanitised, with the limit read
 * from the writer's facet table, and refused if it holds the preview marker; then the provider RUC is
 * checked; then the arithmetic runs; then, for a consumidor-final buyer, the identification and the cap;
 * then `infoAdicional` is assembled and counted. All of that runs before [FacturaWriter], so the user sees a
 * specific engine refusal, and the writer's own facets remain the backstop behind it.
 *
 * **The access key is an input.** [InvoiceRequest.accessKey] was derived once at allocation and
 * persisted; this mapper never derives or recomputes one (D-11). The writer additionally refuses a key
 * that disagrees with the document it is written into.
 *
 * **The environment never alters buyer data.** The test environment writes the real buyer name and
 * identification; nothing here substitutes a placeholder for them (D-17).
 *
 * Field names such as `Email`, `Dirección`, `Teléfono` and `RUC Proveedor` are document content, not source
 * identifiers, and so are the permitted exception to the English-only rule (AGENTS.md section 2.1), exactly
 * like the SRI's element names.
 */
object InvoiceMapper {

    private const val EMISSION_TYPE_NORMAL = "1"
    private const val DOCUMENT_TYPE_FACTURA = "01"
    private const val CURRENCY = "DOLAR"
    private const val SEQUENTIAL_DIGITS = 9
    private const val PROVIDER_RUC_DIGITS = 13
    private val ZERO_AMOUNT: BigDecimal = BigDecimal.ZERO.setScale(2)

    // The names of the four fixed `infoAdicional` entries (D-18, D-19): document content.
    private const val EMAIL_FIELD = "Email"
    private const val ADDRESS_FIELD = "Dirección"
    private const val PHONE_FIELD = "Teléfono"
    private const val PROVIDER_RUC_FIELD = "RUC Proveedor"

    // The SRI element or attribute names handed to the sanitiser as the `field` of a refusal.
    private const val RAZON_SOCIAL = "razonSocial"
    private const val NOMBRE_COMERCIAL = "nombreComercial"
    private const val DIR_MATRIZ = "dirMatriz"
    private const val DIR_ESTABLECIMIENTO = "dirEstablecimiento"
    private const val CONTRIBUYENTE_ESPECIAL = "contribuyenteEspecial"
    private const val RAZON_SOCIAL_COMPRADOR = "razonSocialComprador"
    private const val IDENTIFICACION_COMPRADOR = "identificacionComprador"
    private const val CAMPO_ADICIONAL = "campoAdicional"
    private const val CAMPO_ADICIONAL_NOMBRE = "nombre"
    private const val CODIGO_PRINCIPAL = "codigoPrincipal"
    private const val DESCRIPCION = "descripcion"
    private const val UNIDAD_TIEMPO = "unidadTiempo"

    /**
     * The schema's repeat cap for `campoAdicional`, read from the writer's facet table. `null` would mean the
     * facet has no cap, in which case only the writer's own check applies.
     */
    private val ADDITIONAL_FIELD_CAP: Int? = FieldLimits.facet(FieldLimits.ADDITIONAL_FIELD)?.maxOccurs

    /** Every text of the request once cleaned, so that the mapping below reads one consistent set. */
    private class CleanText(
        val issuerLegalName: String,
        val issuerTradeName: String?,
        val headOfficeAddress: String,
        val establishmentAddress: String,
        val specialTaxpayerNumber: String?,
        val buyerLegalName: String,
        val buyerIdentification: String,
        val billingEmail: String,
        val buyerAddress: String?,
        val buyerPhone: String?,
        val lineCodes: List<String?>,
        val lineDescriptions: List<String>,
        val templateFields: List<AdditionalField>,
        val creditTermUnit: String?,
    )

    /** Maps [request], or says why the engine or the writer refused it. */
    fun map(request: InvoiceRequest): Outcome<Computed<MappedInvoice>, MappingError> {
        val text = when (val cleaned = sanitise(request)) {
            is Outcome.Ok -> cleaned.value
            is Outcome.Err -> return Outcome.Err(cleaned.error)
        }
        val providerRuc = when (val checked = providerRuc(request.issuer.providerRuc)) {
            is Outcome.Ok -> checked.value
            is Outcome.Err -> return Outcome.Err(checked.error)
        }
        val computed = when (
            val outcome = InvoiceCalculator.calculate(
                request.lines.map { LineInput(it.quantity, it.unitPrice, it.ivaRateCode) },
            )
        ) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> return Outcome.Err(MappingError.Totals(outcome.error))
        }
        val totals = computed.value

        // D-31: an anonymous buyer is only the consumidor-final identification, and only up to the cap. The
        // identification is the discriminator as much as the type: thirteen nines under any other type is
        // the same anonymous buyer, so it is refused rather than allowed to bypass the cap.
        val anonymousType = request.buyer.idType == BuyerIdType.CONSUMIDOR_FINAL
        val anonymousIdentification = IdentificationValidator.isConsumidorFinal(text.buyerIdentification)
        if (anonymousType != anonymousIdentification) {
            return Outcome.Err(MappingError.ConsumidorFinalIdentificationMismatch)
        }
        if (anonymousType) {
            when (val guard = ConsumidorFinalGuard.check(totals.grandTotal)) {
                is Outcome.Ok -> Unit
                is Outcome.Err -> return Outcome.Err(MappingError.BuyerGuard(guard.error))
            }
        }

        val additionalFields = additionalFields(text, providerRuc)
        val cap = ADDITIONAL_FIELD_CAP
        if (cap != null && additionalFields.size > cap) {
            return Outcome.Err(MappingError.TooManyAdditionalFields(cap, additionalFields.size))
        }

        val factura = Factura(
            taxInfo = taxInfo(request, text),
            invoiceInfo = invoiceInfo(request, text, totals),
            lines = request.lines.indices.map { index ->
                val calculated = totals.lines[index]
                InvoiceLine(
                    mainCode = text.lineCodes[index],
                    description = text.lineDescriptions[index],
                    quantity = calculated.quantity,
                    unitPrice = calculated.unitPrice,
                    discount = ZERO_AMOUNT,
                    totalWithoutTaxes = calculated.base,
                    taxes = listOf(calculated.tax),
                )
            },
            additionalFields = additionalFields,
        )

        return when (val written = FacturaWriter.write(factura)) {
            is Outcome.Ok -> Outcome.Ok(Computed(MappedInvoice(factura, written.value, totals), computed.warnings))
            is Outcome.Err -> Outcome.Err(MappingError.Writer(written.error))
        }
    }

    /**
     * Cleans every document text with the limit of its own facet (D-34), and refuses any that holds the
     * preview marker (D-37). The first refusal wins, in document order.
     */
    private fun sanitise(request: InvoiceRequest): Outcome<CleanText, MappingError> {
        val issuer = request.issuer
        val buyer = request.buyer

        val issuerLegalName = TextSanitizer.required(
            RAZON_SOCIAL, issuer.legalName, FieldLimits.maxLength(FieldLimits.ISSUER_LEGAL_NAME),
        ).orRefuse(RAZON_SOCIAL) { return Outcome.Err(it) }
        val issuerTradeName = TextSanitizer.optional(
            NOMBRE_COMERCIAL, issuer.tradeName, FieldLimits.maxLength(FieldLimits.ISSUER_TRADE_NAME),
        ).orRefuse(NOMBRE_COMERCIAL) { return Outcome.Err(it) }
        val headOfficeAddress = TextSanitizer.required(
            DIR_MATRIZ, issuer.headOfficeAddress, FieldLimits.maxLength(FieldLimits.HEAD_OFFICE_ADDRESS),
        ).orRefuse(DIR_MATRIZ) { return Outcome.Err(it) }
        // D-15: one establishment, so the same address is cleaned again with `dirEstablecimiento`'s own limit.
        val establishmentAddress = TextSanitizer.required(
            DIR_ESTABLECIMIENTO, issuer.headOfficeAddress, FieldLimits.maxLength(FieldLimits.ESTABLISHMENT_ADDRESS),
        ).orRefuse(DIR_ESTABLECIMIENTO) { return Outcome.Err(it) }
        val specialTaxpayerNumber = TextSanitizer.optional(
            CONTRIBUYENTE_ESPECIAL, issuer.specialTaxpayerNumber, FieldLimits.maxLength(FieldLimits.SPECIAL_TAXPAYER),
        ).orRefuse(CONTRIBUYENTE_ESPECIAL) { return Outcome.Err(it) }
        val buyerLegalName = TextSanitizer.required(
            RAZON_SOCIAL_COMPRADOR, buyer.legalName, FieldLimits.maxLength(FieldLimits.BUYER_LEGAL_NAME),
        ).orRefuse(RAZON_SOCIAL_COMPRADOR) { return Outcome.Err(it) }
        val buyerIdentification = TextSanitizer.required(
            IDENTIFICACION_COMPRADOR, buyer.identification, FieldLimits.maxLength(FieldLimits.BUYER_IDENTIFICATION),
        ).orRefuse(IDENTIFICACION_COMPRADOR) { return Outcome.Err(it) }
        val billingEmail = TextSanitizer.required(
            CAMPO_ADICIONAL, buyer.billingEmail, FieldLimits.maxLength(FieldLimits.ADDITIONAL_FIELD),
        ).orRefuse(CAMPO_ADICIONAL) { return Outcome.Err(it) }
        val buyerAddress = TextSanitizer.optional(
            CAMPO_ADICIONAL, buyer.address, FieldLimits.maxLength(FieldLimits.ADDITIONAL_FIELD),
        ).orRefuse(CAMPO_ADICIONAL) { return Outcome.Err(it) }
        val buyerPhone = TextSanitizer.optional(
            CAMPO_ADICIONAL, buyer.phone, FieldLimits.maxLength(FieldLimits.ADDITIONAL_FIELD),
        ).orRefuse(CAMPO_ADICIONAL) { return Outcome.Err(it) }

        val lineCodes = ArrayList<String?>(request.lines.size)
        val lineDescriptions = ArrayList<String>(request.lines.size)
        for (line in request.lines) {
            lineCodes += TextSanitizer.optional(
                CODIGO_PRINCIPAL, line.code, FieldLimits.maxLength(FieldLimits.LINE_CODE),
            ).orRefuse(CODIGO_PRINCIPAL) { return Outcome.Err(it) }
            lineDescriptions += TextSanitizer.required(
                DESCRIPCION, line.description, FieldLimits.maxLength(FieldLimits.LINE_DESCRIPTION),
            ).orRefuse(DESCRIPCION) { return Outcome.Err(it) }
        }

        val templateFields = ArrayList<AdditionalField>(request.templateFields.size)
        for (field in request.templateFields) {
            val name = TextSanitizer.required(
                CAMPO_ADICIONAL_NOMBRE, field.name, FieldLimits.maxLength(FieldLimits.ADDITIONAL_FIELD_NAME),
            ).orRefuse(CAMPO_ADICIONAL_NOMBRE) { return Outcome.Err(it) }
            val value = TextSanitizer.required(
                CAMPO_ADICIONAL, field.value, FieldLimits.maxLength(FieldLimits.ADDITIONAL_FIELD),
            ).orRefuse(CAMPO_ADICIONAL) { return Outcome.Err(it) }
            templateFields += AdditionalField(name, value)
        }

        val creditTermUnit = request.payment.creditTerm?.let { term ->
            TextSanitizer.required(UNIDAD_TIEMPO, term.unit, FieldLimits.maxLength(FieldLimits.CREDIT_TERM_UNIT))
                .orRefuse(UNIDAD_TIEMPO) { return Outcome.Err(it) }
        }

        return Outcome.Ok(
            CleanText(
                issuerLegalName = issuerLegalName,
                issuerTradeName = issuerTradeName,
                headOfficeAddress = headOfficeAddress,
                establishmentAddress = establishmentAddress,
                specialTaxpayerNumber = specialTaxpayerNumber,
                buyerLegalName = buyerLegalName,
                buyerIdentification = buyerIdentification,
                billingEmail = billingEmail,
                buyerAddress = buyerAddress,
                buyerPhone = buyerPhone,
                lineCodes = lineCodes,
                lineDescriptions = lineDescriptions,
                templateFields = templateFields,
                creditTermUnit = creditTermUnit,
            ),
        )
    }

    /**
     * Turns a sanitiser result into the cleaned text, or hands the refusal to [onRefused], which returns
     * from the caller. A text that still holds the preview marker is refused as [MappingError.PreviewMarkerInDocument]
     * for [field] (D-37): the marker means a sequential that does not exist yet, and a signed document must
     * never carry it.
     */
    private inline fun <T : String?> Outcome<T, SanitizerError>.orRefuse(
        field: String,
        onRefused: (MappingError) -> Nothing,
    ): T = when (this) {
        is Outcome.Err -> onRefused(MappingError.Text(error))
        is Outcome.Ok -> {
            val cleaned: String? = value
            if (cleaned != null && cleaned.contains(PlaceholderResolver.PENDING_SEQUENCE_MARKER)) {
                onRefused(MappingError.PreviewMarkerInDocument(field))
            }
            value
        }
    }

    /** The trimmed provider RUC, `null` when it is off, or [MappingError.ProviderRucMalformed] (D-19). */
    private fun providerRuc(raw: String?): Outcome<String?, MappingError> {
        if (raw == null) return Outcome.Ok(null)
        val trimmed = raw.trim()
        // A character range, never Character.isDigit: that also accepts Arabic-Indic and fullwidth digits.
        val valid = trimmed.length == PROVIDER_RUC_DIGITS && trimmed.all { it in '0'..'9' }
        return if (valid) Outcome.Ok(trimmed) else Outcome.Err(MappingError.ProviderRucMalformed)
    }

    /** `infoAdicional` in the decided order (D-18, D-19): email, address, phone, provider RUC, template fields. */
    private fun additionalFields(text: CleanText, providerRuc: String?): List<AdditionalField> {
        val fields = ArrayList<AdditionalField>()
        fields += AdditionalField(EMAIL_FIELD, text.billingEmail)
        text.buyerAddress?.let { fields += AdditionalField(ADDRESS_FIELD, it) }
        text.buyerPhone?.let { fields += AdditionalField(PHONE_FIELD, it) }
        providerRuc?.let { fields += AdditionalField(PROVIDER_RUC_FIELD, it) }
        fields += text.templateFields
        return fields
    }

    private fun taxInfo(request: InvoiceRequest, text: CleanText): TaxInfo = TaxInfo(
        environmentCode = request.environment.code,
        emissionTypeCode = EMISSION_TYPE_NORMAL,
        legalName = text.issuerLegalName,
        tradeName = text.issuerTradeName,
        ruc = request.issuer.ruc,
        accessKey = request.accessKey,
        documentTypeCode = DOCUMENT_TYPE_FACTURA,
        establishmentCode = request.issuer.establishment,
        emissionPointCode = request.issuer.emissionPoint,
        sequential = request.sequential.toString().padStart(SEQUENTIAL_DIGITS, '0'),
        headOfficeAddress = text.headOfficeAddress,
        // D-20: typed values, already in the form the SRI wants.
        withholdingAgentResolution = request.issuer.withholdingAgent?.number,
        rimpeRegimeLegend = request.issuer.rimpeLegend.documentText,
    )

    private fun invoiceInfo(request: InvoiceRequest, text: CleanText, totals: InvoiceTotals): InvoiceInfo = InvoiceInfo(
        issueDate = request.issueDate,
        // D-15: a single establishment, so the establishment address is the head-office address.
        establishmentAddress = text.establishmentAddress,
        specialTaxpayerNumber = text.specialTaxpayerNumber,
        accountingRequired = request.issuer.accountingRequired,
        buyerIdentificationTypeCode = request.buyer.idType.code,
        buyerLegalName = text.buyerLegalName,
        buyerIdentification = text.buyerIdentification,
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
                termUnit = text.creditTermUnit,
            ),
        ),
    )
}
