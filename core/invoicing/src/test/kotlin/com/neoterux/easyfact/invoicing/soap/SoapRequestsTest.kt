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

import com.neoterux.easyfact.invoicing.accesskey.AccessKey
import com.neoterux.easyfact.invoicing.probe.ThrowawaySoap
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.signing.SignedDocument
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.time.LocalDate
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two request builders, checked three independent ways: against an exact literal assembled
 * here from the namespace constants, against the probe's separately written envelope templates
 * (the oracle), and by a base64 round trip of the payload.
 *
 * Both envelope shapes are [ASSUMED] until the owner-run test-environment session; what these
 * tests pin is that the builders emit exactly the shape the probe sent, and that the payload is
 * the signed octets and nothing else.
 */
class SoapRequestsTest {

    private val envelopeNamespace = "http://schemas.xmlsoap.org/soap/envelope/"
    private val receptionNamespace = "http://ec.gob.sri.ws.recepcion"
    private val authorizationNamespace = "http://ec.gob.sri.ws.autorizacion"

    /** 300 synthetic bytes covering every byte value class, so the base64 payload exceeds 76 characters. */
    private val octets: ByteArray = ByteArray(300) { index -> (index * 7 + 3).toByte() }

    private val document: SignedDocument = SignedDocument.wrap(octets)

    private val key: AccessKey = when (
        val outcome = AccessKey.of(
            issueDate = LocalDate.of(2026, 10, 27),
            documentTypeCode = "01",
            issuerRuc = SyntheticIdentifiers.RUC,
            environmentCode = "1",
            establishment = "001",
            emissionPoint = "003",
            sequential = 990064L,
            numericCode = "12345678",
            emissionTypeCode = "1",
        )
    ) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw AssertionError("the fixture key must build: ${outcome.error}")
    }

    private fun text(bytes: ByteArray): String = String(bytes, Charsets.UTF_8)

    @Test
    fun validarComprobanteMatchesTheProbeOracleByteForByte() {
        val expected = ThrowawaySoap.recepcionEnvelope(octets).toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, SoapRequests.validarComprobante(document))
    }

    @Test
    fun autorizacionComprobanteMatchesTheProbeOracleByteForByte() {
        val expected = ThrowawaySoap.autorizacionEnvelope(key.digits).toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, SoapRequests.autorizacionComprobante(key))
    }

    @Test
    fun theReceptionPayloadBase64DecodesToExactlyTheSignedOctets() {
        val envelope = text(SoapRequests.validarComprobante(document))
        val payload = envelope.substringAfter("<xml>").substringBefore("</xml>")
        assertArrayEquals(octets, Base64.getDecoder().decode(payload))
    }

    @Test
    fun aPayloadLongerThan76CharactersHasNoLineBreak() {
        val envelope = text(SoapRequests.validarComprobante(document))
        val payload = envelope.substringAfter("<xml>").substringBefore("</xml>")
        assertTrue("the payload must exceed the MIME line length for this test to mean anything", payload.length > 76)
        assertFalse(payload.contains('\r'))
        assertFalse(payload.contains('\n'))
        assertFalse("the whole envelope carries no line break either", envelope.contains('\n') || envelope.contains('\r'))
    }

    @Test
    fun theReceptionEnvelopeIsExactlyTheDocumentedShape() {
        val payload = Base64.getEncoder().encodeToString(octets)
        val expected = "<soapenv:Envelope xmlns:soapenv=\"$envelopeNamespace\" xmlns:ec=\"$receptionNamespace\">" +
            "<soapenv:Body><ec:validarComprobante><xml>$payload</xml></ec:validarComprobante></soapenv:Body></soapenv:Envelope>"
        assertEquals(expected, text(SoapRequests.validarComprobante(document)))
    }

    @Test
    fun theAuthorizationEnvelopeIsExactlyTheDocumentedShape() {
        val expected = "<soapenv:Envelope xmlns:soapenv=\"$envelopeNamespace\" xmlns:ec=\"$authorizationNamespace\">" +
            "<soapenv:Body><ec:autorizacionComprobante><claveAccesoComprobante>${key.digits}</claveAccesoComprobante>" +
            "</ec:autorizacionComprobante></soapenv:Body></soapenv:Envelope>"
        assertEquals(expected, text(SoapRequests.autorizacionComprobante(key)))
    }

    @Test
    fun neitherEnvelopeCarriesAnXmlDeclaration() {
        assertFalse(text(SoapRequests.validarComprobante(document)).startsWith("<?xml"))
        assertFalse(text(SoapRequests.autorizacionComprobante(key)).startsWith("<?xml"))
    }
}
