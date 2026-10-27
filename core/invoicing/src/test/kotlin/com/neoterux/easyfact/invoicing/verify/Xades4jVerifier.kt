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
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import xades4j.XAdES4jException
import xades4j.providers.impl.PKIXCertificateValidationProvider
import xades4j.verification.XAdESForm
import xades4j.verification.XadesVerificationProfile

/**
 * The second independent oracle: xades4j judging bytes this project's signer produced.
 *
 * [Jsr105Verifier] proves the XML-DSig layer and cannot see the `QualifyingProperties` at all.
 * This verifier is the only check available here that the document is valid XAdES-**BES** rather
 * than merely a well-formed XML signature (NFR-05, D-21). It shares no code with the signer.
 *
 * Test source only. xades4j and its transitive Apache Santuario are denied on every shipping
 * classpath by `DependencyPolicy`; the dependency is declared `testImplementation`.
 *
 * **This is a structural check of the XAdES form, not a trust decision.** The SRI performs the
 * real trust evaluation, and what it says is observed by the `celcer` probe, not here.
 */
object Xades4jVerifier {

    /**
     * How xades4j judged a document. The four values are kept apart so that a test asserting
     * rejection cannot be satisfied by a crash: [INVALID] means xades4j rejected the signature,
     * while [ERROR] means something unrelated to signature validity went wrong.
     */
    enum class Verdict {
        /** xades4j verified the signature and resolved its form. */
        VALID,

        /** xades4j rejected the document: a digest, a signature value or the certificate path failed. */
        INVALID,

        /** The bytes are not a parseable XML document carrying exactly one `ds:Signature`. */
        MALFORMED,

        /** Anything else. A test that expects rejection must not accept this. */
        ERROR,
    }

    /**
     * What xades4j reported. [failure] is the simple class name of the exception xades4j raised,
     * never its message: a provider message can quote certificate detail (AGENTS.md section 2.2).
     */
    data class Report(
        val verdict: Verdict,
        val form: XAdESForm?,
        val references: Int,
        val signedDataObjects: Int,
        val failure: String?,
    )

    private const val DSIG_NAMESPACE = "http://www.w3.org/2000/09/xmldsig#"
    private const val ETSI_NAMESPACE = "http://uri.etsi.org/01903/v1.3.2#"

    /** Verifies [octets] against [trustAnchor] as the single trusted certificate. */
    fun verify(octets: ByteArray, trustAnchor: X509Certificate): Report {
        val signature = signatureElement(octets)
            ?: return Report(Verdict.MALFORMED, null, 0, 0, null)
        return try {
            // One trusted entry: the certificate the caller nominates. A PKCS#12 store held in
            // memory, never written anywhere (AGENTS.md section 6.4).
            val trust = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("anchor", trustAnchor)
            }
            // Revocation checking is off because the synthetic certificates are self-signed and
            // carry no distribution point, so there is nothing to fetch and no CRL to consult.
            // This is the only relaxation here, and it does not weaken what the test asserts: a
            // wrong anchor and a one-byte change are both still rejected.
            val validation = PKIXCertificateValidationProvider.builder(trust)
                .checkRevocation(false)
                .build()
            val result = XadesVerificationProfile(validation).newVerifier().verify(signature, null)
            Report(
                verdict = Verdict.VALID,
                form = result.signatureForm,
                references = result.xmlSignature.signedInfo.length,
                signedDataObjects = result.signedDataObjects.size,
                failure = null,
            )
        } catch (e: XAdES4jException) {
            Report(Verdict.INVALID, null, 0, 0, e.javaClass.simpleName)
        } catch (e: Exception) {
            Report(Verdict.ERROR, null, 0, 0, e.javaClass.simpleName)
        }
    }

    /**
     * The document's single `ds:Signature`, with every id attribute marked so the references
     * resolve, or `null` when [octets] are not a parseable document carrying exactly one.
     */
    private fun signatureElement(octets: ByteArray): Element? = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(octets))
        // The root carries a lowercase `id`; the signature's own elements carry `Id`.
        document.documentElement.setIdAttribute("id", true)
        for (namespace in listOf(DSIG_NAMESPACE, ETSI_NAMESPACE)) {
            val elements = document.getElementsByTagNameNS(namespace, "*")
            for (index in 0 until elements.length) {
                val element = elements.item(index) as Element
                if (element.hasAttribute("Id")) element.setIdAttribute("Id", true)
            }
        }
        val signatures = document.getElementsByTagNameNS(DSIG_NAMESPACE, "Signature")
        if (signatures.length == 1) signatures.item(0) as Element else null
    } catch (e: Exception) {
        null
    }

    /** Whether [octets] verify. The boolean form the tamper matrix drives both verifiers through. */
    fun validate(octets: ByteArray, trustAnchor: X509Certificate): Boolean =
        verify(octets, trustAnchor).verdict == Verdict.VALID
}
