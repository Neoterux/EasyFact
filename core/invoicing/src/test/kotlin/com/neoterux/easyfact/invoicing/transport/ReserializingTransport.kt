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

package com.neoterux.easyfact.invoicing.transport

import com.neoterux.easyfact.invoicing.probe.ThrowawaySoap
import com.neoterux.easyfact.invoicing.signing.SignedDocument
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * **This class exists to fail.** It parses the signed octets into a DOM and serializes them again
 * before encoding — exactly the mistake AGENTS.md section 6.2 forbids — and it is the committed
 * proof that [ByteIdentityTest] can detect that mistake rather than merely assert against it.
 *
 * It must never be used by production code or by the probe. A gate that has never been seen to
 * fail is not evidence; this is how the byte-identity gate is seen to fail.
 *
 * ## Two modes, because the round trip fails in two different ways
 *
 * [reformat] `false` is the conservative round trip: parse, serialize, add no whitespace. It is
 * the most favourable case a re-serializing transport could hope for, and it **still** does not
 * return the bytes it was given. Measured against this project's signed factura, it drops the LF
 * after the XML declaration, writes `standalone="no"` into it, and collapses every empty element
 * — `<ds:CanonicalizationMethod .../>`, `<ds:Transform .../>`, `<ds:DigestMethod .../>` — into
 * the self-closing form the canonical writer deliberately never emits.
 *
 * None of those changes break the signature, because inclusive C14N normalises all of them away
 * again before the digest is taken. **That is the point.** A verifier saying VALID does not mean
 * the transmitted octets are the signed octets, so SRI-05 has to be asserted on the bytes
 * themselves. The conservative mode is what makes that statement evidence instead of an opinion.
 *
 * [reformat] `true` is the formatter: the same round trip with indentation switched on. The
 * inserted whitespace text nodes are inside the digested root and survive canonicalization, so
 * both verifiers reject the result. This is the mode that answers "would a difference that
 * reaches the signature actually be caught?".
 */
class ReserializingTransport(private val reformat: Boolean = false) : DocumentTransport {

    private var recorded: ByteArray? = null

    /** The octets handed to the base64 encoder on the last [submit] — the re-serialized ones. */
    val encodedOctets: ByteArray
        get() = checkNotNull(recorded) { "nothing has been submitted yet" }.copyOf()

    override fun submit(document: SignedDocument): String {
        val reserialized = parseAndSerialize(document.octets())
        recorded = reserialized
        return ThrowawaySoap.recepcionEnvelope(reserialized)
    }

    /** [octets] through a `DocumentBuilder` and back out through an identity `Transformer`. */
    private fun parseAndSerialize(octets: ByteArray): ByteArray {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val parsed = factory.newDocumentBuilder().parse(ByteArrayInputStream(octets))
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            if (reformat) {
                setOutputProperty(OutputKeys.INDENT, "yes")
                setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            }
        }
        val out = ByteArrayOutputStream()
        transformer.transform(DOMSource(parsed), StreamResult(out))
        return out.toByteArray()
    }
}
