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

import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document

/**
 * The probe's refusal to touch anything but the SRI test environment.
 *
 * The two facts that decide the environment are held here as code constants and the endpoints are
 * built from them, so a typo in the owner's configuration file cannot redirect the probe: the
 * configuration has no key that names a host. [requireTestEnvironment] then asserts, immediately
 * before a network call, that the access key, the document and the endpoint all agree on the test
 * environment. Three independent facts must hold; any one of them failing stops the call.
 *
 * This phase must not be the one that discovers what the SRI does with a production-environment
 * document by submitting to production. The production host is never named anywhere in this
 * package, not even in a comment, and the guard test scans the package to keep it that way.
 */
object ProbeEnvironmentGuard {

    private const val TEST_HOST = "celcer.sri.gob.ec"
    private const val SERVICE_PATH = "/comprobantes-electronicos-ws/"

    /** The environment digit's position in the 49-digit access key: 8 + 2 + 13 digits precede it. */
    private const val ENVIRONMENT_INDEX = 23
    private const val ACCESS_KEY_LENGTH = 49
    private const val TEST_ENVIRONMENT = '1'

    val receptionEndpoint: URI = URI("https://$TEST_HOST${SERVICE_PATH}RecepcionComprobantesOffline")
    val authorizationEndpoint: URI = URI("https://$TEST_HOST${SERVICE_PATH}AutorizacionComprobantesOffline")

    /**
     * @throws IllegalArgumentException unless [accessKey] is 49 characters with environment digit
     * `1`, [documentOctets] carries exactly one `ambiente` element whose content is `1`, and
     * [endpoint] is an HTTPS URI on the test host
     */
    fun requireTestEnvironment(accessKey: String, documentOctets: ByteArray, endpoint: URI) {
        require(accessKey.length == ACCESS_KEY_LENGTH) { "refusing: the access key is not $ACCESS_KEY_LENGTH characters long" }
        require(accessKey[ENVIRONMENT_INDEX] == TEST_ENVIRONMENT) { "refusing: the access key's environment digit is not 1" }
        require(endpoint.scheme == "https") { "refusing: the endpoint is not HTTPS" }
        require(endpoint.host == TEST_HOST) { "refusing: the endpoint host is not the test host" }
        val ambiente = ambienteValues(documentOctets)
        require(ambiente == listOf("1")) { "refusing: the document's ambiente element is not exactly one element with content 1" }
    }

    /** The content of every `ambiente` element in the document, in document order. */
    internal fun ambienteValues(documentOctets: ByteArray): List<String> {
        val document = parseHardened(documentOctets)
        val nodes = document.getElementsByTagNameNS("*", "ambiente")
        return List(nodes.length) { nodes.item(it).textContent.trim() }
    }

    /**
     * A namespace-aware parser that refuses DOCTYPE declarations and external entities. Shared by
     * everything in the probe that reads XML it did not write.
     */
    internal fun parseHardened(octets: ByteArray): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(octets))
    }
}
