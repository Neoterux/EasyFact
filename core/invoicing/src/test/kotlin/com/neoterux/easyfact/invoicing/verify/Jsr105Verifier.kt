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
import java.security.PublicKey
import java.security.cert.X509Certificate
import javax.xml.XMLConstants
import javax.xml.crypto.AlgorithmMethod
import javax.xml.crypto.KeySelector
import javax.xml.crypto.KeySelectorResult
import javax.xml.crypto.XMLCryptoContext
import javax.xml.crypto.dsig.XMLSignature
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.keyinfo.KeyInfo
import javax.xml.crypto.dsig.keyinfo.X509Data
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The independent oracle: the JDK's own XML-DSig (JSR-105) implementation validating bytes that
 * the project's signer produced. It shares no code with the signer, so agreement is evidence and
 * not a restatement.
 *
 * Test source only. `javax.xml.crypto` is in the JDK and absent from Android, which is why the
 * portable-boundary gate forbids it in main source.
 */
object Jsr105Verifier {

    private const val ETSI_NAMESPACE = "http://uri.etsi.org/01903/v1.3.2#"

    /** How the JDK judged a document. [MALFORMED] is kept apart so a parse failure never reads as a rejection. */
    enum class Verdict { VALID, CORE_INVALID, MALFORMED }

    fun validate(octets: ByteArray): Boolean = verdict(octets) == Verdict.VALID

    fun verdict(octets: ByteArray): Verdict {
        return try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(octets))
            // The root carries a lowercase `id`; the signature's own elements carry `Id`.
            document.documentElement.setIdAttribute("id", true)
            for (namespace in listOf(XMLSignature.XMLNS, ETSI_NAMESPACE)) {
                val elements = document.getElementsByTagNameNS(namespace, "*")
                for (index in 0 until elements.length) {
                    val element = elements.item(index) as Element
                    if (element.hasAttribute("Id")) element.setIdAttribute("Id", true)
                }
            }
            val signatures = document.getElementsByTagNameNS(XMLSignature.XMLNS, "Signature")
            if (signatures.length != 1) return Verdict.MALFORMED

            val selector = object : KeySelector() {
                override fun select(info: KeyInfo, purpose: Purpose, method: AlgorithmMethod, context: XMLCryptoContext): KeySelectorResult {
                    val certificate = info.content.filterIsInstance<X509Data>()
                        .flatMap { it.content }
                        .filterIsInstance<X509Certificate>()
                        .first()
                    return KeySelectorResult { certificate.publicKey as PublicKey }
                }
            }
            val context = DOMValidateContext(selector, signatures.item(0)).apply {
                // The JDK rejects `rsa-sha1` in secure-validation mode. SHA-1 here is the algorithm
                // the SRI mandates for its signatures, not a lapse, so this verifier has to accept it.
                // The relaxation lives in this test-source file only.
                setProperty("org.jcp.xml.dsig.secureValidation", false)
            }
            val signature = XMLSignatureFactory.getInstance("DOM").unmarshalXMLSignature(context)
            if (signature.validate(context)) Verdict.VALID else Verdict.CORE_INVALID
        } catch (e: Exception) {
            Verdict.MALFORMED
        }
    }
}
