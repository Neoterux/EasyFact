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
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The byte guard that runs before any parser. Every payload is built here in Kotlin from
 * synthetic values; none is a captured SRI response.
 */
class ResponseGuardTest {

    private val key: String = when (
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
        is Outcome.Ok -> outcome.value.digits
        is Outcome.Err -> throw AssertionError("the fixture key must build: ${outcome.error}")
    }

    private val declaration = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"

    /** A Ficha-shaped authorization answer; the comprobante is CDATA holding its own declaration and a comment. */
    private fun benign(prolog: String = declaration): String =
        prolog +
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" +
            "<ns2:autorizacionComprobanteResponse xmlns:ns2=\"http://ec.gob.sri.ws.autorizacion\">" +
            "<RespuestaAutorizacionComprobante><claveAccesoConsultada>$key</claveAccesoConsultada>" +
            "<numeroComprobantes>1</numeroComprobantes><autorizaciones><autorizacion>" +
            "<estado>AUTORIZADO</estado><numeroAutorizacion>$key</numeroAutorizacion>" +
            "<fechaAutorizacion>2026-10-27T10:00:00.000-05:00</fechaAutorizacion><ambiente>PRUEBAS</ambiente>" +
            "<comprobante><![CDATA[<?xml version=\"1.0\" encoding=\"UTF-8\"?><factura id=\"comprobante\" version=\"1.1.0\">" +
            "<!-- Descripción --></factura>]]></comprobante><mensajes/></autorizacion></autorizaciones>" +
            "</RespuestaAutorizacionComprobante></ns2:autorizacionComprobanteResponse></soap:Body></soap:Envelope>"

    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    /** [benign] padded with spaces just inside `<autorizaciones>` to exactly [total] bytes. */
    private fun paddedTo(total: Int): ByteArray {
        val base = utf8(benign())
        val missing = total - base.size
        assertTrue("the benign body must be smaller than the target", missing >= 0)
        return utf8(benign().replace("<autorizaciones>", "<autorizaciones>" + " ".repeat(missing)))
    }

    private fun withInsertedBytes(vararg extra: Byte): ByteArray {
        val base = utf8(benign())
        val split = base.size / 2
        return base.copyOfRange(0, split) + extra + base.copyOfRange(split, base.size)
    }

    // --- accepted ------------------------------------------------------------------------------

    @Test
    fun aBenignUtf8ResponseIsAccepted() {
        assertNull(ResponseGuard.check(utf8(benign())))
    }

    @Test
    fun aUtf8ByteOrderMarkIsAccepted() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertNull(ResponseGuard.check(bom + utf8(benign())))
    }

    @Test
    fun aDeclaredUtf8EncodingIsMatchedIgnoringCase() {
        assertNull(ResponseGuard.check(utf8(benign("<?xml version=\"1.0\" encoding=\"utf-8\"?>"))))
        assertNull(ResponseGuard.check(utf8(benign("<?xml version='1.0' encoding='Utf-8'?>"))))
    }

    @Test
    fun aDeclarationWithNoEncodingIsAccepted() {
        assertNull(ResponseGuard.check(utf8(benign("<?xml version=\"1.0\"?>"))))
        assertNull(ResponseGuard.check(utf8(benign(""))))
    }

    @Test
    fun theGuardIsNotAWellFormednessCheck() {
        // Well-formedness is the parser's job (NOT_WELL_FORMED); the guard only refuses bytes
        // that are hostile or unreadable, and must not claim more than it checks.
        assertNull(ResponseGuard.check(utf8("not xml at all")))
    }

    @Test
    fun anEncodingDeclaredInsideTheCdataComprobanteIsNotTheResponsesEncoding() {
        val inner = benign("").replace("encoding=\"UTF-8\"", "encoding=\"ISO-8859-1\"")
        assertNull(ResponseGuard.check(utf8(inner)))
    }

    // --- EMPTY / TOO_LARGE ---------------------------------------------------------------------

    @Test
    fun emptyInputIsRefusedAsEmpty() {
        assertEquals(MalformedReason.EMPTY, ResponseGuard.check(ByteArray(0)))
    }

    @Test
    fun oneByteOverTheCapIsRefusedAsTooLarge() {
        assertEquals(MalformedReason.TOO_LARGE, ResponseGuard.check(paddedTo(ResponseGuard.MAX_RESPONSE_BYTES + 1)))
    }

    @Test
    fun exactlyTheCapIsNotTooLarge() {
        val body = paddedTo(ResponseGuard.MAX_RESPONSE_BYTES)
        assertEquals(ResponseGuard.MAX_RESPONSE_BYTES, body.size)
        assertNull(ResponseGuard.check(body))
    }

    @Test
    fun theSizeCheckRunsBeforeAnyContentCheck() {
        val hostile = ByteArray(ResponseGuard.MAX_RESPONSE_BYTES + 1)
        assertEquals(MalformedReason.TOO_LARGE, ResponseGuard.check(hostile))
    }

    @Test
    fun theCapIsDerivedFromTheSheetsComprobanteLimit() {
        val derived = ResponseGuard.COMPROBANTE_MAX_BYTES.toLong() *
            ResponseGuard.ESCAPING_ALLOWANCE * ResponseGuard.MAX_AUTHORIZATION_ENTRIES
        assertEquals(320 * 1024, ResponseGuard.COMPROBANTE_MAX_BYTES)
        assertEquals(7_864_320L, derived)
        assertTrue("the cap must sit at or above the derived product", ResponseGuard.MAX_RESPONSE_BYTES >= derived)
        assertEquals(8 * 1024 * 1024, ResponseGuard.MAX_RESPONSE_BYTES)
    }

    // --- NUL_BYTE ------------------------------------------------------------------------------

    @Test
    fun aNulByteInTheMiddleIsRefused() {
        assertEquals(MalformedReason.NUL_BYTE, ResponseGuard.check(withInsertedBytes(0x00)))
    }

    @Test
    fun aNulByteAsTheFirstOrLastByteIsRefused() {
        assertEquals(MalformedReason.NUL_BYTE, ResponseGuard.check(byteArrayOf(0x00) + utf8(benign())))
        assertEquals(MalformedReason.NUL_BYTE, ResponseGuard.check(utf8(benign()) + byteArrayOf(0x00)))
    }

    @Test
    fun utf16WithoutAByteOrderMarkIsRefusedForItsNulBytes() {
        assertEquals(MalformedReason.NUL_BYTE, ResponseGuard.check(benign().toByteArray(Charsets.UTF_16LE)))
    }

    // --- NOT_UTF8 ------------------------------------------------------------------------------

    @Test
    fun everyUtf16AndUtf32ByteOrderMarkIsRefused() {
        val marks = listOf(
            byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
            byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00),
        )
        for (mark in marks) {
            assertEquals(
                "mark ${mark.joinToString(" ") { "%02X".format(it) }}",
                MalformedReason.NOT_UTF8,
                ResponseGuard.check(mark + utf8(benign())),
            )
        }
    }

    @Test
    fun aByteOrderMarkIsJudgedBeforeTheNulBytesItImplies() {
        val utf32 = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00) + benign().toByteArray(Charsets.UTF_16LE)
        assertEquals(MalformedReason.NOT_UTF8, ResponseGuard.check(utf32))
    }

    @Test
    fun invalidUtf8SequencesAreRefused() {
        // A lead byte followed by an ASCII byte, an overlong slash, a lone continuation byte, and
        // an encoded surrogate: four different ways a strict decoder says no.
        val cases = listOf(
            byteArrayOf(0xC3.toByte(), 0x28),
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()),
            byteArrayOf(0x80.toByte()),
            byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
        )
        for (bad in cases) {
            assertEquals(
                "bytes ${bad.joinToString(" ") { "%02X".format(it) }}",
                MalformedReason.NOT_UTF8,
                ResponseGuard.check(withInsertedBytes(*bad)),
            )
        }
    }

    @Test
    fun aTruncatedMultiByteSequenceAtTheEndIsRefused() {
        assertEquals(
            MalformedReason.NOT_UTF8,
            ResponseGuard.check(utf8(benign()) + byteArrayOf(0xE2.toByte(), 0x82.toByte())),
        )
    }

    @Test
    fun aDeclaredNonUtf8EncodingIsRefusedEvenWhenTheBytesAreAscii() {
        for (declared in listOf("ISO-8859-1", "UTF-16", "windows-1252", "US-ASCII")) {
            assertEquals(
                declared,
                MalformedReason.NOT_UTF8,
                ResponseGuard.check(utf8(benign("<?xml version=\"1.0\" encoding=\"$declared\"?>"))),
            )
        }
        assertEquals(
            MalformedReason.NOT_UTF8,
            ResponseGuard.check(utf8(benign("<?xml version='1.0' encoding = 'ISO-8859-1' ?>"))),
        )
    }

    // --- DOCTYPE / ENTITY_DECLARATION ----------------------------------------------------------

    @Test
    fun aBillionLaughsPayloadIsRefusedAsDoctype() {
        val bomb = "<?xml version=\"1.0\"?><!DOCTYPE lolz [" +
            "<!ENTITY lol \"lol\">" +
            "<!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">" +
            "<!ENTITY lol3 \"&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;\">" +
            "]><lolz>&lol3;</lolz>"
        assertEquals(MalformedReason.DOCTYPE, ResponseGuard.check(utf8(bomb)))
    }

    @Test
    fun anExternalEntityPayloadIsRefusedAsDoctype() {
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"http://example.com/x.dtd\">]><r>&x;</r>"
        assertEquals(MalformedReason.DOCTYPE, ResponseGuard.check(utf8(xxe)))
    }

    @Test
    fun aLoneEntityDeclarationIsRefusedAsEntityDeclaration() {
        val lone = benign().replace("<autorizaciones>", "<!ENTITY x \"y\"><autorizaciones>")
        assertEquals(MalformedReason.ENTITY_DECLARATION, ResponseGuard.check(utf8(lone)))
    }

    @Test
    fun aDoctypeInsideACdataSectionIsStillRefused() {
        // The scan is deliberately whole-body: a legitimate signed comprobante can never carry
        // the sequence, so a body that does is refused wherever the sequence sits.
        val hidden = benign().replace("<!-- Descripción -->", "<!DOCTYPE x>")
        assertEquals(MalformedReason.DOCTYPE, ResponseGuard.check(utf8(hidden)))
    }

    @Test
    fun anEscapedDoctypeIsNotTheAsciiSequenceAndIsAccepted() {
        val escaped = benign().replace("<!-- Descripción -->", "&lt;!DOCTYPE x&gt;")
        assertNull(ResponseGuard.check(utf8(escaped)))
    }

    @Test
    fun theGuardNeverRaisesTheTwoReasonsThatBelongToTheParser() {
        val reasons = listOf(
            ResponseGuard.check(ByteArray(0)),
            ResponseGuard.check(withInsertedBytes(0x00)),
            ResponseGuard.check(utf8("<!DOCTYPE a>")),
            ResponseGuard.check(utf8("<!ENTITY a \"b\">")),
            ResponseGuard.check(withInsertedBytes(0x80.toByte())),
        )
        for (reason in reasons) {
            assertNotEquals(MalformedReason.NOT_WELL_FORMED, reason)
            assertNotEquals(MalformedReason.RAW_SLICE_MISMATCH, reason)
        }
        assertTrue(reasons.all { it != null })
    }
}
