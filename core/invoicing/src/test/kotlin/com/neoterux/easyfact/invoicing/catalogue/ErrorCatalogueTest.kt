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
import com.neoterux.easyfact.invoicing.soap.MalformedReason
import com.neoterux.easyfact.invoicing.soap.RawResponse
import com.neoterux.easyfact.invoicing.soap.ResponseProblem
import com.neoterux.easyfact.invoicing.soap.SriMessage
import com.neoterux.easyfact.invoicing.text.SanitizerError
import com.neoterux.easyfact.invoicing.totals.BuyerGuardError
import com.neoterux.easyfact.invoicing.totals.TotalsError
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mappings from every typed error family onto the catalogue (D-09) and the explanation of a received SRI
 * message (D-04, SRI-13). Each family is listed in full, and [everyFamilyIsListedInFull] compares each list with
 * the family's permitted subclasses, so a variant added without a row here fails this suite as well as the
 * compiler.
 */
class ErrorCatalogueTest {

    private val signingTime = OffsetDateTime.parse("2026-01-01T00:00:00-05:00")
    private val raw = RawResponse.of(byteArrayOf(1))

    private val factura: List<Pair<FacturaError, CatalogueEntry>> = listOf(
        FacturaError.FieldTooLong("e", 10, 11) to CatalogueEntry.TEXT_TOO_LONG,
        FacturaError.FieldTooShort("e", 3, 2) to CatalogueEntry.TEXT_EMPTY,
        FacturaError.BlankField("e") to CatalogueEntry.TEXT_EMPTY,
        FacturaError.IllegalCharacter("e") to CatalogueEntry.TEXT_CONTROL_CHARACTER,
        FacturaError.DecimalOutOfRange("e", "maxInclusive", "1") to CatalogueEntry.AMOUNT_OUT_OF_RANGE,
        FacturaError.InvalidFormat("e") to CatalogueEntry.FIELD_INVALID_FORMAT,
        FacturaError.TooManyEntries("e", 15, 16) to CatalogueEntry.TOO_MANY_ADDITIONAL_FIELDS,
        FacturaError.CodeOutsideCatalogue("e", "9", setOf("1")) to CatalogueEntry.ENGINE_BUG,
        FacturaError.EmptyCollection("e") to CatalogueEntry.ENGINE_BUG,
        FacturaError.AccessKeyMismatch("claveAcceso", "ruc") to CatalogueEntry.ENGINE_BUG,
    )

    private val accessKey: List<Pair<AccessKeyError, CatalogueEntry>> = listOf(
        AccessKeyError.SegmentNotNumeric("f") to CatalogueEntry.ENGINE_BUG,
        AccessKeyError.SegmentWrongLength("f", 1, 2) to CatalogueEntry.ENGINE_BUG,
        AccessKeyError.SequentialOutOfRange(-1L) to CatalogueEntry.ENGINE_BUG,
    )

    private val rehydration: List<Pair<RehydrationError, CatalogueEntry>> = listOf(
        RehydrationError.DigestMismatch to CatalogueEntry.ENGINE_BUG,
        RehydrationError.EmptyOctets to CatalogueEntry.ENGINE_BUG,
    )

    private val signing: List<Pair<SigningError, CatalogueEntry>> = listOf(
        SigningError.KeyNotRsa2048 to CatalogueEntry.CERTIFICATE_KEY_UNSUPPORTED,
        SigningError.CertificateNotValidAtSigningTime(signingTime) to CatalogueEntry.CERTIFICATE_NOT_VALID_NOW,
        SigningError.KeystoreUnlockFailed to CatalogueEntry.CERTIFICATE_UNLOCK_FAILED,
        SigningError.PrivateKeyDoesNotMatchCertificate to CatalogueEntry.CERTIFICATE_KEY_MISMATCH,
        SigningError.InputNotCanonical("r") to CatalogueEntry.ENGINE_BUG,
        SigningError.SigningFailed("s") to CatalogueEntry.ENGINE_BUG,
    )

    private val totals: List<Pair<TotalsError, CatalogueEntry>> = listOf(
        TotalsError.NoLines to CatalogueEntry.NO_LINES,
        TotalsError.NegativeQuantity(0) to CatalogueEntry.NEGATIVE_QUANTITY,
        TotalsError.NegativeUnitPrice(0) to CatalogueEntry.NEGATIVE_UNIT_PRICE,
        TotalsError.UnknownIvaCode(0, "x") to CatalogueEntry.UNKNOWN_IVA_CODE,
        TotalsError.IvaCodeNotComputable(0, "8") to CatalogueEntry.IVA_CODE_NOT_COMPUTABLE,
    )

    private val buyerGuard: List<Pair<BuyerGuardError, CatalogueEntry>> = listOf(
        BuyerGuardError.ConsumidorFinalCapExceeded to CatalogueEntry.CONSUMIDOR_FINAL_CAP_EXCEEDED,
    )

    private val sanitizer: List<Pair<SanitizerError, CatalogueEntry>> = listOf(
        SanitizerError.ControlCharacter("f") to CatalogueEntry.TEXT_CONTROL_CHARACTER,
        SanitizerError.UnsupportedCharacter("f") to CatalogueEntry.TEXT_UNSUPPORTED_CHARACTER,
        SanitizerError.Empty("f") to CatalogueEntry.TEXT_EMPTY,
        SanitizerError.TooLong("f", 3, 4) to CatalogueEntry.TEXT_TOO_LONG,
    )

    private val mapping: List<Pair<MappingError, CatalogueEntry>> = listOf(
        MappingError.Totals(TotalsError.NoLines) to CatalogueEntry.NO_LINES,
        MappingError.Writer(FacturaError.InvalidFormat("e")) to CatalogueEntry.FIELD_INVALID_FORMAT,
        MappingError.Text(SanitizerError.Empty("f")) to CatalogueEntry.TEXT_EMPTY,
        MappingError.BuyerGuard(BuyerGuardError.ConsumidorFinalCapExceeded) to
            CatalogueEntry.CONSUMIDOR_FINAL_CAP_EXCEEDED,
        MappingError.TooManyAdditionalFields(15, 16) to CatalogueEntry.TOO_MANY_ADDITIONAL_FIELDS,
        MappingError.PreviewMarkerInDocument("f") to CatalogueEntry.PREVIEW_MARKER_IN_DOCUMENT,
        MappingError.ProviderRucMalformed to CatalogueEntry.PROVIDER_RUC_MALFORMED,
        MappingError.ConsumidorFinalIdentificationMismatch to CatalogueEntry.CONSUMIDOR_FINAL_IDENTIFICATION_MISMATCH,
    )

    private val withholding: List<Pair<WithholdingAgentError, CatalogueEntry>> = listOf(
        WithholdingAgentError.Empty to CatalogueEntry.WITHHOLDING_AGENT_INVALID,
        WithholdingAgentError.NotNumeric to CatalogueEntry.WITHHOLDING_AGENT_INVALID,
        WithholdingAgentError.TooLong(8, 9) to CatalogueEntry.WITHHOLDING_AGENT_INVALID,
    )

    private val identification: List<Pair<IdentificationVerdict, CatalogueEntry?>> = listOf(
        IdentificationVerdict.Valid to null,
        IdentificationVerdict.NoCheckDigit to null,
        IdentificationVerdict.Blank to CatalogueEntry.IDENTIFICATION_MALFORMED,
        IdentificationVerdict.TooLong(20, 21) to CatalogueEntry.IDENTIFICATION_MALFORMED,
        IdentificationVerdict.WrongLength(10, 9) to CatalogueEntry.IDENTIFICATION_MALFORMED,
        IdentificationVerdict.NotNumeric to CatalogueEntry.IDENTIFICATION_MALFORMED,
        IdentificationVerdict.BadProvince to CatalogueEntry.IDENTIFICATION_MALFORMED,
        IdentificationVerdict.BadThirdDigit to CatalogueEntry.IDENTIFICATION_MALFORMED,
        IdentificationVerdict.BadCheckDigit to CatalogueEntry.IDENTIFICATION_CHECK_DIGIT,
        IdentificationVerdict.BadEstablishmentSuffix to CatalogueEntry.IDENTIFICATION_MALFORMED,
        IdentificationVerdict.NotConsumidorFinal to CatalogueEntry.IDENTIFICATION_NOT_CONSUMIDOR_FINAL,
        IdentificationVerdict.UnknownType to CatalogueEntry.IDENTIFICATION_MALFORMED,
    )

    private val warnings: List<Pair<EngineWarning, CatalogueEntry>> = listOf(
        EngineWarning.ZeroQuantity(0) to CatalogueEntry.WARNING_ZERO_QUANTITY,
        EngineWarning.ZeroLineTotal(0) to CatalogueEntry.WARNING_ZERO_LINE_TOTAL,
        EngineWarning.ZeroInvoiceTotal to CatalogueEntry.WARNING_ZERO_INVOICE_TOTAL,
        EngineWarning.SequenceAssignedAtIssue to CatalogueEntry.WARNING_SEQUENCE_ASSIGNED_AT_ISSUE,
        EngineWarning.UnrecognisedToken("x") to CatalogueEntry.WARNING_UNRECOGNISED_TOKEN,
    )

    private val responses: List<Pair<ResponseProblem, CatalogueEntry>> = listOf(
        ResponseProblem.SoapFault(null, null, raw) to CatalogueEntry.RESPONSE_SOAP_FAULT,
        ResponseProblem.UnexpectedBody(raw) to CatalogueEntry.RESPONSE_UNEXPECTED,
        ResponseProblem.Malformed(MalformedReason.EMPTY, raw) to CatalogueEntry.RESPONSE_MALFORMED,
    )

    @Test
    fun facturaErrorsMapToTheirEntries() {
        for ((error, expected) in factura) assertEquals(error.toString(), expected, ErrorCatalogue.entryFor(error))
    }

    @Test
    fun accessKeyAndRehydrationErrorsAreEngineBugs() {
        for ((error, expected) in accessKey) assertEquals(error.toString(), expected, ErrorCatalogue.entryFor(error))
        for ((error, expected) in rehydration) assertEquals(error.toString(), expected, ErrorCatalogue.entryFor(error))
    }

    @Test
    fun signingErrorsMapToCertificateEntriesOrTheEngineBug() {
        for ((error, expected) in signing) assertEquals(error.toString(), expected, ErrorCatalogue.entryFor(error))
    }

    @Test
    fun totalsAndBuyerGuardErrorsMapToTheirSameNamedEntries() {
        for ((error, expected) in totals) assertEquals(error.toString(), expected, ErrorCatalogue.entryFor(error))
        for ((error, expected) in buyerGuard) assertEquals(error.toString(), expected, ErrorCatalogue.entryFor(error))
    }

    @Test
    fun sanitiserAndWithholdingErrorsMapToTheirEntries() {
        for ((error, expected) in sanitizer) assertEquals(error.toString(), expected, ErrorCatalogue.entryFor(error))
        for ((error, expected) in withholding) assertEquals(error.toString(), expected, ErrorCatalogue.entryFor(error))
    }

    @Test
    fun mappingErrorsDelegateToTheWrappedErrorsEntry() {
        for ((error, expected) in mapping) assertEquals(error.toString(), expected, ErrorCatalogue.entryFor(error))
    }

    @Test
    fun aWrappedErrorResolvesLikeItsOwnFamilyForEveryVariant() {
        for ((inner, _) in totals) {
            assertEquals(ErrorCatalogue.entryFor(inner), ErrorCatalogue.entryFor(MappingError.Totals(inner)))
        }
        for ((inner, _) in factura) {
            assertEquals(ErrorCatalogue.entryFor(inner), ErrorCatalogue.entryFor(MappingError.Writer(inner)))
        }
        for ((inner, _) in sanitizer) {
            assertEquals(ErrorCatalogue.entryFor(inner), ErrorCatalogue.entryFor(MappingError.Text(inner)))
        }
        for ((inner, _) in buyerGuard) {
            assertEquals(ErrorCatalogue.entryFor(inner), ErrorCatalogue.entryFor(MappingError.BuyerGuard(inner)))
        }
    }

    @Test
    fun identificationVerdictsMapToAnEntryOnlyWhenTheyAreAFault() {
        for ((verdict, expected) in identification) {
            assertEquals(verdict.toString(), expected, ErrorCatalogue.entryFor(verdict))
        }
        assertNull(ErrorCatalogue.entryFor(IdentificationVerdict.Valid))
        assertNull(ErrorCatalogue.entryFor(IdentificationVerdict.NoCheckDigit))
    }

    @Test
    fun engineWarningsMapToTheirWarningEntries() {
        for ((warning, expected) in warnings) assertEquals(warning.toString(), expected, ErrorCatalogue.entryFor(warning))
    }

    @Test
    fun everyResponseProblemIsAnUnknownOutcomeNeverARejection() {
        for ((problem, expected) in responses) {
            val entry = ErrorCatalogue.entryFor(problem)
            assertEquals(problem.toString(), expected, entry)
            assertEquals(Category.UNKNOWN_OUTCOME, entry.category)
        }
    }

    @Test
    fun everyFamilyIsListedInFull() {
        assertCovers(FacturaError::class.java, factura.map { it.first })
        assertCovers(AccessKeyError::class.java, accessKey.map { it.first })
        assertCovers(RehydrationError::class.java, rehydration.map { it.first })
        assertCovers(SigningError::class.java, signing.map { it.first })
        assertCovers(TotalsError::class.java, totals.map { it.first })
        assertCovers(BuyerGuardError::class.java, buyerGuard.map { it.first })
        assertCovers(SanitizerError::class.java, sanitizer.map { it.first })
        assertCovers(MappingError::class.java, mapping.map { it.first })
        assertCovers(WithholdingAgentError::class.java, withholding.map { it.first })
        assertCovers(IdentificationVerdict::class.java, identification.map { it.first })
        assertCovers(EngineWarning::class.java, warnings.map { it.first })
        assertCovers(ResponseProblem::class.java, responses.map { it.first })
    }

    @Test
    fun aCataloguedCodeKeepsTheMessageObjectUnchanged() {
        val message = SriMessage("39", "text", "extra", "ERROR")
        val explained = ErrorCatalogue.explain(message)
        assertEquals(CatalogueEntry.SRI_39, explained.entry)
        assertSame(message, explained.message)
        assertEquals(Severity.ERROR, explained.severity)
    }

    @Test
    fun anIdentifierIsMatchedAfterTrimmingAsAsciiDigits() {
        assertEquals(CatalogueEntry.SRI_39, ErrorCatalogue.explain(SriMessage("039", "m", null, "ERROR")).entry)
        assertEquals(CatalogueEntry.SRI_39, ErrorCatalogue.explain(SriMessage(" 39 ", "m", null, "ERROR")).entry)
        assertEquals(CatalogueEntry.SRI_39, ErrorCatalogue.explain(SriMessage(" 039 ", "m", null, "ERROR")).entry)
        assertEquals(CatalogueEntry.SRI_2, ErrorCatalogue.explain(SriMessage("0002", "m", null, "ERROR")).entry)
    }

    @Test
    fun anIdentifierThatIsNotPlainAsciiDigitsIsUnknown() {
        val unknown = listOf(
            "\u0663\u0669", // Arabic-Indic digits that read as 39
            "\uFF13\uFF19", // full-width digits that read as 39
            "",
            "   ",
            "+39",
            "-39",
            "3 9",
            "39a",
            (39L + (1L shl 32)).toString(), // 39 plus 2^32: must not wrap onto code 39
            "9".repeat(20),
        )
        for (identifier in unknown) {
            val explained = ErrorCatalogue.explain(SriMessage(identifier, "m", null, "ERROR"))
            assertEquals("identifier '$identifier'", CatalogueEntry.SRI_UNKNOWN_ERROR, explained.entry)
        }
    }

    @Test
    fun anUnknownCodeKeepsItsRawIdentifierMessageAdditionalInformationAndType() {
        val message = SriMessage("99", "raw text", "raw extra", "ERROR")
        val explained = ErrorCatalogue.explain(message)
        assertEquals(CatalogueEntry.SRI_UNKNOWN_ERROR, explained.entry)
        assertEquals("99", explained.message.identifier)
        assertEquals("raw text", explained.message.message)
        assertEquals("raw extra", explained.message.additionalInformation)
        assertEquals("ERROR", explained.message.type)
    }

    @Test
    fun anUnknownCodeResolvesByTipo() {
        assertEquals(
            CatalogueEntry.SRI_UNKNOWN_ERROR,
            ErrorCatalogue.explain(SriMessage("99", "m", null, "ERROR")).entry,
        )
        assertEquals(
            CatalogueEntry.SRI_UNKNOWN_WARNING,
            ErrorCatalogue.explain(SriMessage("123", "m", null, "ADVERTENCIA")).entry,
        )
        assertEquals(Severity.WARNING, ErrorCatalogue.explain(SriMessage("123", "m", null, "ADVERTENCIA")).severity)
    }

    @Test
    fun severityComesFromTipoFirst() {
        assertEquals(Severity.ERROR, ErrorCatalogue.explain(SriMessage("60", "m", null, "ERROR")).severity)
        assertEquals(Severity.WARNING, ErrorCatalogue.explain(SriMessage("39", "m", null, "ADVERTENCIA")).severity)
        assertEquals(Severity.WARNING, ErrorCatalogue.explain(SriMessage("60", "m", null, "  advertencia ")).severity)
        assertEquals(Severity.ERROR, ErrorCatalogue.explain(SriMessage("60", "m", null, " error  ")).severity)
    }

    @Test
    fun withoutAUsableTipoSeverityFollowsTheCategoryAndDefaultsToError() {
        assertEquals(Severity.ERROR, ErrorCatalogue.explain(SriMessage("39", "m", null, null)).severity)
        assertEquals(Severity.WARNING, ErrorCatalogue.explain(SriMessage("60", "m", null, null)).severity)
        assertEquals(Severity.ERROR, ErrorCatalogue.explain(SriMessage("99", "m", null, null)).severity)
        assertEquals(Severity.ERROR, ErrorCatalogue.explain(SriMessage("39", "m", null, "INFORMATIVO")).severity)
        assertEquals(Severity.WARNING, ErrorCatalogue.explain(SriMessage("60", "m", null, "INFORMATIVO")).severity)
        assertEquals(CatalogueEntry.SRI_UNKNOWN_ERROR, ErrorCatalogue.explain(SriMessage("99", "m", null, "INFORMATIVO")).entry)
    }

    @Test
    fun aTurkishDefaultLocaleChangesNothing() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val warning = ErrorCatalogue.explain(SriMessage("123", "m", null, "advertencia"))
            assertEquals(Severity.WARNING, warning.severity)
            assertEquals(CatalogueEntry.SRI_UNKNOWN_WARNING, warning.entry)
            assertEquals(Severity.ERROR, ErrorCatalogue.explain(SriMessage("60", "m", null, "error")).severity)
            assertEquals(Severity.WARNING, ErrorCatalogue.explain(SriMessage("60", "m", null, " advertencia ")).severity)
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun noMappingInTheSourceHasAnElseBranch() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/neoterux/easyfact/invoicing/catalogue/ErrorCatalogue.kt"),
        )
        val offending = source.lines().withIndex().filter { (_, line) -> Regex("^else\\s*->").containsMatchIn(line.trim()) }
        assertTrue("an else branch on lines ${offending.map { it.index + 1 }}", offending.isEmpty())
    }

    @Test
    fun noSpanishTextLivesInTheSource() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/neoterux/easyfact/invoicing/catalogue/ErrorCatalogue.kt"),
        )
        val nonAscii = source.lines().withIndex().filter { (_, line) -> line.any { it.code > 127 } }
        assertTrue("non-ASCII text on lines ${nonAscii.map { it.index + 1 }}", nonAscii.isEmpty())
    }

    private fun assertCovers(family: Class<*>, instances: List<Any>) {
        val permitted = family.permittedSubclasses
        assertTrue("${family.simpleName} is not a sealed type on this JVM", permitted != null)
        assertEquals(
            "${family.simpleName}: the variants listed in this suite",
            permitted!!.map { it.name }.toSet(),
            instances.map { it.javaClass.name }.toSet(),
        )
    }
}
