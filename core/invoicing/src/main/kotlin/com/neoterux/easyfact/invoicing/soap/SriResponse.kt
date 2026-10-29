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

package com.neoterux.easyfact.invoicing.soap

import java.time.OffsetDateTime

/*
 * The parsed form of the SRI's two SOAP answers (D-39..D-44). The shapes follow the technical
 * sheet (Ficha) v2.34, pages 17-19. Every type that can hold response content has a toString that
 * renders none of it: parsed objects reach logs, and a response carries the issuer's RUC, the
 * access key and text that echoes the document.
 */

/**
 * The exact bytes of a response, kept in a private copy.
 *
 * A `ByteArray` property in a data class would give identity equality and a toString that prints
 * an object reference, so the bytes live behind a class in the manner of `SignedDocument`. There
 * is no `String` accessor and [toString] renders only the size.
 */
class RawResponse private constructor(private val bytes: ByteArray) {

    /** The number of bytes. */
    val size: Int get() = bytes.size

    /** A copy of the response bytes. */
    fun octets(): ByteArray = bytes.copyOf()

    /** Whether the response bytes are exactly [other]. */
    fun contentEquals(other: ByteArray): Boolean = bytes.contentEquals(other)

    /** A copy of the bytes in [span], which must lie inside the response. */
    fun copyOf(span: ByteSpan): ByteArray = bytes.copyOfRange(span.start, span.endExclusive)

    /**
     * Whether [span], which must lie inside the response, holds anything but XML white space, once an
     * enclosing CDATA section's delimiters are set aside. Reads the bytes in place, without a copy.
     */
    internal fun hasContent(span: ByteSpan): Boolean {
        var start = span.start
        var end = span.endExclusive
        while (start < end && isXmlSpace(bytes[start])) start++
        while (end > start && isXmlSpace(bytes[end - 1])) end--
        if (end - start >= CDATA_OPEN.size + CDATA_CLOSE.size &&
            matchesAt(start, CDATA_OPEN) && matchesAt(end - CDATA_CLOSE.size, CDATA_CLOSE)
        ) {
            start += CDATA_OPEN.size
            end -= CDATA_CLOSE.size
            while (start < end && isXmlSpace(bytes[start])) start++
        }
        return start < end
    }

    private fun matchesAt(offset: Int, pattern: ByteArray): Boolean =
        pattern.indices.all { bytes[offset + it] == pattern[it] }

    private fun isXmlSpace(b: Byte): Boolean =
        b == SPACE || b == TAB || b == CARRIAGE_RETURN || b == LINE_FEED

    /** Never renders the content. */
    override fun toString(): String = "RawResponse($size bytes)"

    companion object {
        /** Wraps a copy of [bytes]. */
        internal fun of(bytes: ByteArray): RawResponse = RawResponse(bytes.copyOf())

        private const val SPACE: Byte = 0x20
        private const val TAB: Byte = 0x09
        private const val CARRIAGE_RETURN: Byte = 0x0D
        private const val LINE_FEED: Byte = 0x0A
        private val CDATA_OPEN = "<![CDATA[".toByteArray(Charsets.US_ASCII)
        private val CDATA_CLOSE = "]]>".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * One `mensaje` of a response.
 *
 * Severity is [type] (`ERROR`, `ADVERTENCIA`, `INFORMATIVO`); an `ADVERTENCIA` is a warning and
 * never a failure (SRI-08, D-40). [toString] renders only [identifier], which is a protocol code;
 * [additionalInformation] can echo content of the document.
 *
 * @property identifier the SRI code, or an empty string when the message carries none; never invented
 * @property message the text of the message, trimmed
 * @property additionalInformation `informacionAdicional`, trimmed, or `null` when absent or empty
 * @property type `tipo`, trimmed, or `null` when absent or empty
 */
data class SriMessage(
    val identifier: String,
    val message: String,
    val additionalInformation: String?,
    val type: String?,
) {
    override fun toString(): String = "SriMessage(identifier=$identifier)"
}

/** The `estado` of a reception answer, matched leniently (D-40). */
sealed interface ReceptionState {
    /** `RECIBIDA`: accepted for processing. This is not an authorization. */
    data object Received : ReceptionState

    /** `DEVUELTA`: returned; the document must be corrected and resubmitted. */
    data object Returned : ReceptionState

    /** Anything else, with the trimmed text as received. Never success. */
    data class Unknown(val raw: String) : ReceptionState
}

/**
 * One `comprobante` of a reception answer.
 *
 * @property accessKey `claveAcceso`, trimmed, or `null` when the answer carries none
 * @property messages the messages reported for this document
 */
class ReceivedDocument(val accessKey: String?, val messages: List<SriMessage>) {
    /** Never renders the access key, which contains the issuer's RUC. */
    override fun toString(): String = "ReceivedDocument(messages=${messages.size})"
}

/**
 * The parsed answer to `validarComprobante`.
 *
 * @property raw the exact response bytes
 * @property state the matched `estado`
 * @property documents the `comprobante` entries; none for an empty or absent `comprobantes`
 */
class ReceptionResponse(
    val raw: RawResponse,
    val state: ReceptionState,
    val documents: List<ReceivedDocument>,
) {
    /** Every document's messages, in response order. */
    val messages: List<SriMessage> get() = documents.flatMap { it.messages }

    /** Never renders the content. */
    override fun toString(): String = "ReceptionResponse(state=$state, documents=${documents.size})"
}

/**
 * The `estado` of one `autorizacion`, matched leniently (D-40): trimmed, internal whitespace
 * collapsed, upper-cased with `Locale.ROOT`.
 */
sealed interface AuthorizationState {
    /** `AUTORIZADO`. */
    data object Authorized : AuthorizationState

    /** `NO AUTORIZADO`, `RECHAZADO` or `RECHAZADA`, with the trimmed text as received. */
    data class NotAuthorized(val raw: String) : AuthorizationState

    /** Anything else, including a missing or empty `estado`. Never success. */
    data class Unknown(val raw: String) : AuthorizationState
}

/**
 * One `autorizacion` of an authorization answer.
 *
 * The bytes of the element and of its `comprobante` come from the raw response through the spans
 * `RawScan` found, never from a parsed tree (D-41): CDATA versus escaped form and CR LF survive.
 *
 * @property state the matched `estado`
 * @property number `numeroAutorizacion`, trimmed, or `null` (absent for a rejected document)
 * @property authorizedAtRaw `fechaAutorizacion` exactly as received, trimmed, or `null`
 * @property authorizedAt [authorizedAtRaw] parsed (D-43), or `null` when it does not parse
 * @property environmentRaw `ambiente` as received, for example `PRUEBAS`, or `null`
 * @property messages the messages of this authorization
 * @property inheritsNamespace whether [elementOctets] depends on a namespace declaration outside
 *   the element; when `true` the slice is not a standalone XML element
 */
class Authorization internal constructor(
    val state: AuthorizationState,
    val number: String?,
    val authorizedAtRaw: String?,
    val authorizedAt: OffsetDateTime?,
    val environmentRaw: String?,
    val messages: List<SriMessage>,
    val inheritsNamespace: Boolean,
    private val response: RawResponse,
    private val elementSpan: ByteSpan,
    private val comprobanteSpan: ByteSpan?,
) {
    /** The exact `<autorizacion>...</autorizacion>` bytes as received. */
    fun elementOctets(): ByteArray = response.copyOf(elementSpan)

    /**
     * The exact content bytes of the `comprobante` child as received (a CDATA section with its
     * delimiters, or escaped text), or `null` when the element has no `comprobante`.
     */
    fun comprobanteOctets(): ByteArray? = comprobanteSpan?.let { response.copyOf(it) }

    /**
     * Whether the entry carries both what the deliverable `<autorizacion>` envelope needs: the authorization
     * number and a signed comprobante with content. A `comprobante` element that is self-closing, empty or
     * holds only white space (or an empty CDATA section) is a truncated answer, not a document.
     */
    internal val isComplete: Boolean
        get() = number != null && comprobanteSpan != null && response.hasContent(comprobanteSpan)

    /** Never renders the number, the date, the messages or the document. */
    override fun toString(): String = "Authorization(state=$state, messages=${messages.size})"
}

/**
 * The single answer derived from the list of authorizations (D-39). Phase 6 may apply another
 * rule; the list stays the primary result.
 */
sealed interface AuthorizationSummary {
    /**
     * The entry at [index] is `AUTORIZADO` and carries a number and a comprobante with content; the first such entry.
     * An `AUTORIZADO` entry that lacks either is never reported here, see [AuthorizedIncomplete].
     */
    data class Authorized(val index: Int) : AuthorizationSummary

    /**
     * The first `AUTORIZADO` entry, at [index], lacks the authorization number or a non-empty comprobante, and no
     * complete authorized entry exists. A truncated or malformed answer looks like this. The deliverable
     * envelope cannot be built from it, and the document must not be treated as authorized yet: Phase 6
     * reconciles by asking again, as for [Undetermined]. It is not a rejection.
     */
    data class AuthorizedIncomplete(val index: Int) : AuthorizationSummary

    /** The deciding entry at [index] is not authorized. */
    data class NotAuthorized(val index: Int) : AuthorizationSummary

    /** The deciding entry at [index] has an unrecognised state. */
    data class Undetermined(val index: Int) : AuthorizationSummary

    /**
     * No entry at all: the answer is not available yet. Phase 6 reads this as pending. It is not
     * a rejection and must never be treated as [NotAuthorized].
     */
    data object NoAuthorizations : AuthorizationSummary
}

/**
 * The parsed answer to `autorizacionComprobante`.
 *
 * @property raw the exact response bytes
 * @property queriedAccessKey `claveAccesoConsultada`, trimmed, or `null`
 * @property declaredCount `numeroComprobantes` as an integer, or `null` when absent or not a number
 * @property authorizations every `autorizacion` in SRI order
 * @property summary the derived answer, see [AuthorizationSummary]
 */
class AuthorizationResponse(
    val raw: RawResponse,
    val queriedAccessKey: String?,
    val declaredCount: Int?,
    val authorizations: List<Authorization>,
    val summary: AuthorizationSummary,
) {
    /**
     * Whether the count the SRI declared agrees with the entries found. A response that declares
     * no count cannot disagree with itself and counts as matching. A mismatch is recorded here and
     * is never an error.
     */
    val declaredCountMatches: Boolean get() = declaredCount == null || declaredCount == authorizations.size

    /** Never renders the content. */
    override fun toString(): String =
        "AuthorizationResponse(authorizations=${authorizations.size}, summary=$summary)"
}

/**
 * Why an answer could not be read as a reception or authorization response.
 *
 * Every variant is transport-class (D-44): the outcome of the submission is unknown, and Phase 6
 * must reconcile before doing anything else. None of them is ever a rejection of the document.
 * Each keeps the exact response bytes for the audit trail, and none renders its content.
 */
sealed interface ResponseProblem {
    /** The exact bytes that were received. */
    val raw: RawResponse

    /** A SOAP `Fault` under `Body`. */
    class SoapFault(
        val code: String?,
        val reason: String?,
        override val raw: RawResponse,
    ) : ResponseProblem {
        /** Never renders [reason], which can echo content. */
        override fun toString(): String = "SoapFault(${raw.size} bytes)"
    }

    /** Well-formed XML that does not contain the expected response element. */
    class UnexpectedBody(override val raw: RawResponse) : ResponseProblem {
        override fun toString(): String = "UnexpectedBody(${raw.size} bytes)"
    }

    /** Bytes the guard refused, XML that is not well formed, or structure over the limits. */
    class Malformed(val reason: MalformedReason, override val raw: RawResponse) : ResponseProblem {
        override fun toString(): String = "Malformed($reason, ${raw.size} bytes)"
    }
}
