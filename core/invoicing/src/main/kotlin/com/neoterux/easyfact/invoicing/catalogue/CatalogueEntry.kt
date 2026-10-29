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
enum class Source { SRI, ENGINE }

/** The kind of situation an entry describes. */
enum class Category { SETUP, CORRECTABLE, ENGINE_BUG, TRANSIENT, WAIT, RECONCILE, UNKNOWN_OUTCOME, WARNING }

/** The point of the pipeline an entry can be raised from. */
enum class Stage {
    RECEPTION,
    AUTHORIZATION,
    ISSUER_CHECK,
    TEXT,
    TOTALS,
    MAPPING,
    WRITING,
    KEY,
    SIGNING,
    STORAGE,
    RESPONSE,
    IDENTIFICATION,
    TEMPLATE,
}

/** What the user is advised to do next; advisory only. */
enum class NextAction {
    FIX_AND_RESUBMIT,
    FIX_INPUT,
    RENEW_CERTIFICATE,
    FIX_IN_SRI_PORTAL,
    REVIEW_SEQUENTIAL,
    CHECK_STATUS,
    WAIT,
    CONTACT_SRI,
    UPDATE_APP,
    EXPORT_DIAGNOSTICS,
    CONFIRM_TO_CONTINUE,
    NONE,
}

/** Placeholder entries until the catalogue is transcribed. */
enum class CatalogueEntry(
    val source: Source,
    val sriCode: Int?,
    val category: Category,
    val stages: Set<Stage>,
    val nextAction: NextAction,
    val fichaReference: String?,
) {
    SRI_2(Source.SRI, 2, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.NONE, "placeholder"),
    SRI_34(Source.SRI, 34, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.NONE, "placeholder"),
    SRI_60(Source.SRI, 60, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.NONE, "placeholder"),
    SRI_80(Source.SRI, 80, Category.ENGINE_BUG, setOf(Stage.RECEPTION), NextAction.NONE, "placeholder"),
    SRI_39(Source.SRI, 39, Category.SETUP, setOf(Stage.AUTHORIZATION), NextAction.FIX_AND_RESUBMIT, "Ficha v2.34 section 11, p.37"),
    SRI_52(Source.SRI, 52, Category.ENGINE_BUG, setOf(Stage.AUTHORIZATION), NextAction.EXPORT_DIAGNOSTICS, "Ficha v2.34 section 11, p.37"),
    SRI_65(Source.SRI, 65, Category.CORRECTABLE, setOf(Stage.RECEPTION), NextAction.FIX_AND_RESUBMIT, "Ficha v2.34 section 11, p.37"),
    SRI_UNKNOWN_ERROR(Source.SRI, null, Category.UNKNOWN_OUTCOME, setOf(Stage.RECEPTION), NextAction.CONTACT_SRI, null),
    SRI_UNKNOWN_WARNING(Source.SRI, null, Category.WARNING, setOf(Stage.RECEPTION), NextAction.NONE, null),
    ENGINE_BUG(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.EXPORT_DIAGNOSTICS, null),
    WARNING_SEQUENCE_ASSIGNED_AT_ISSUE(Source.ENGINE, null, Category.WARNING, setOf(Stage.TEMPLATE), NextAction.NONE, null),
    TEXT_CONTROL_CHARACTER(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    TEXT_UNSUPPORTED_CHARACTER(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    TEXT_EMPTY(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    TEXT_TOO_LONG(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    FIELD_INVALID_FORMAT(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    AMOUNT_OUT_OF_RANGE(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    NO_LINES(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    NEGATIVE_QUANTITY(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    NEGATIVE_UNIT_PRICE(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    UNKNOWN_IVA_CODE(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    IVA_CODE_NOT_COMPUTABLE(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    CONSUMIDOR_FINAL_CAP_EXCEEDED(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    CONSUMIDOR_FINAL_IDENTIFICATION_MISMATCH(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    TOO_MANY_ADDITIONAL_FIELDS(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    PROVIDER_RUC_MALFORMED(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    WITHHOLDING_AGENT_INVALID(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    PREVIEW_MARKER_IN_DOCUMENT(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    IDENTIFICATION_MALFORMED(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    IDENTIFICATION_CHECK_DIGIT(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    IDENTIFICATION_NOT_CONSUMIDOR_FINAL(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    CERTIFICATE_KEY_UNSUPPORTED(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    CERTIFICATE_NOT_VALID_NOW(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    CERTIFICATE_KEY_MISMATCH(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    CERTIFICATE_UNLOCK_FAILED(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    RESPONSE_SOAP_FAULT(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    RESPONSE_UNEXPECTED(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    RESPONSE_MALFORMED(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    WARNING_ZERO_QUANTITY(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    WARNING_ZERO_LINE_TOTAL(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    WARNING_ZERO_INVOICE_TOTAL(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    WARNING_UNRECOGNISED_TOKEN(Source.ENGINE, null, Category.ENGINE_BUG, setOf(Stage.WRITING), NextAction.NONE, null),
    ;

    /** The `strings.xml` key that holds the user-facing text of this entry. */
    val resourceName: String get() = "catalogue_" + name.lowercase(Locale.ROOT)

    companion object {
        /** The entry for the SRI [code], or `null` when the code is not catalogued. */
        fun forSriCode(code: Int): CatalogueEntry? = entries.firstOrNull { it.sriCode == code }
    }
}
