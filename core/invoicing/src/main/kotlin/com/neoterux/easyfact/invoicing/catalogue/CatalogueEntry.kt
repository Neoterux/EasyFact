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

import java.util.Locale

/** Where a catalogue entry comes from. */
enum class Source {
    /** A code or status the SRI itself reports. */
    SRI,

    /** A refusal or note the engine raises on its own. */
    ENGINE,
}

/**
 * The kind of situation an entry describes (D-01). RECONCILE (code 43) and UNKNOWN_OUTCOME (transport
 * faults) are additions to the examples D-01 gave; the choice is recorded in the phase decisions.
 */
enum class Category {
    /** The user's account, certificate or SRI registration needs attention outside the app. */
    SETUP,

    /** The user can correct an input and try again. */
    CORRECTABLE,

    /** Only a defect in the engine can cause it. */
    ENGINE_BUG,

    /** A temporary condition on the SRI side. */
    TRANSIENT,

    /** The SRI is still working; nothing is to be resent. */
    WAIT,

    /** The earlier submission may already have succeeded; check its status first. */
    RECONCILE,

    /** The outcome of a submission is not known, so it must be reconciled and never treated as rejected. */
    UNKNOWN_OUTCOME,

    /** A warning: the operation went ahead, or may go ahead. Never a rejection. */
    WARNING,
}

/** The point of the pipeline an entry can be raised from; an entry may be raised from several. */
enum class Stage {
    /** SRI reception (validarComprobante). */
    RECEPTION,

    /** SRI authorization (autorizacionComprobante). */
    AUTHORIZATION,

    /** The Ficha's EMISOR table: checks the issuer's own system is expected to make. */
    ISSUER_CHECK,

    /** Cleaning free text. */
    TEXT,

    /** Line and invoice arithmetic. */
    TOTALS,

    /** Mapping engine input onto a document. */
    MAPPING,

    /** The canonical document writer. */
    WRITING,

    /** Access key derivation. */
    KEY,

    /** Signing. */
    SIGNING,

    /** Storing and re-reading signed bytes. */
    STORAGE,

    /** Reading an SRI answer. */
    RESPONSE,

    /** Buyer identification checks. */
    IDENTIFICATION,

    /** Template placeholder resolution. */
    TEMPLATE,
}

/**
 * What the user is advised to do next.
 *
 * **Advisory only (D-02):** Phase 6's `decide()` keeps sole authority over every state transition; this is a
 * hint for the explanation shown to the user, nothing more. The declaration order is the specificity order
 * that [MessageRanking] uses as its tiebreak (D-07): most specific first, so reordering these changes which
 * message is the headline.
 */
enum class NextAction {
    /** Correct the document or its data and send it again with the same access key and sequential. */
    FIX_AND_RESUBMIT,

    /** Correct an input before anything is sent. */
    FIX_INPUT,

    /** Renew or replace the signing certificate. */
    RENEW_CERTIFICATE,

    /** Put right a registration state in the SRI portal. */
    FIX_IN_SRI_PORTAL,

    /** Review the sequential; never increment it automatically. */
    REVIEW_SEQUENTIAL,

    /** Ask the SRI for the document's status before doing anything else. */
    CHECK_STATUS,

    /** Wait; do not resend or re-key. */
    WAIT,

    /** Contact the SRI. */
    CONTACT_SRI,

    /** Update the app. */
    UPDATE_APP,

    /** Export diagnostics, because only an engine defect explains it. */
    EXPORT_DIAGNOSTICS,

    /** Ask the user to confirm that they want to go on. */
    CONFIRM_TO_CONTINUE,

    /** Nothing to do. */
    NONE,
}

private const val FICHA_P36 = "Ficha v2.34 section 11, p.36, main table"
private const val FICHA_P37 = "Ficha v2.34 section 11, p.37, main table"
private const val FICHA_P38 = "Ficha v2.34 section 11, p.38, main table"
private const val FICHA_WARNING_P38 = "Ficha v2.34 section 11, p.38, warnings table"
private const val FICHA_EMISOR_P39 = "Ficha v2.34 section 11, p.39, EMISOR table"
private const val FICHA_P37_AND_P39 = "Ficha v2.34 section 11, p.37, main table, and p.39, EMISOR table"

/**
 * Every message the app can explain, in one plain enum (D-01, D-09): the 36 codes of Ficha v2.34 section 11
 * (28 in the main table, 4 in the EMISOR table only, 4 warnings), two Unknown entries, and the engine's own
 * refusals and warnings.
 *
 * **Typed in core, prose in `:app` (D-01).** An entry carries a [resourceName], the `strings.xml` key, and no
 * user-facing text. A plain enum is used rather than enumerating the sealed error hierarchies because that
 * would need `kotlin-reflect`, a dependency that needs the owner's approval (AGENTS.md section 2.4).
 * Completeness is therefore two properties: the mappings in [ErrorCatalogue] are `when` expressions with no
 * else branch, so a new error variant fails compilation, and plan 03-10's `:app` test iterates [entries]
 * against `strings.xml`.
 *
 * **Source of truth (D-05).** Where vendor tables disagree (35, 39, 43, 45, 50, 52, 60, 70) the Ficha wins.
 * Each SRI entry cites its table and page. The Ficha's Spanish message text is not quoted here: each entry
 * paraphrases it in English. Code 99 appears only in the consulta services (section 8), which this phase does
 * not call, so it is deliberately not catalogued and resolves through the Unknown entries (D-04).
 *
 * [stages] is a set because 52 and 65 are checked in two places. The shared engine entries list every stage
 * that can raise them.
 *
 * @property source whether the SRI or the engine raises it
 * @property sriCode the SRI's numeric identifier, or `null` for the Unknown and engine entries
 * @property category the kind of situation
 * @property stages where it can be raised
 * @property nextAction the advisory hint (D-02)
 * @property fichaReference the Ficha table and page that define it, or `null` when the entry is not in the Ficha
 */
enum class CatalogueEntry(
    val source: Source,
    val sriCode: Int?,
    val category: Category,
    val stages: Set<Stage>,
    val nextAction: NextAction,
    val fichaReference: String?,
) {
    /** Ficha p.36, main table: the issuer's RUC is not in the ACTIVE state; checked at authorization. */
    SRI_2(Source.SRI, 2, Category.SETUP, setOf(Stage.AUTHORIZATION), NextAction.FIX_IN_SRI_PORTAL, FICHA_P36),
    /** Ficha p.36, main table: the issuer's establishment is closed down by the tax authority; the service re-enables itself once the closure ends; checked at authorization. */
    SRI_10(Source.SRI, 10, Category.SETUP, setOf(Stage.AUTHORIZATION), NextAction.FIX_IN_SRI_PORTAL, FICHA_P36),
    /** Ficha p.36, main table: the file exceeds the maximum size; checked at reception. */
    SRI_26(Source.SRI, 26, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P36),
    /** Ficha p.37, main table: the taxpayer class may not issue electronic documents; checked at authorization. */
    SRI_27(Source.SRI, 27, Category.SETUP, setOf(Stage.AUTHORIZATION), NextAction.CONTACT_SRI, FICHA_P37),
    /** Ficha p.37, main table: the electronic-media agreement has not been accepted; checked at reception. */
    SRI_28(Source.SRI, 28, Category.SETUP, setOf(Stage.RECEPTION), NextAction.FIX_IN_SRI_PORTAL, FICHA_P37),
    /** Ficha p.39, EMISOR table only: the document was not authorized as part of the issuer's issuance request. */
    SRI_34(Source.SRI, 34, Category.SETUP, setOf(Stage.ISSUER_CHECK), NextAction.FIX_IN_SRI_PORTAL, FICHA_EMISOR_P39),
    /** Ficha p.37, main table: the document fails schema validation; informacionAdicional names the offending element; checked at reception. */
    SRI_35(Source.SRI, 35, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P37),
    /** Ficha p.37, main table: the schema version is discontinued; checked at reception. */
    SRI_36(Source.SRI, 36, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.UPDATE_APP, FICHA_P37),
    /** Ficha p.37, main table: the issuer's RUC has no electronic issuance request; checked at authorization. */
    SRI_37(Source.SRI, 37, Category.SETUP, setOf(Stage.AUTHORIZATION), NextAction.FIX_IN_SRI_PORTAL, FICHA_P37),
    /** Ficha p.37, main table: the issuer's electronic signature is not valid; checked at authorization. Suspects: clock, certificate expiry or revocation, wrong certificate. */
    SRI_39(Source.SRI, 39, Category.SETUP, setOf(Stage.AUTHORIZATION), NextAction.FIX_AND_RESUBMIT, FICHA_P37),
    /** Ficha p.37, main table: the certificate was not found or cannot be converted to X509; checked at authorization. */
    SRI_40(Source.SRI, 40, Category.ENGINE_BUG, setOf(Stage.AUTHORIZATION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P37),
    /** Ficha p.39, EMISOR table only: the certificate passed its expiry date and was not renewed. */
    SRI_42(Source.SRI, 42, Category.SETUP, setOf(Stage.ISSUER_CHECK), NextAction.RENEW_CERTIFICATE, FICHA_EMISOR_P39),
    /** Ficha p.37, main table: the access key is already registered; the earlier submission may already be authorized, so the status is checked first; checked at reception. */
    SRI_43(Source.SRI, 43, Category.RECONCILE, setOf(Stage.RECEPTION), NextAction.CHECK_STATUS, FICHA_P37),
    /** Ficha p.37, main table: the sequential is already registered; the user reviews the starting sequential and the app never increments it by itself; checked at reception. */
    SRI_45(Source.SRI, 45, Category.SETUP, setOf(Stage.RECEPTION), NextAction.REVIEW_SEQUENTIAL, FICHA_P37),
    /** Ficha p.37, main table: a RUC does not exist in the taxpayer registry. The table says the issuer's RUC and the FAQ (p.141) says the receiver's, so both are shown; checked at authorization. */
    SRI_46(Source.SRI, 46, Category.CORRECTABLE, setOf(Stage.AUTHORIZATION), NextAction.FIX_AND_RESUBMIT, FICHA_P37),
    /** Ficha p.37, main table: the document type is not in the SRI's catalogue; checked at reception. */
    SRI_47(Source.SRI, 47, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P37),
    /** Ficha p.37, main table: no schema exists for the document type sent; checked at reception. */
    SRI_48(Source.SRI, 48, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P37),
    /** Ficha p.37, main table: the web service was called with null arguments; checked at reception. */
    SRI_49(Source.SRI, 49, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P37),
    /** Ficha p.37, main table: an unexpected server-side error; checked at reception. Retrying later can succeed. */
    SRI_50(Source.SRI, 50, Category.TRANSIENT, setOf(Stage.RECEPTION), NextAction.WAIT, FICHA_P37),
    /** Ficha p.37 main table (authorization) and p.39 EMISOR table: the document's calculations disagree with the SRI's own. */
    SRI_52(Source.SRI, 52, Category.ENGINE_BUG, setOf(Stage.AUTHORIZATION, Stage.ISSUER_CHECK), NextAction.EXPORT_DIAGNOSTICS, FICHA_P37_AND_P39),
    /** Ficha p.37, main table: the issuing establishment is closed; checked at authorization. */
    SRI_56(Source.SRI, 56, Category.SETUP, setOf(Stage.AUTHORIZATION), NextAction.FIX_IN_SRI_PORTAL, FICHA_P37),
    /** Ficha p.37, main table: the issuer's authorization is suspended by control processes, or the issuer was not authorized at the issue date; checked at authorization. */
    SRI_57(Source.SRI, 57, Category.SETUP, setOf(Stage.AUTHORIZATION), NextAction.CONTACT_SRI, FICHA_P37),
    /** Ficha p.37, main table: the access key components differ from the document's; checked at authorization. */
    SRI_58(Source.SRI, 58, Category.ENGINE_BUG, setOf(Stage.AUTHORIZATION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P37),
    /** Ficha p.38, warnings table (tipo ADVERTENCIA): the buyer's identification does not exist. */
    SRI_59(Source.SRI, 59, Category.WARNING, setOf(Stage.AUTHORIZATION), NextAction.NONE, FICHA_WARNING_P38),
    /** Ficha p.38, warnings table (tipo ADVERTENCIA): sent with every authorization issued in the test environment. Shown, never suppressed and never read as a rejection (D-06). */
    SRI_60(Source.SRI, 60, Category.WARNING, setOf(Stage.AUTHORIZATION), NextAction.NONE, FICHA_WARNING_P38),
    /** Ficha p.38, warnings table (tipo ADVERTENCIA): the buyer's identification is incorrect, for example a cedula that fails its check digit. */
    SRI_62(Source.SRI, 62, Category.WARNING, setOf(Stage.AUTHORIZATION), NextAction.NONE, FICHA_WARNING_P38),
    /** Ficha p.37, main table: the issuer's RUC is closed down by control processes; checked at authorization. */
    SRI_63(Source.SRI, 63, Category.SETUP, setOf(Stage.AUTHORIZATION), NextAction.CONTACT_SRI, FICHA_P37),
    /** Ficha p.39, EMISOR table only: the supporting-document code is not in the catalogue. Not applicable in v1, which issues no document that cites one. */
    SRI_64(Source.SRI, 64, Category.ENGINE_BUG, setOf(Stage.ISSUER_CHECK), NextAction.EXPORT_DIAGNOSTICS, FICHA_EMISOR_P39),
    /** Ficha p.37 main table (reception) and p.39 EMISOR table: the issue date is extemporaneous, the document was not sent within the time allowed for its emission type. */
    SRI_65(Source.SRI, 65, Category.CORRECTABLE, setOf(Stage.RECEPTION, Stage.ISSUER_CHECK), NextAction.FIX_AND_RESUBMIT, FICHA_P37_AND_P39),
    /** Ficha p.37, main table: a date has an invalid format; checked at reception. */
    SRI_67(Source.SRI, 67, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P37),
    /** Ficha p.38, warnings table (tipo ADVERTENCIA): the related supporting document does not exist as an electronic document. */
    SRI_68(Source.SRI, 68, Category.WARNING, setOf(Stage.AUTHORIZATION), NextAction.NONE, FICHA_WARNING_P38),
    /** Ficha p.39, EMISOR table only: the buyer's identification does not exist, in general a buyer RUC absent from the registry. */
    SRI_69(Source.SRI, 69, Category.CORRECTABLE, setOf(Stage.ISSUER_CHECK), NextAction.FIX_AND_RESUBMIT, FICHA_EMISOR_P39),
    /** Ficha p.37, main table: the access key was sent before and its processing has not finished. Ficha note 2 (p.38): neither resend nor re-key for up to 24 hours. */
    SRI_70(Source.SRI, 70, Category.WAIT, setOf(Stage.RECEPTION), NextAction.WAIT, FICHA_P37),
    /** Ficha p.38, main table: the access key parameter of an authorization query is malformed; checked at authorization. */
    SRI_80(Source.SRI, 80, Category.ENGINE_BUG, setOf(Stage.AUTHORIZATION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P38),
    /** Ficha p.38, main table: a transport start date precedes the issue date of a remission guide. Not applicable in v1. */
    SRI_82(Source.SRI, 82, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P38),
    /** Ficha p.38, main table: the IVA refund amount does not match the one authorized by the DIG web service. Not applicable in v1. */
    SRI_92(Source.SRI, 92, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.EXPORT_DIAGNOSTICS, FICHA_P38),

    /** An SRI message of tipo ERROR, or of an undeterminable tipo, whose identifier is not catalogued (D-04). */
    SRI_UNKNOWN_ERROR(Source.SRI, null, Category.UNKNOWN_OUTCOME, setOf(Stage.RECEPTION, Stage.AUTHORIZATION), NextAction.CONTACT_SRI, null),

    /** An SRI message of tipo ADVERTENCIA or INFORMATIVO whose identifier is not catalogued (D-04). */
    SRI_UNKNOWN_WARNING(Source.SRI, null, Category.WARNING, setOf(Stage.RECEPTION, Stage.AUTHORIZATION), NextAction.NONE, null),

    /** The shared entry for a failure only an engine bug can cause: a writer, access-key, rehydration or signing precondition broke (D-09). The user is told to export diagnostics. */
    ENGINE_BUG(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING, Stage.KEY, Stage.SIGNING, Stage.STORAGE), NextAction.EXPORT_DIAGNOSTICS, null),
    /** A text holds a control character that cannot be written to the document. */
    TEXT_CONTROL_CHARACTER(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.TEXT, Stage.WRITING), NextAction.FIX_INPUT, null),
    /** A text holds a character outside the Basic Multilingual Plane, such as an emoji (D-34). */
    TEXT_UNSUPPORTED_CHARACTER(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.TEXT), NextAction.FIX_INPUT, null),
    /** A mandatory text is empty or blank. */
    TEXT_EMPTY(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.TEXT, Stage.WRITING), NextAction.FIX_INPUT, null),
    /** A text is longer than the schema allows for its element. */
    TEXT_TOO_LONG(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.TEXT, Stage.WRITING), NextAction.FIX_INPUT, null),
    /** A value does not match the schema pattern or enumeration of its element. */
    FIELD_INVALID_FORMAT(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.WRITING), NextAction.FIX_INPUT, null),
    /** An amount breaks a decimal facet of the schema. */
    AMOUNT_OUT_OF_RANGE(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.WRITING), NextAction.FIX_INPUT, null),
    /** The invoice has no lines. */
    NO_LINES(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.TOTALS), NextAction.FIX_INPUT, null),
    /** A line has a negative quantity (D-27). */
    NEGATIVE_QUANTITY(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.TOTALS), NextAction.FIX_INPUT, null),
    /** A line has a negative unit price (D-27). */
    NEGATIVE_UNIT_PRICE(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.TOTALS), NextAction.FIX_INPUT, null),
    /** A line names an IVA percentage code the rate table does not know. */
    UNKNOWN_IVA_CODE(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.TOTALS), NextAction.FIX_INPUT, null),
    /** A line names the differentiated IVA code, which has no fixed rate to compute with. */
    IVA_CODE_NOT_COMPUTABLE(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.TOTALS), NextAction.FIX_INPUT, null),
    /** A consumidor-final invoice exceeds the maximum total (D-31). */
    CONSUMIDOR_FINAL_CAP_EXCEEDED(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.MAPPING), NextAction.FIX_INPUT, null),
    /** The buyer type is consumidor final but the identification is not the fixed one (D-31). */
    CONSUMIDOR_FINAL_IDENTIFICATION_MISMATCH(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.MAPPING), NextAction.FIX_INPUT, null),
    /** The additional-information container would hold more entries than the schema allows. */
    TOO_MANY_ADDITIONAL_FIELDS(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.MAPPING, Stage.WRITING), NextAction.FIX_INPUT, null),
    /** The issuer's provider RUC is set but is not thirteen digits (D-19). */
    PROVIDER_RUC_MALFORMED(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.MAPPING), NextAction.FIX_INPUT, null),
    /** The issuer's withholding-agent resolution number is empty, not numeric or too long. */
    WITHHOLDING_AGENT_INVALID(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.MAPPING), NextAction.FIX_INPUT, null),
    /** A text bound for the document still holds the preview marker of an unassigned sequential (D-37). A programming fault, never a user input. */
    PREVIEW_MARKER_IN_DOCUMENT(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.MAPPING), NextAction.EXPORT_DIAGNOSTICS, null),
    /** A buyer identification is blank, the wrong length, not numeric or fails a structural rule. */
    IDENTIFICATION_MALFORMED(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.IDENTIFICATION), NextAction.FIX_INPUT, null),
    /** A buyer identification fails its check digit. Phase 5 decides whether this blocks or only warns, because the SRI itself only warns (code 62). */
    IDENTIFICATION_CHECK_DIGIT(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.IDENTIFICATION), NextAction.FIX_INPUT, null),
    /** A consumidor-final identification is not exactly the fixed placeholder. */
    IDENTIFICATION_NOT_CONSUMIDOR_FINAL(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.IDENTIFICATION), NextAction.FIX_INPUT, null),
    /** The certificate's key is not RSA of exactly 2048 bits, which the SRI requires. */
    CERTIFICATE_KEY_UNSUPPORTED(Source.ENGINE, null, Category.SETUP, setOf(Stage.SIGNING), NextAction.RENEW_CERTIFICATE, null),
    /** The certificate is outside its validity window at signing time. */
    CERTIFICATE_NOT_VALID_NOW(Source.ENGINE, null, Category.SETUP, setOf(Stage.SIGNING), NextAction.RENEW_CERTIFICATE, null),
    /** The private key is not the counterpart of the certificate's public key. */
    CERTIFICATE_KEY_MISMATCH(Source.ENGINE, null, Category.SETUP, setOf(Stage.SIGNING), NextAction.RENEW_CERTIFICATE, null),
    /** The certificate store could not be opened: the password was rejected or the file is corrupt. */
    CERTIFICATE_UNLOCK_FAILED(Source.ENGINE, null, Category.CORRECTABLE, setOf(Stage.SIGNING), NextAction.FIX_INPUT, null),
    /** The answer was a SOAP fault. The outcome of the submission is unknown, so the status is reconciled first and the document is never treated as rejected (D-44). */
    RESPONSE_SOAP_FAULT(Source.ENGINE, null, Category.UNKNOWN_OUTCOME, setOf(Stage.RESPONSE), NextAction.CHECK_STATUS, null),
    /** The answer was well-formed XML without the expected response element. Reconcile first (D-44). */
    RESPONSE_UNEXPECTED(Source.ENGINE, null, Category.UNKNOWN_OUTCOME, setOf(Stage.RESPONSE), NextAction.CHECK_STATUS, null),
    /** The answer could not be read as XML or broke a parser limit. Reconcile first (D-44). */
    RESPONSE_MALFORMED(Source.ENGINE, null, Category.UNKNOWN_OUTCOME, setOf(Stage.RESPONSE), NextAction.CHECK_STATUS, null),
    /** A line has a quantity of zero (D-27). */
    WARNING_ZERO_QUANTITY(Source.ENGINE, null, Category.WARNING, setOf(Stage.TOTALS), NextAction.CONFIRM_TO_CONTINUE, null),
    /** A line has a total of zero (D-27). */
    WARNING_ZERO_LINE_TOTAL(Source.ENGINE, null, Category.WARNING, setOf(Stage.TOTALS), NextAction.CONFIRM_TO_CONTINUE, null),
    /** The whole invoice totals zero (D-27). */
    WARNING_ZERO_INVOICE_TOTAL(Source.ENGINE, null, Category.WARNING, setOf(Stage.TOTALS), NextAction.CONFIRM_TO_CONTINUE, null),
    /** A template holds a brace token the resolver does not know and leaves as literal text (D-38). */
    WARNING_UNRECOGNISED_TOKEN(Source.ENGINE, null, Category.WARNING, setOf(Stage.TEMPLATE), NextAction.CONFIRM_TO_CONTINUE, null),
    /** An informational note that the previewed number is only a marker and the real sequential is assigned at issue (D-37). NONE rather than CONFIRM_TO_CONTINUE: it must not ask for confirmation on every preview that contains a sequence token. */
    WARNING_SEQUENCE_ASSIGNED_AT_ISSUE(Source.ENGINE, null, Category.WARNING, setOf(Stage.TEMPLATE), NextAction.NONE, null),
    ;

    /** The `strings.xml` key that holds the user-facing text of this entry. */
    val resourceName: String get() = "catalogue_" + name.lowercase(Locale.ROOT)

    companion object {
        private val BY_SRI_CODE: Map<Int, CatalogueEntry> =
            entries.filter { it.sriCode != null }.associateBy { it.sriCode!! }

        /** The entry for the SRI [code], or `null` when the code is not catalogued (99 is not). */
        fun forSriCode(code: Int): CatalogueEntry? = BY_SRI_CODE[code]
    }
}
