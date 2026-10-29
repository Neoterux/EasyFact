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

package com.neoterux.easyfact.invoicing.catalogue

import com.neoterux.easyfact.invoicing.accesskey.AccessKeyError
import com.neoterux.easyfact.invoicing.factura.FacturaError
import com.neoterux.easyfact.invoicing.identification.IdentificationVerdict
import com.neoterux.easyfact.invoicing.mapping.MappingError
import com.neoterux.easyfact.invoicing.mapping.WithholdingAgentError
import com.neoterux.easyfact.invoicing.result.EngineWarning
import com.neoterux.easyfact.invoicing.result.SigningError
import com.neoterux.easyfact.invoicing.signing.RehydrationError
import com.neoterux.easyfact.invoicing.soap.ResponseProblem
import com.neoterux.easyfact.invoicing.soap.SriMessage
import com.neoterux.easyfact.invoicing.text.SanitizerError
import com.neoterux.easyfact.invoicing.totals.BuyerGuardError
import com.neoterux.easyfact.invoicing.totals.TotalsError
import java.util.Locale

/** How serious a received SRI message is for the user (SRI-08). */
enum class Severity {
    /** A rejection, or a message whose seriousness cannot be told, which is treated as one. */
    ERROR,

    /** A warning that accompanies an operation that went ahead; never a rejection. */
    WARNING,
}

/**
 * A received SRI message beside its catalogue [entry] and [severity] (SRI-13).
 *
 * The raw [message] is kept unchanged, so the identifier, the text, `informacionAdicional` and the tipo the SRI
 * sent stay available next to the explanation (SC5).
 */
data class ExplainedMessage(
    val entry: CatalogueEntry,
    val message: SriMessage,
    val severity: Severity,
)

/**
 * Maps every typed failure of the engine, and every message of the SRI, onto one [CatalogueEntry] (D-04, D-09).
 *
 * **Completeness is a compile-time property.** Each `entryFor` is a `when` expression over a sealed type with no
 * else branch, so adding a variant anywhere in the engine fails compilation here until it is catalogued
 * (threat T-03-21). The test suite additionally lists every variant of every family, and plan 03-10's `:app`
 * test checks every entry against `strings.xml`. Failures that only an engine bug can cause share
 * [CatalogueEntry.ENGINE_BUG] (D-09).
 *
 * Nothing here carries user text: an entry names a resource, and the typed errors it reads carry no values.
 */
object ErrorCatalogue {

    private const val TIPO_ERROR = "ERROR"
    private const val TIPO_WARNING = "ADVERTENCIA"
    private val WHITESPACE = Regex("\\s+")

    /** The entry for a document-writer refusal. */
    fun entryFor(error: FacturaError): CatalogueEntry = when (error) {
        is FacturaError.FieldTooLong -> CatalogueEntry.TEXT_TOO_LONG
        is FacturaError.FieldTooShort -> CatalogueEntry.TEXT_EMPTY
        is FacturaError.BlankField -> CatalogueEntry.TEXT_EMPTY
        is FacturaError.IllegalCharacter -> CatalogueEntry.TEXT_CONTROL_CHARACTER
        is FacturaError.DecimalOutOfRange -> CatalogueEntry.AMOUNT_OUT_OF_RANGE
        is FacturaError.InvalidFormat -> CatalogueEntry.FIELD_INVALID_FORMAT
        is FacturaError.TooManyEntries -> CatalogueEntry.TOO_MANY_ADDITIONAL_FIELDS
        is FacturaError.CodeOutsideCatalogue -> CatalogueEntry.ENGINE_BUG
        is FacturaError.EmptyCollection -> CatalogueEntry.ENGINE_BUG
        is FacturaError.AccessKeyMismatch -> CatalogueEntry.ENGINE_BUG
    }

    /** The entry for an access-key failure: only an engine bug can cause one. */
    fun entryFor(error: AccessKeyError): CatalogueEntry = when (error) {
        is AccessKeyError.SegmentNotNumeric -> CatalogueEntry.ENGINE_BUG
        is AccessKeyError.SegmentWrongLength -> CatalogueEntry.ENGINE_BUG
        is AccessKeyError.SequentialOutOfRange -> CatalogueEntry.ENGINE_BUG
    }

    /** The entry for a failure to re-wrap stored signed bytes: only an engine bug can cause one. */
    fun entryFor(error: RehydrationError): CatalogueEntry = when (error) {
        is RehydrationError.DigestMismatch -> CatalogueEntry.ENGINE_BUG
        is RehydrationError.EmptyOctets -> CatalogueEntry.ENGINE_BUG
    }

    /** The entry for a signing failure. */
    fun entryFor(error: SigningError): CatalogueEntry = when (error) {
        is SigningError.KeyNotRsa2048 -> CatalogueEntry.CERTIFICATE_KEY_UNSUPPORTED
        is SigningError.CertificateNotValidAtSigningTime -> CatalogueEntry.CERTIFICATE_NOT_VALID_NOW
        is SigningError.KeystoreUnlockFailed -> CatalogueEntry.CERTIFICATE_UNLOCK_FAILED
        is SigningError.PrivateKeyDoesNotMatchCertificate -> CatalogueEntry.CERTIFICATE_KEY_MISMATCH
        is SigningError.InputNotCanonical -> CatalogueEntry.ENGINE_BUG
        is SigningError.SigningFailed -> CatalogueEntry.ENGINE_BUG
    }

    /** The entry for an arithmetic refusal. */
    fun entryFor(error: TotalsError): CatalogueEntry = when (error) {
        is TotalsError.NoLines -> CatalogueEntry.NO_LINES
        is TotalsError.NegativeQuantity -> CatalogueEntry.NEGATIVE_QUANTITY
        is TotalsError.NegativeUnitPrice -> CatalogueEntry.NEGATIVE_UNIT_PRICE
        is TotalsError.UnknownIvaCode -> CatalogueEntry.UNKNOWN_IVA_CODE
        is TotalsError.IvaCodeNotComputable -> CatalogueEntry.IVA_CODE_NOT_COMPUTABLE
    }

    /** The entry for a buyer-dependent refusal. */
    fun entryFor(error: BuyerGuardError): CatalogueEntry = when (error) {
        is BuyerGuardError.ConsumidorFinalCapExceeded -> CatalogueEntry.CONSUMIDOR_FINAL_CAP_EXCEEDED
    }

    /** The entry for a text-sanitiser refusal. */
    fun entryFor(error: SanitizerError): CatalogueEntry = when (error) {
        is SanitizerError.ControlCharacter -> CatalogueEntry.TEXT_CONTROL_CHARACTER
        is SanitizerError.UnsupportedCharacter -> CatalogueEntry.TEXT_UNSUPPORTED_CHARACTER
        is SanitizerError.Empty -> CatalogueEntry.TEXT_EMPTY
        is SanitizerError.TooLong -> CatalogueEntry.TEXT_TOO_LONG
    }

    /** The entry for a mapping refusal; a wrapping variant resolves to the entry of the error it wraps. */
    fun entryFor(error: MappingError): CatalogueEntry = when (error) {
        is MappingError.Totals -> entryFor(error.error)
        is MappingError.Writer -> entryFor(error.error)
        is MappingError.Text -> entryFor(error.error)
        is MappingError.BuyerGuard -> entryFor(error.error)
        is MappingError.TooManyAdditionalFields -> CatalogueEntry.TOO_MANY_ADDITIONAL_FIELDS
        is MappingError.PreviewMarkerInDocument -> CatalogueEntry.PREVIEW_MARKER_IN_DOCUMENT
        is MappingError.ProviderRucMalformed -> CatalogueEntry.PROVIDER_RUC_MALFORMED
        is MappingError.ConsumidorFinalIdentificationMismatch -> CatalogueEntry.CONSUMIDOR_FINAL_IDENTIFICATION_MISMATCH
    }

    /** The entry for a withholding-agent resolution number the issuer profile refused. */
    fun entryFor(error: WithholdingAgentError): CatalogueEntry = when (error) {
        is WithholdingAgentError.Empty -> CatalogueEntry.WITHHOLDING_AGENT_INVALID
        is WithholdingAgentError.NotNumeric -> CatalogueEntry.WITHHOLDING_AGENT_INVALID
        is WithholdingAgentError.TooLong -> CatalogueEntry.WITHHOLDING_AGENT_INVALID
    }

    /**
     * The entry for a buyer-identification verdict, or `null` when the verdict is not a fault ([IdentificationVerdict.Valid]
     * and [IdentificationVerdict.NoCheckDigit]).
     */
    fun entryFor(verdict: IdentificationVerdict): CatalogueEntry? = when (verdict) {
        is IdentificationVerdict.Valid -> null
        is IdentificationVerdict.NoCheckDigit -> null
        is IdentificationVerdict.Blank -> CatalogueEntry.IDENTIFICATION_MALFORMED
        is IdentificationVerdict.TooLong -> CatalogueEntry.IDENTIFICATION_MALFORMED
        is IdentificationVerdict.WrongLength -> CatalogueEntry.IDENTIFICATION_MALFORMED
        is IdentificationVerdict.NotNumeric -> CatalogueEntry.IDENTIFICATION_MALFORMED
        is IdentificationVerdict.BadProvince -> CatalogueEntry.IDENTIFICATION_MALFORMED
        is IdentificationVerdict.BadThirdDigit -> CatalogueEntry.IDENTIFICATION_MALFORMED
        is IdentificationVerdict.BadCheckDigit -> CatalogueEntry.IDENTIFICATION_CHECK_DIGIT
        is IdentificationVerdict.BadEstablishmentSuffix -> CatalogueEntry.IDENTIFICATION_MALFORMED
        is IdentificationVerdict.NotConsumidorFinal -> CatalogueEntry.IDENTIFICATION_NOT_CONSUMIDOR_FINAL
        is IdentificationVerdict.UnknownType -> CatalogueEntry.IDENTIFICATION_MALFORMED
    }

    /** The entry for a non-blocking engine warning. */
    fun entryFor(warning: EngineWarning): CatalogueEntry = when (warning) {
        is EngineWarning.ZeroQuantity -> CatalogueEntry.WARNING_ZERO_QUANTITY
        is EngineWarning.ZeroLineTotal -> CatalogueEntry.WARNING_ZERO_LINE_TOTAL
        is EngineWarning.ZeroInvoiceTotal -> CatalogueEntry.WARNING_ZERO_INVOICE_TOTAL
        is EngineWarning.SequenceAssignedAtIssue -> CatalogueEntry.WARNING_SEQUENCE_ASSIGNED_AT_ISSUE
        is EngineWarning.UnrecognisedToken -> CatalogueEntry.WARNING_UNRECOGNISED_TOKEN
    }

    /**
     * The entry for an answer that could not be read. Each is transport-class: the outcome of the submission is
     * unknown, so the status is reconciled first and the document is never treated as rejected (D-44).
     */
    fun entryFor(problem: ResponseProblem): CatalogueEntry = when (problem) {
        is ResponseProblem.SoapFault -> CatalogueEntry.RESPONSE_SOAP_FAULT
        is ResponseProblem.UnexpectedBody -> CatalogueEntry.RESPONSE_UNEXPECTED
        is ResponseProblem.Malformed -> CatalogueEntry.RESPONSE_MALFORMED
    }

    /**
     * Explains one SRI [message] (SRI-13). The raw message is kept unchanged inside the result.
     *
     * The identifier is trimmed and must then be non-empty ASCII digits, read as an integer, so `"039"` and
     * `" 39 "` both resolve to code 39; anything else, a non-ASCII digit included, is unknown, as is a number too
     * large for an `Int`. An unknown identifier resolves to [CatalogueEntry.SRI_UNKNOWN_WARNING] when the tipo is
     * ADVERTENCIA and to [CatalogueEntry.SRI_UNKNOWN_ERROR] otherwise (D-04).
     *
     * Severity comes from the tipo first, then from the entry's category, and an undeterminable severity is an
     * error, so a rejection is never hidden and a warning is never reported as an error (SRI-08, D-06, research A6).
     */
    fun explain(message: SriMessage): ExplainedMessage {
        val tipo = normalisedTipo(message.type)
        val entry = codeOf(message.identifier)?.let { CatalogueEntry.forSriCode(it) } ?: unknownFor(tipo)
        return ExplainedMessage(entry, message, severityOf(tipo, entry))
    }

    private fun codeOf(identifier: String): Int? {
        val trimmed = identifier.trim()
        // Kotlin's digit-aware parsers accept other scripts' digits, so ASCII is checked first and explicitly.
        if (trimmed.isEmpty() || !trimmed.all { it in '0'..'9' }) return null
        return trimmed.toIntOrNull()
    }

    private fun unknownFor(tipo: String?): CatalogueEntry =
        if (tipo == TIPO_WARNING) CatalogueEntry.SRI_UNKNOWN_WARNING else CatalogueEntry.SRI_UNKNOWN_ERROR

    private fun severityOf(tipo: String?, entry: CatalogueEntry): Severity {
        if (tipo == TIPO_ERROR) return Severity.ERROR
        if (tipo == TIPO_WARNING) return Severity.WARNING
        return if (entry.category == Category.WARNING) Severity.WARNING else Severity.ERROR
    }

    /** Trim, collapse inner whitespace and upper-case with [Locale.ROOT], never the default locale. */
    private fun normalisedTipo(type: String?): String? =
        type?.trim()?.replace(WHITESPACE, " ")?.uppercase(Locale.ROOT)
}
