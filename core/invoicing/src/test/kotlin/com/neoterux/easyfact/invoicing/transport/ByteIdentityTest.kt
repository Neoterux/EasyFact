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

import com.neoterux.easyfact.invoicing.factura.Factura
import com.neoterux.easyfact.invoicing.factura.SampleFactura
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.signing.FixedIdSource
import com.neoterux.easyfact.invoicing.signing.Rfc2253Jdk
import com.neoterux.easyfact.invoicing.signing.SignedDocument
import com.neoterux.easyfact.invoicing.signing.TestIdentities
import com.neoterux.easyfact.invoicing.signing.XadesBesSigner
import com.neoterux.easyfact.invoicing.verify.Jsr105Verifier
import com.neoterux.easyfact.invoicing.verify.Xades4jVerifier
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SRI-05 as an executable assertion: the base64 payload of the envelope actually produced
 * decodes to exactly the octets the signer returned, prolog included.
 *
 * The comparison always runs `SignedDocument.octets()` -> `RecordingTransport.encodedOctets` ->
 * the decoded payload. It never re-builds a document and compares that, because two
 * independently produced documents agreeing would prove the signer is deterministic rather than
 * prove the transport transmitted what it was handed.
 *
 * [ReserializingTransport] is the committed way to fail this test, and it fails it in two
 * distinct ways that are both worth having:
 *
 * - **Conservative** (`reformat = false`): the bytes change and both verifiers still accept
 *   them. Inclusive C14N normalises the self-closing tags and the rewritten declaration away
 *   again before the digest is taken. This is the reason SRI-05 is asserted on octets rather
 *   than inferred from a verifier — a green verifier is simply not the same claim.
 * - **Reformatting** (`reformat = true`): indentation inserts whitespace text nodes inside the
 *   digested root, which canonicalization preserves, and both verifiers reject the result.
 *
 * Every verification claim here names its oracle: the JDK's JSR-105 implementation, or
 * xades4j 2.4.1. Neither is the SRI, and nothing in this file says anything about what the SRI
 * accepts, authorizes or validates.
 */
class ByteIdentityTest {

    private val anchor = TestIdentities.plain.certificate

    /** A fresh signer per signature: one instance consumes eight ids per call. */
    private fun sign(factura: Factura): SignedDocument {
        val signer = XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, TestIdentities.fixedClock)
        return when (val outcome = signer.sign(SampleFactura.write(factura), TestIdentities.plain)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("the fixture must sign: ${outcome.error}")
        }
    }

    /** The single base64 payload of [envelope], decoded. */
    private fun payloadOf(envelope: String): ByteArray {
        val start = envelope.indexOf(PAYLOAD_OPEN)
        assertTrue("the envelope must carry a payload", start >= 0)
        val end = envelope.indexOf(PAYLOAD_CLOSE, start)
        assertTrue("the envelope must close its payload", end > start)
        val encoded = envelope.substring(start + PAYLOAD_OPEN.length, end)
        assertFalse("the payload must not be chunked", encoded.contains('\n'))
        return Base64.getDecoder().decode(encoded)
    }

    /** A factura whose buyer name is dense in non-ASCII letters. */
    private fun nonAsciiFactura(): Factura {
        val base = SampleFactura.factura()
        return base.copy(
            invoiceInfo = base.invoiceInfo.copy(buyerLegalName = "Ñandú Ávila Güéllar Ürsula Œuvre Ação"),
        )
    }

    /** A factura whose line description forces every text-escaping rule at once. */
    private fun escapedMarkupFactura(): Factura {
        val base = SampleFactura.factura()
        return base.copy(
            lines = listOf(base.lines.first().copy(description = "A & B < C > D \" E ' F")),
        )
    }

    @Test
    fun theTransmittedPayloadIsExactlyTheOctetsTheSignerReturned() {
        val signed = sign(SampleFactura.factura())
        val transport = RecordingTransport()

        val decoded = payloadOf(transport.submit(signed))

        assertArrayEquals("the transport must encode the signer's own octets", signed.octets(), transport.encodedOctets)
        assertArrayEquals("the decoded payload must equal the signed octets", signed.octets(), decoded)
        assertTrue("and the document itself must agree", signed.contentEquals(decoded))
    }

    @Test
    fun theTransmittedPayloadCarriesTheXmlDeclarationProlog() {
        val signed = sign(SampleFactura.factura())

        val decoded = payloadOf(RecordingTransport().submit(signed))

        assertTrue(
            "the declaration is transmitted even though it is not digested",
            String(decoded, Charsets.UTF_8).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<factura"),
        )
    }

    @Test
    fun aDocumentWithNonAsciiTextIsTransmittedByteForByte() {
        val signed = sign(nonAsciiFactura())
        val transport = RecordingTransport()

        val decoded = payloadOf(transport.submit(signed))

        // The case is only real if the multi-byte sequences actually reached the signed octets.
        val text = String(signed.octets(), Charsets.UTF_8)
        assertTrue("the fixture must contain non-ASCII text", text.contains("Ñandú Ávila Güéllar Ürsula Œuvre Ação"))
        assertTrue("UTF-8 must be encoded exactly once", signed.octets().size > text.length)
        assertArrayEquals(signed.octets(), transport.encodedOctets)
        assertArrayEquals(signed.octets(), decoded)
    }

    @Test
    fun aDocumentWithEscapedMarkupInTextNodesIsTransmittedByteForByte() {
        val signed = sign(escapedMarkupFactura())
        val transport = RecordingTransport()

        val decoded = payloadOf(transport.submit(signed))

        val text = String(signed.octets(), Charsets.UTF_8)
        assertTrue("the fixture must contain escaped markup", text.contains("A &amp; B &lt; C &gt; D \" E ' F"))
        assertArrayEquals(signed.octets(), transport.encodedOctets)
        assertArrayEquals(signed.octets(), decoded)
    }

    @Test
    fun aConservativeReSerializationAlreadyChangesTheTransmittedBytes() {
        val signed = sign(SampleFactura.factura())
        val transport = ReserializingTransport()

        val decoded = payloadOf(transport.submit(signed))

        assertArrayEquals("the fixture must encode what it re-serialized", transport.encodedOctets, decoded)
        assertFalse(
            "parsing and re-serializing must change the transmitted bytes, or this fixture proves nothing",
            decoded.contentEquals(signed.octets()),
        )
        assertFalse(signed.contentEquals(decoded))

        // Named concretely, so a future JDK whose serializer behaves differently fails here
        // rather than silently weakening the fixture.
        val original = String(signed.octets(), Charsets.UTF_8)
        val sent = String(decoded, Charsets.UTF_8)
        assertFalse("the canonical writer never emits a self-closing tag", original.contains(EMPTY_ELEMENT_COLLAPSED))
        assertTrue("the re-serializer collapses empty elements", sent.contains(EMPTY_ELEMENT_COLLAPSED))
        assertTrue("and rewrites the declaration", sent.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>"))
    }

    @Test
    fun aConservativeReSerializationIsStillAcceptedByBothVerifiers() {
        val signed = sign(SampleFactura.factura())

        val decoded = payloadOf(ReserializingTransport().submit(signed))

        // This is the finding that justifies asserting SRI-05 on octets. Inclusive C14N puts the
        // self-closing tags and the rewritten declaration back before the digest is taken, so
        // both independent verifiers accept bytes that are NOT the bytes the signer returned.
        // A green verifier therefore cannot stand in for byte identity.
        assertFalse(decoded.contentEquals(signed.octets()))
        assertEquals(Jsr105Verifier.Verdict.VALID, Jsr105Verifier.verdict(decoded))
        assertEquals(Xades4jVerifier.Verdict.VALID, Xades4jVerifier.verify(decoded, anchor).verdict)
    }

    @Test
    fun aReformattingReSerializationIsRejectedByTheJsr105Oracle() {
        val signed = sign(SampleFactura.factura())

        val decoded = payloadOf(ReserializingTransport(reformat = true).submit(signed))

        assertFalse(decoded.contentEquals(signed.octets()))
        assertEquals(Jsr105Verifier.Verdict.VALID, Jsr105Verifier.verdict(signed.octets()))
        // CORE_INVALID, not MALFORMED: the reformatted document still parses, so the rejection
        // is a broken digest and not an accident of the fixture producing unreadable bytes.
        assertEquals(Jsr105Verifier.Verdict.CORE_INVALID, Jsr105Verifier.verdict(decoded))
    }

    @Test
    fun aReformattingReSerializationIsRejectedByTheXadesBesVerifier() {
        val signed = sign(SampleFactura.factura())

        val decoded = payloadOf(ReserializingTransport(reformat = true).submit(signed))

        assertEquals(Xades4jVerifier.Verdict.VALID, Xades4jVerifier.verify(signed.octets(), anchor).verdict)
        // INVALID, not ERROR: xades4j rejected the signature rather than failing for some
        // unrelated reason.
        assertEquals(Xades4jVerifier.Verdict.INVALID, Xades4jVerifier.verify(decoded, anchor).verdict)
    }

    @Test
    fun theDocumentRecoveredFromTheHonestPayloadStillValidatesInBothVerifiers() {
        val signed = sign(SampleFactura.factura())

        val decoded = payloadOf(RecordingTransport().submit(signed))

        assertEquals(Jsr105Verifier.Verdict.VALID, Jsr105Verifier.verdict(decoded))
        assertEquals(Xades4jVerifier.Verdict.VALID, Xades4jVerifier.verify(decoded, anchor).verdict)
    }

    @Test
    fun submitTakesASignedDocumentAndHasNoByteArrayOrStringOverload() {
        val declared = DocumentTransport::class.java.declaredMethods.filter { it.name == "submit" }

        assertEquals("there must be exactly one submit", 1, declared.size)
        assertArrayEquals(arrayOf<Class<*>>(SignedDocument::class.java), declared.single().parameterTypes)
        assertTrue(
            "no overload may take raw bytes or a string",
            DocumentTransport::class.java.methods.none { method ->
                method.parameterTypes.any { it == ByteArray::class.java || it == String::class.java }
            },
        )
    }

    private companion object {
        const val PAYLOAD_OPEN = "<xml>"
        const val PAYLOAD_CLOSE = "</xml>"

        /** One empty element of the signature, in the self-closing form the writer never emits. */
        const val EMPTY_ELEMENT_COLLAPSED =
            "<ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/TR/2001/REC-xml-c14n-20010315\"/>"
    }
}
