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

package com.neoterux.easyfact.invoicing.probe

import java.io.StringWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The probe's throwaway SOAP client: two fixed envelope templates, one HTTP call each, and a
 * reader that extracts only the state strings and the message pairs.
 *
 * It is allowed to be ugly. The production codec is a later phase's work and must not grow out of
 * this file; none of it may move to main source, because `java.net.http` and JAXP are absent from
 * the Android API 31 subset.
 *
 * The endpoints are never parameters: they come from [ProbeEnvironmentGuard], and every network
 * call asserts the test environment again at the point of the call, so reordering the probe's own
 * steps cannot bypass the guard.
 */
class ThrowawaySoap(private val transport: Transport = JdkTransport()) {

    /** One HTTP exchange: POST [body] to [endpoint] and return the response body. */
    fun interface Transport {
        fun post(endpoint: URI, body: String): String
    }

    /** One `mensaje` of a response. Values are trimmed; the SRI pads some of them. */
    data class Message(val code: String, val text: String, val type: String, val additionalInformation: String)

    /**
     * What a response said, and nothing more. [states] holds every `estado` in document order (a
     * reception answer has one, an authorization answer has one per `autorizacion`), and
     * [messages] every message with an identifier, wherever it is nested. [fault] is the SOAP
     * fault string when the server answered with one.
     */
    data class Reading(val states: List<String>, val messages: List<Message>, val fault: String?)

    /** Submits [signedOctets] to reception and returns the raw response. */
    fun validarComprobante(accessKey: String, signedOctets: ByteArray): String {
        ProbeEnvironmentGuard.requireTestEnvironment(accessKey, signedOctets, ProbeEnvironmentGuard.receptionEndpoint)
        return transport.post(ProbeEnvironmentGuard.receptionEndpoint, recepcionEnvelope(signedOctets))
    }

    /** Asks authorization about [accessKey] and returns the raw response. */
    fun autorizacionComprobante(accessKey: String, signedOctets: ByteArray): String {
        ProbeEnvironmentGuard.requireTestEnvironment(accessKey, signedOctets, ProbeEnvironmentGuard.authorizationEndpoint)
        return transport.post(ProbeEnvironmentGuard.authorizationEndpoint, autorizacionEnvelope(accessKey))
    }

    companion object {
        private const val ENVELOPE_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/"
        private const val RECEPTION_NAMESPACE = "http://ec.gob.sri.ws.recepcion"
        private const val AUTHORIZATION_NAMESPACE = "http://ec.gob.sri.ws.autorizacion"

        /**
         * The reception envelope. The payload is the base64 of the signed octets and nothing
         * else: the encoder is `java.util.Base64`'s basic one, which never inserts line breaks.
         */
        fun recepcionEnvelope(signedOctets: ByteArray): String =
            "<soapenv:Envelope xmlns:soapenv=\"$ENVELOPE_NAMESPACE\" xmlns:ec=\"$RECEPTION_NAMESPACE\">" +
                "<soapenv:Body><ec:validarComprobante><xml>" +
                Base64.getEncoder().encodeToString(signedOctets) +
                "</xml></ec:validarComprobante></soapenv:Body></soapenv:Envelope>"

        /** The authorization envelope; `claveAccesoComprobante` is unqualified, as the WSDL says. */
        fun autorizacionEnvelope(accessKey: String): String =
            "<soapenv:Envelope xmlns:soapenv=\"$ENVELOPE_NAMESPACE\" xmlns:ec=\"$AUTHORIZATION_NAMESPACE\">" +
                "<soapenv:Body><ec:autorizacionComprobante><claveAccesoComprobante>" +
                accessKey +
                "</claveAccesoComprobante></ec:autorizacionComprobante></soapenv:Body></soapenv:Envelope>"

        /**
         * Reads the state strings and message pairs out of a response. Tolerant by design: several
         * `autorizacion` elements, padded values, a `RECHAZADO` the prose never mentions, and
         * `ADVERTENCIA` messages are all just data here. The authorized document that an
         * `autorizacion` carries inside `comprobante` is character data and is never read.
         *
         * @throws org.xml.sax.SAXException if the response is not well-formed or carries a DOCTYPE
         */
        fun readState(response: String): Reading {
            val document = ProbeEnvironmentGuard.parseHardened(response.toByteArray(Charsets.UTF_8))
            val states = mutableListOf<String>()
            val messages = mutableListOf<Message>()
            var fault: String? = null

            fun walk(node: Node) {
                if (node is Element) {
                    when (node.localName) {
                        "estado" -> states += node.textContent.trim()
                        "faultstring" -> fault = node.textContent.trim()
                        "mensaje" -> if (child(node, "identificador") != null) {
                            messages += Message(
                                code = child(node, "identificador")!!.textContent.trim(),
                                text = child(node, "mensaje")?.textContent?.trim().orEmpty(),
                                type = child(node, "tipo")?.textContent?.trim().orEmpty(),
                                additionalInformation = child(node, "informacionAdicional")?.textContent?.trim().orEmpty(),
                            )
                        }
                    }
                }
                var next = node.firstChild
                while (next != null) {
                    walk(next)
                    next = next.nextSibling
                }
            }
            walk(document.documentElement)
            return Reading(states, messages, fault)
        }

        private fun child(parent: Element, name: String): Element? {
            var next = parent.firstChild
            while (next != null) {
                if (next is Element && next.localName == name) return next
                next = next.nextSibling
            }
            return null
        }

        /**
         * [response] with every piece of character data and every attribute value removed. Element
         * names, nesting and attribute names survive; namespace declarations keep their URIs, which
         * are protocol constants. What remains describes the shape of an exchange and nothing in it,
         * so it can be committed without a judgement about what counts as identifying.
         *
         * @throws org.xml.sax.SAXException if the response is not well-formed or carries a DOCTYPE
         */
        fun structureOnly(response: String): String {
            val document = ProbeEnvironmentGuard.parseHardened(response.toByteArray(Charsets.UTF_8))

            fun strip(node: Node) {
                var next = node.firstChild
                while (next != null) {
                    val following = next.nextSibling
                    when (next.nodeType) {
                        Node.TEXT_NODE, Node.CDATA_SECTION_NODE, Node.COMMENT_NODE, Node.PROCESSING_INSTRUCTION_NODE ->
                            node.removeChild(next)
                        Node.ELEMENT_NODE -> {
                            val element = next as Element
                            val attributes = element.attributes
                            for (index in 0 until attributes.length) {
                                val attribute = attributes.item(index)
                                val isNamespaceDeclaration = attribute.nodeName == "xmlns" || attribute.nodeName.startsWith("xmlns:")
                                if (!isNamespaceDeclaration) attribute.nodeValue = ""
                            }
                            strip(element)
                        }
                    }
                    next = following
                }
            }
            strip(document.documentElement)

            val transformer = TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }
            val writer = StringWriter()
            transformer.transform(DOMSource(document), StreamResult(writer))
            return writer.toString()
        }
    }

    /** The JDK HTTP client, HTTPS only, no redirects followed. */
    class JdkTransport(
        private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build(),
    ) : Transport {
        override fun post(endpoint: URI, body: String): String {
            val request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build()
            return client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8)).body()
        }
    }
}
