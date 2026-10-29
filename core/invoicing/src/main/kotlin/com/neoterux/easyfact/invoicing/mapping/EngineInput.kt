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
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The plain engine input types (D-10): what the caller hands to [InvoiceMapper].
 *
 * These are deliberately not the Phase 5 template or the Phase 6 persistence model. Phase 5 builds
 * templates on top of them and Phase 6 persists the result; nothing here knows either exists, which
 * is what keeps TMPL-01..07 out of Phase 3.
 *
 * Every type that holds identification data overrides `toString()` to render no field value, in the
 * same spirit as `AccessKey(redacted)`: an input object reaches logs and crash reports, and this
 * repository's rule is that no identification number, name, address or mailbox is ever logged
 * (AGENTS.md sections 2.2 and 7).
 */

/** The SRI environment, `ambiente`: `1` is test, `2` is production. */
enum class Environment(val code: String) {
    TEST("1"),
    PRODUCTION("2"),
}

/** The buyer's identification type, `tipoIdentificacionComprador`, with the codes the SRI publishes. */
enum class BuyerIdType(val code: String) {
    RUC("04"),
    CEDULA("05"),
    PASSPORT("06"),
    CONSUMIDOR_FINAL("07"),
    FOREIGN("08"),
}

/**
 * The issuer, one per install (SETUP-01).
 *
 * Plan 03-08 adds the RIMPE legend, the withholding-agent resolution and the provider RUC with
 * defaults; they are not here yet so that the tracer does not pre-empt those decisions.
 */
data class IssuerProfile(
    val ruc: String,
    val legalName: String,
    val tradeName: String?,
    val headOfficeAddress: String,
    /** `estab`: three digits. */
    val establishment: String,
    /** `ptoEmi`: three digits. */
    val emissionPoint: String,
    val accountingRequired: Boolean,
    val specialTaxpayerNumber: String? = null,
) {
    override fun toString(): String = "IssuerProfile(redacted)"
}

/**
 * The buyer. One billing email is required (D-18); it is the first `infoAdicional` entry. [address]
 * and [phone] become `infoAdicional` entries too, and `direccionComprador` is left empty on purpose
 * (research A12).
 */
data class Buyer(
    val idType: BuyerIdType,
    val identification: String,
    val legalName: String,
    val billingEmail: String,
    val address: String? = null,
    val phone: String? = null,
) {
    override fun toString(): String = "Buyer(redacted)"
}

/**
 * One line, already resolved: placeholders replaced, amounts typed in.
 *
 * There is no discount field and none may be added in v1 (D-24). [quantity] and [unitPrice] are
 * rounded to six decimals by [com.neoterux.easyfact.invoicing.totals.InvoiceCalculator] before the
 * line base is computed (D-23). [ivaRateCode] is the SRI `codigoPorcentaje`.
 */
data class ResolvedLine(
    val code: String?,
    val description: String,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val ivaRateCode: String,
) {
    companion object {

        /**
         * A lump-sum line: one unit at the entered [amount], which is the base **before** IVA (D-26).
         */
        fun lumpSum(code: String?, description: String, amount: BigDecimal, ivaRateCode: String): ResolvedLine =
            ResolvedLine(
                code = code,
                description = description,
                quantity = BigDecimal.ONE,
                unitPrice = amount,
                ivaRateCode = ivaRateCode,
            )
    }
}

/** A template-defined extra `infoAdicional` entry: a name and the value resolved for this invoice. */
data class TemplateField(val name: String, val value: String)

/**
 * `formaPago`, the payment method, with the eight codes of Ficha v2.34 Table 24 (p.79).
 *
 * TMPL-06 (Phase 5) lets the user pick one per template.
 */
enum class PaymentMethod(val code: String) {
    WITHOUT_FINANCIAL_SYSTEM("01"),
    DEBT_OFFSET("15"),
    DEBIT_CARD("16"),
    ELECTRONIC_MONEY("17"),
    PREPAID_CARD("18"),
    CREDIT_CARD("19"),
    OTHER_WITH_FINANCIAL_SYSTEM("20"),
    ENDORSEMENT_OF_SECURITIES("21"),
}

/** A credit term: `plazo` and `unidadTiempo` (D-30). */
data class CreditTerm(val term: BigDecimal, val unit: String)

/** The single `pago` of the document (D-30): a method and, only when the template has one, a credit term. */
data class PaymentTerms(val method: PaymentMethod, val creditTerm: CreditTerm? = null)

/**
 * Everything [InvoiceMapper] needs to build one factura.
 *
 * [accessKey] is an **input**: Phase 6 derives it once, at sequence allocation, with
 * [com.neoterux.easyfact.invoicing.accesskey.KeyDerivation] and persists it. The mapper never derives
 * or recomputes a key, so a persisted key is never changed for an existing row (D-11).
 */
data class InvoiceRequest(
    val issuer: IssuerProfile,
    val buyer: Buyer,
    val lines: List<ResolvedLine>,
    val payment: PaymentTerms,
    val environment: Environment,
    val issueDate: LocalDate,
    val sequential: Long,
    val accessKey: AccessKey,
    val templateFields: List<TemplateField> = emptyList(),
) {
    override fun toString(): String = "InvoiceRequest(redacted)"
}
