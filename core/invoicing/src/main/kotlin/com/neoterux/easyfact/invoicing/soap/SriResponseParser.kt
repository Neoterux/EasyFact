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

import com.neoterux.easyfact.invoicing.result.Outcome
import java.io.ByteArrayInputStream
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.helpers.DefaultHandler

/**
 * Reads the SRI's two SOAP answers into typed values without ever throwing and without ever
 * mistaking a transport problem for a verdict on the document (SRI-08, D-39..D-44).
 *
 * The shapes are those of the technical sheet (Ficha) v2.34, pages 17-19: reception is
 * `Envelope/Body/validarComprobanteResponse/RespuestaRecepcionComprobante/{estado, comprobantes/...}`
 * and authorization is `.../autorizacionComprobanteResponse/RespuestaAutorizacionComprobante/
 * {claveAccesoConsultada, numeroComprobantes, autorizaciones/autorizacion/...}`. Only local names
 * are matched, so neither the namespace prefixes nor the wrapper name matters: the Ficha's XML
 * element is `RespuestaRecepcionComprobante` and its Java return type is `RespuestaSolicitud`, and
 * both are read.
 *
 * The order is fixed. [ResponseGuard] runs first and is the actual XXE and entity-expansion
 * control, because it does not depend on which parser features a platform accepts. The structural
 * read then uses JAXP SAX (never StAX, absent on Android, and never XmlPull, absent from the JDK)
 * with the platform's secure-processing switch in a guarded try and a resolver that refuses every
 * external entity: defence in depth, whose behaviour on ART is proven by the device test of plan
 * 03-10. The tree it builds is bounded in depth and in element count, because the guard bounds
 * only bytes.
 *
 * What the parsed tree cannot give is kept out of it. A parser decodes CDATA and escaped text to
 * the same string and rewrites CR LF, so the bytes of each `autorizacion` and of its `comprobante`
 * come from [RawScan] spans into the response (D-41), cross-checked against the structural count.
 */
object SriResponseParser {

    /** A real response nests about ten levels deep. */
    private const val MAX_DEPTH = RawScan.MAX_ELEMENT_DEPTH

    /**
     * A real response holds a few thousand elements at the very most (twelve entries and their
     * messages). The cap keeps a body of nothing but empty elements, which the byte guard allows
     * up to its size limit, from becoming millions of tree nodes on a phone.
     */
    private const val MAX_ELEMENTS = 50_000

    private const val LEGACY_DATE_PATTERN = "dd/MM/uuuu HH:mm:ss"
    private const val LEGACY_DATE_ZONE = "America/Guayaquil"

    /** Reads an answer to `validarComprobante`. No exception escapes. */
    fun parseReception(bytes: ByteArray): Outcome<ReceptionResponse, ResponseProblem> {
        val raw = RawResponse.of(bytes)
        val body = when (val result = bodyOf(bytes, raw)) {
            is Body.Problem -> return Outcome.Err(result.problem)
            is Body.Found -> result.node
        }
        val node = firstDescendant(
            body,
            skip = { it.name == "autorizacion" },
            match = { it.child("estado") != null },
        ) ?: return Outcome.Err(ResponseProblem.UnexpectedBody(raw))

        val stateRaw = node.child("estado")?.value().orEmpty()
        val state = when (normalise(stateRaw)) {
            "RECIBIDA" -> ReceptionState.Received
            "DEVUELTA" -> ReceptionState.Returned
            else -> ReceptionState.Unknown(stateRaw)
        }
        val documents = node.child("comprobantes")?.childrenNamed("comprobante").orEmpty().map { comprobante ->
            ReceivedDocument(comprobante.child("claveAcceso")?.value(), messagesOf(comprobante))
        }
        return Outcome.Ok(ReceptionResponse(raw, state, documents))
    }

    /** Reads an answer to `autorizacionComprobante`. No exception escapes. */
    fun parseAuthorization(bytes: ByteArray): Outcome<AuthorizationResponse, ResponseProblem> {
        val raw = RawResponse.of(bytes)
        val body = when (val result = bodyOf(bytes, raw)) {
            is Body.Problem -> return Outcome.Err(result.problem)
            is Body.Found -> result.node
        }
        val node = firstDescendant(
            body,
            skip = { false },
            match = {
                it.child("claveAccesoConsultada") != null ||
                    it.child("numeroComprobantes") != null ||
                    it.child("autorizaciones") != null
            },
        ) ?: return Outcome.Err(ResponseProblem.UnexpectedBody(raw))

        val nodes = node.child("autorizaciones")?.childrenNamed("autorizacion").orEmpty()
        val slices = RawScan.authorizations(bytes)
            ?: return Outcome.Err(ResponseProblem.Malformed(MalformedReason.NOT_WELL_FORMED, raw))
        if (slices.size != nodes.size) {
            return Outcome.Err(ResponseProblem.Malformed(MalformedReason.RAW_SLICE_MISMATCH, raw))
        }

        val authorizations = nodes.mapIndexed { index, entry ->
            val slice = slices[index]
            val dateRaw = entry.child("fechaAutorizacion")?.value()
            Authorization(
                state = authorizationState(entry.child("estado")?.value().orEmpty()),
                number = entry.child("numeroAutorizacion")?.value(),
                authorizedAtRaw = dateRaw,
                authorizedAt = parseDate(dateRaw),
                environmentRaw = entry.child("ambiente")?.value(),
                messages = messagesOf(entry),
                inheritsNamespace = slice.inheritsNamespace,
                response = raw,
                elementSpan = slice.element,
                comprobanteSpan = slice.comprobanteContent,
            )
        }
        return Outcome.Ok(
            AuthorizationResponse(
                raw = raw,
                queriedAccessKey = node.child("claveAccesoConsultada")?.value(),
                declaredCount = node.child("numeroComprobantes")?.value()?.toIntOrNull(),
                authorizations = authorizations,
                summary = summarise(authorizations),
            ),
        )
    }

    // --- Body and fault ---------------------------------------------------------------------

    private sealed interface Body {
        class Found(val node: Node) : Body
        class Problem(val problem: ResponseProblem) : Body
    }

    /** The guard, the structural read, the `Body` element and the fault check, in that order. */
    private fun bodyOf(bytes: ByteArray, raw: RawResponse): Body {
        ResponseGuard.check(bytes)?.let { return Body.Problem(ResponseProblem.Malformed(it, raw)) }
        val root = readTree(bytes)
            ?: return Body.Problem(ResponseProblem.Malformed(MalformedReason.NOT_WELL_FORMED, raw))
        val body = root.child("Body") ?: return Body.Problem(ResponseProblem.UnexpectedBody(raw))
        val fault = body.child("Fault")
        if (fault != null) {
            // SOAP 1.1 writes faultcode and faultstring; SOAP 1.2 writes Code/Value and Reason/Text.
            val code = fault.child("faultcode")?.value() ?: fault.child("Code")?.child("Value")?.value()
            val reason = fault.child("faultstring")?.value() ?: fault.child("Reason")?.child("Text")?.value()
            return Body.Problem(ResponseProblem.SoapFault(code, reason, raw))
        }
        return Body.Found(body)
    }

    // --- Messages ---------------------------------------------------------------------------

    /**
     * The `mensaje` elements directly under the owner's `mensajes`. The wrapper and its text
     * element share a name, so the text is read from the wrapper's own `mensaje` child, one level
     * down; collecting `mensaje` at any depth would turn that text element into a second message.
     */
    private fun messagesOf(owner: Node): List<SriMessage> =
        owner.child("mensajes")?.childrenNamed("mensaje").orEmpty().map { wrapper ->
            SriMessage(
                identifier = wrapper.child("identificador")?.value().orEmpty(),
                message = wrapper.child("mensaje")?.value().orEmpty(),
                additionalInformation = wrapper.child("informacionAdicional")?.value(),
                type = wrapper.child("tipo")?.value(),
            )
        }

    // --- States and summary -----------------------------------------------------------------

    private fun authorizationState(raw: String): AuthorizationState = when (normalise(raw)) {
        "AUTORIZADO" -> AuthorizationState.Authorized
        "NO AUTORIZADO", "RECHAZADO", "RECHAZADA" -> AuthorizationState.NotAuthorized(raw)
        else -> AuthorizationState.Unknown(raw)
    }

    /**
     * Trims, collapses every whitespace run to one space and upper-cases with [Locale.ROOT]. The
     * default locale must never decide this: a Turkish default maps `i` to a dotted capital I and
     * would turn `autorizado` into something that is not `AUTORIZADO`.
     */
    private fun normalise(raw: String): String {
        val builder = StringBuilder(raw.length)
        var gap = false
        for (character in raw.trim()) {
            if (character.isWhitespace()) {
                gap = true
            } else {
                if (gap) builder.append(' ')
                gap = false
                builder.append(character)
            }
        }
        return builder.toString().uppercase(Locale.ROOT)
    }

    /**
     * D-39. No entry is [AuthorizationSummary.NoAuthorizations]. An authorized entry wins, the
     * first one if there are several. Otherwise the latest by `fechaAutorizacion` decides when
     * every entry has a parsed date (a tie goes to the later position), and the last entry in SRI
     * order decides when any date is missing.
     */
    private fun summarise(authorizations: List<Authorization>): AuthorizationSummary {
        if (authorizations.isEmpty()) return AuthorizationSummary.NoAuthorizations
        val authorized = authorizations.indexOfFirst { it.state == AuthorizationState.Authorized }
        if (authorized >= 0) return AuthorizationSummary.Authorized(authorized)

        val dates = authorizations.map { it.authorizedAt?.toInstant() }
        var decisive = authorizations.lastIndex
        if (dates.all { it != null }) {
            decisive = 0
            for (index in 1..authorizations.lastIndex) {
                if (dates[index]!! >= dates[decisive]!!) decisive = index
            }
        }
        return when (authorizations[decisive].state) {
            is AuthorizationState.NotAuthorized -> AuthorizationSummary.NotAuthorized(decisive)
            else -> AuthorizationSummary.Undetermined(decisive)
        }
    }

    /**
     * D-43: ISO with an offset, else `dd/MM/yyyy HH:mm:ss` read as Guayaquil time; anything else,
     * or an impossible date, is `null` and never an error.
     */
    private fun parseDate(raw: String?): OffsetDateTime? {
        if (raw == null) return null
        try {
            return OffsetDateTime.parse(raw)
        } catch (ignored: DateTimeException) {
            // Not ISO with an offset; the day-first form is tried next.
        }
        return try {
            val formatter = DateTimeFormatter.ofPattern(LEGACY_DATE_PATTERN, Locale.ROOT)
                .withResolverStyle(ResolverStyle.STRICT)
            LocalDateTime.parse(raw, formatter).atZone(ZoneId.of(LEGACY_DATE_ZONE)).toOffsetDateTime()
        } catch (ignored: DateTimeException) {
            null
        }
    }

    // --- Structural read --------------------------------------------------------------------

    /** One element of the tree: its local name, its children and its own character data. */
    private class Node(val name: String, val parent: Node?, val depth: Int) {
        val children = ArrayList<Node>()
        val text = StringBuilder()

        /** The signed document inside an `autorizacion` is served from the raw slice, never from here. */
        val skipsText: Boolean = name == "comprobante" && parent?.name == "autorizacion"

        fun child(childName: String): Node? = children.firstOrNull { it.name == childName }

        fun childrenNamed(childName: String): List<Node> = children.filter { it.name == childName }

        /** The trimmed text, or `null` when there is none. */
        fun value(): String? = text.toString().trim().ifEmpty { null }
    }

    /** The first element below [from], in document order, that satisfies [match] and is not inside a [skip] element. */
    private fun firstDescendant(from: Node, skip: (Node) -> Boolean, match: (Node) -> Boolean): Node? {
        for (child in from.children) {
            if (skip(child)) continue
            if (match(child)) return child
            firstDescendant(child, skip, match)?.let { return it }
        }
        return null
    }

    private class TreeBuilder : DefaultHandler() {
        var root: Node? = null
        private var current: Node? = null
        private var count = 0

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val parent = current
            val depth = if (parent == null) 1 else parent.depth + 1
            if (depth > MAX_DEPTH) throw SAXException("element nesting is too deep")
            count++
            if (count > MAX_ELEMENTS) throw SAXException("too many elements")
            val name = localName.orEmpty().ifEmpty { qName.orEmpty().substringAfterLast(':') }
            val node = Node(name, parent, depth)
            if (parent == null) root = node else parent.children.add(node)
            current = node
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            current = current?.parent
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            val node = current ?: return
            if (!node.skipsText) node.text.append(ch, start, length)
        }
    }

    /** The document element, or `null` when the bytes are not well-formed XML or exceed the structural limits. */
    private fun readTree(bytes: ByteArray): Node? {
        val builder = TreeBuilder()
        try {
            val factory = SAXParserFactory.newInstance()
            factory.isNamespaceAware = true
            try {
                factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            } catch (ignored: ParserConfigurationException) {
                // The platform does not offer the switch; the byte guard is the control.
            } catch (ignored: SAXNotRecognizedException) {
                // As above.
            } catch (ignored: SAXNotSupportedException) {
                // As above.
            }
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = EntityResolver { _, _ -> throw SAXException("external entity refused") }
            reader.contentHandler = builder
            reader.errorHandler = builder
            reader.parse(InputSource(ByteArrayInputStream(bytes)))
        } catch (exception: Exception) {
            // SAXException and IOException are the expected refusals. A parser that fails on odd
            // input with a runtime exception is the same answer: this function never throws.
            return null
        }
        return builder.root
    }
}
