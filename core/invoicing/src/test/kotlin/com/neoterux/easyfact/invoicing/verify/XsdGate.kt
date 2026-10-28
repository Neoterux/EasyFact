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

package com.neoterux.easyfact.invoicing.verify

import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.Schema
import javax.xml.validation.SchemaFactory
import org.w3c.dom.ls.LSInput
import org.w3c.dom.ls.LSResourceResolver
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

/**
 * Validates a document against the factura 1.1.0 schema vendored under `xsd/` in the test
 * resources (provenance and digest in `SCHEMA-PROVENANCE.md` beside it).
 *
 * **What it validates.** The UNSIGNED canonical document, [com.neoterux.easyfact.invoicing.xml.CanonicalDocument.octets].
 * The vendored revision declares no `ds:Signature` inside `<factura>`, so a signed document cannot
 * pass it; the signature is covered by the independent JSR-105 and xades4j verifiers instead. Feed
 * this gate signed output and it fails for that reason, which says nothing about the signature.
 *
 * **What it proves.** The document matches the schema the SRI publishes: element names, element
 * order, required elements, and the length, digit-count and pattern facets.
 *
 * **What it does not prove.** That the SRI will accept the document. Schema-valid documents are
 * rejected for business reasons every day, which is why the round-trip probe exists.
 *
 * **Offline.** External DTD access is the empty string and external schema access is `file`, the
 * schema and its imports are served by an [LSResourceResolver] from the classpath rather than a
 * `File` built from a resource URL (which breaks as soon as resources are packaged), and the
 * document parser disallows `DOCTYPE` and external entities. This is a configuration, not a proof
 * of no egress.
 *
 * **The W3C xmldsig schema is deliberately not vendored.** The factura schema imports it but never
 * references a `ds:` component, so the unresolved import is reported by the schema compiler as a
 * warning and nothing depends on it. [schemaWarnings] exposes every warning so a test can assert
 * that the unresolved import is the only one; the day the schema starts using `ds:`, compilation
 * fails loudly instead of silently validating less.
 */
object XsdGate {

    private const val SCHEMA_RESOURCE_DIRECTORY = "/xsd/"
    private const val SCHEMA_NAME = "factura_V1.1.0.xsd"

    /**
     * A system id that is not a file URL, so that a relative import which the resolver does not
     * serve cannot be satisfied by a stray file next to the working directory.
     */
    private const val SCHEMA_SYSTEM_ID = "classpath:$SCHEMA_RESOURCE_DIRECTORY$SCHEMA_NAME"

    private val warnings = mutableListOf<String>()

    /** A schema compiled once; compilation warnings are captured into [schemaWarnings]. */
    private val schema: Schema by lazy { compile() }

    /** Warnings raised while compiling the schema, in order. */
    val schemaWarnings: List<String>
        get() {
            schema // force compilation
            return warnings.toList()
        }

    /** Throws [SAXException] carrying every violation when [octets] does not match the schema. */
    fun validate(octets: ByteArray) {
        val errors = validationErrors(octets)
        if (errors.isNotEmpty()) throw SAXException(errors.joinToString("\n"))
    }

    /** Every violation joined by newlines, or `null` when [octets] is schema-valid. */
    fun validationErrorOrNull(octets: ByteArray): String? =
        validationErrors(octets).takeIf { it.isNotEmpty() }?.joinToString("\n")

    /** All violations, not only the first. Empty means schema-valid. */
    fun validationErrors(octets: ByteArray): List<String> {
        val errors = mutableListOf<String>()
        val document = try {
            parser().parse(ByteArrayInputStream(octets))
        } catch (e: SAXException) {
            return listOf("not well-formed or not permitted: ${e.message}")
        }
        val validator = schema.newValidator()
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        validator.errorHandler = Collecting { errors += it }
        validator.validate(DOMSource(document))
        return errors
    }

    private fun parser() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder().apply {
        // The default handler prints to stderr; a malformed document must surface as an exception.
        setErrorHandler(Collecting { throw SAXException(it) })
    }

    private fun compile(): Schema {
        val factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI)
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file")
        factory.resourceResolver = ClasspathResolver
        factory.errorHandler = object : ErrorHandler {
            override fun warning(e: SAXParseException) {
                warnings += "${e.message} (${e.systemId})"
            }

            override fun error(e: SAXParseException): Unit = throw e

            override fun fatalError(e: SAXParseException): Unit = throw e
        }
        val resource = checkNotNull(XsdGate::class.java.getResourceAsStream(SCHEMA_RESOURCE_DIRECTORY + SCHEMA_NAME)) {
            "the vendored schema is missing from the test resources: $SCHEMA_NAME"
        }
        return resource.use { factory.newSchema(StreamSource(it, SCHEMA_SYSTEM_ID)) }
    }

    /** Serves vendored files by name; anything else is left to the (restricted) default. */
    private object ClasspathResolver : LSResourceResolver {
        override fun resolveResource(
            type: String?,
            namespaceURI: String?,
            publicId: String?,
            systemId: String?,
            baseURI: String?,
        ): LSInput? {
            val name = systemId?.substringAfterLast('/') ?: return null
            val stream = XsdGate::class.java.getResourceAsStream(SCHEMA_RESOURCE_DIRECTORY + name) ?: return null
            return StreamInput(systemId, stream)
        }
    }

    private class StreamInput(private var id: String?, private val stream: java.io.InputStream) : LSInput {
        override fun getByteStream() = stream
        override fun setByteStream(byteStream: java.io.InputStream?) = Unit
        override fun getSystemId() = id
        override fun setSystemId(systemId: String?) { id = systemId }
        override fun getCharacterStream(): java.io.Reader? = null
        override fun setCharacterStream(characterStream: java.io.Reader?) = Unit
        override fun getStringData(): String? = null
        override fun setStringData(stringData: String?) = Unit
        override fun getPublicId(): String? = null
        override fun setPublicId(publicId: String?) = Unit
        override fun getBaseURI(): String? = null
        override fun setBaseURI(baseURI: String?) = Unit
        override fun getEncoding(): String? = null
        override fun setEncoding(encoding: String?) = Unit
        override fun getCertifiedText() = false
        override fun setCertifiedText(certifiedText: Boolean) = Unit
    }

    /** Records every error and fatal error; warnings are not violations. */
    private class Collecting(private val sink: (String) -> Unit) : ErrorHandler {
        override fun warning(e: SAXParseException) = Unit
        override fun error(e: SAXParseException) = sink(describe(e))
        override fun fatalError(e: SAXParseException) = sink(describe(e))

        private fun describe(e: SAXParseException) = e.message ?: e.toString()
    }
}
