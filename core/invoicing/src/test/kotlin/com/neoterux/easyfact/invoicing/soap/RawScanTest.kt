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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The raw-byte slicer. Every input is built here in Kotlin from synthetic values, with explicit
 * `\r\n` where line ends matter, because the point of the scanner is what a parser would lose:
 * CDATA versus escaped form, and line-end normalisation.
 */
class RawScanTest {

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

    private val cdataComprobante =
        "<![CDATA[<?xml version=\"1.0\" encoding=\"UTF-8\"?><factura id=\"comprobante\" version=\"1.1.0\">" +
            "<!-- <autorizacion> --><info>a &amp; b</info></factura>]]>"

    private fun authorization(comprobante: String, startTagAttributes: String = ""): String =
        "<autorizacion$startTagAttributes><estado>AUTORIZADO</estado><numeroAutorizacion>$key</numeroAutorizacion>" +
            "<fechaAutorizacion>2026-10-27T10:00:00.000-05:00</fechaAutorizacion><ambiente>PRUEBAS</ambiente>" +
            "<comprobante>$comprobante</comprobante><mensajes/></autorizacion>"

    private fun response(inner: String, ancestorAttributes: String = ""): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" +
            "<ns2:autorizacionComprobanteResponse xmlns:ns2=\"http://ec.gob.sri.ws.autorizacion\">" +
            "<RespuestaAutorizacionComprobante$ancestorAttributes><claveAccesoConsultada>$key</claveAccesoConsultada>" +
            "<numeroComprobantes>1</numeroComprobantes><autorizaciones>$inner</autorizaciones>" +
            "</RespuestaAutorizacionComprobante></ns2:autorizacionComprobanteResponse></soap:Body></soap:Envelope>"

    private fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

    private fun scan(text: String): List<AuthorizationSlice> {
        val result = RawScan.authorizations(utf8(text))
        assertNotNull("expected a scan result, got null", result)
        return result!!
    }

    private fun textOf(bytes: ByteArray, span: ByteSpan): String =
        String(bytes, span.start, span.endExclusive - span.start, Charsets.UTF_8)

    // --- the Ficha shape -----------------------------------------------------------------------

    @Test
    fun aLiteralAutorizacionInsideCdataDoesNotMakeASecondSlice() {
        val body = utf8(response(authorization(cdataComprobante)))
        val slices = RawScan.authorizations(body)
        assertNotNull(slices)
        assertEquals(1, slices!!.size)

        val slice = slices.single()
        val element = textOf(body, slice.element)
        assertTrue(element.startsWith("<autorizacion>"))
        assertTrue(element.endsWith("</autorizacion>"))
        assertEquals(authorization(cdataComprobante), element)
    }

    @Test
    fun theComprobanteSpanIsTheCdataSectionExactlyAsWritten() {
        val body = utf8(response(authorization(cdataComprobante)))
        val slice = RawScan.authorizations(body)!!.single()
        val content = slice.comprobanteContent
        assertNotNull(content)
        assertEquals(cdataComprobante, textOf(body, content!!))
        assertTrue(body.copyOfRange(content.start, content.endExclusive).contentEquals(utf8(cdataComprobante)))
    }

    @Test
    fun anEscapedComprobanteKeepsItsEscapes() {
        val escaped = "&lt;?xml version=\"1.0\"?&gt;&lt;factura id=\"comprobante\"&gt;&lt;autorizacion&gt;&lt;/factura&gt;"
        val body = utf8(response(authorization(escaped)))
        val slices = RawScan.authorizations(body)!!
        assertEquals(1, slices.size)
        assertEquals(escaped, textOf(body, slices.single().comprobanteContent!!))
    }

    @Test
    fun theFichaShapeDoesNotInheritANamespace() {
        assertFalse(scan(response(authorization(cdataComprobante))).single().inheritsNamespace)
    }

    // --- several elements ----------------------------------------------------------------------

    @Test
    fun threeAutorizacionElementsGiveThreeSlicesInDocumentOrder() {
        val first = authorization("<![CDATA[one]]>")
        val second = authorization("<![CDATA[two]]>")
        val third = authorization("<![CDATA[three]]>")
        val body = utf8(response(first + second + third))
        val slices = RawScan.authorizations(body)!!
        assertEquals(3, slices.size)
        assertEquals(listOf(first, second, third), slices.map { textOf(body, it.element) })
        assertEquals(
            listOf("<![CDATA[one]]>", "<![CDATA[two]]>", "<![CDATA[three]]>"),
            slices.map { textOf(body, it.comprobanteContent!!) },
        )
        assertTrue(slices[0].element.endExclusive <= slices[1].element.start)
        assertTrue(slices[1].element.endExclusive <= slices[2].element.start)
    }

    @Test
    fun theWrapperElementNamedAutorizacionesIsNeverMatched() {
        assertEquals(emptyList<AuthorizationSlice>(), scan(response("")))
    }

    @Test
    fun anAutorizacionNestedInsideAnAutorizacionIsNotASecondSlice() {
        val nested = "<autorizacion><autorizacion><comprobante>inner</comprobante></autorizacion>" +
            "<comprobante>outer</comprobante></autorizacion>"
        val body = utf8(response(nested))
        val slices = RawScan.authorizations(body)!!
        assertEquals(1, slices.size)
        assertEquals(nested, textOf(body, slices.single().element))
        assertEquals("outer", textOf(body, slices.single().comprobanteContent!!))
    }

    // --- bytes, not characters -----------------------------------------------------------------

    @Test
    fun carriageReturnLineFeedInsideTheComprobanteSurvivesByteForByte() {
        val comprobante = "<![CDATA[line one\r\nline two\r\n<x/>\r\n]]>"
        val body = utf8(response(authorization(comprobante)))
        val content = RawScan.authorizations(body)!!.single().comprobanteContent!!
        val copied = body.copyOfRange(content.start, content.endExclusive)
        assertTrue(copied.contentEquals(utf8(comprobante)))
        val crLf = byteArrayOf(0x0D, 0x0A)
        assertTrue("the span must still contain a CR LF pair", (0 until copied.size - 1).any { copied[it] == crLf[0] && copied[it + 1] == crLf[1] })
    }

    @Test
    fun spansAreByteIndicesEvenWhenEarlierTextIsMultiByte() {
        val text = response("<!-- Descripción € -->" + authorization(cdataComprobante))
        val body = utf8(text)
        assertTrue("the fixture must have more bytes than characters", body.size > text.length)
        val slice = RawScan.authorizations(body)!!.single()
        assertEquals(authorization(cdataComprobante), textOf(body, slice.element))
    }

    // --- opaque constructs ---------------------------------------------------------------------

    @Test
    fun aCommentContainingAnAutorizacionTagIsIgnored() {
        val inner = "<!-- <autorizacion><comprobante>x</comprobante></autorizacion> -->" + authorization(cdataComprobante)
        val body = utf8(response(inner))
        assertEquals(1, RawScan.authorizations(body)!!.size)
    }

    @Test
    fun aProcessingInstructionContainingAnAutorizacionTagIsIgnored() {
        val inner = "<?probe <autorizacion> ?>" + authorization(cdataComprobante)
        assertEquals(1, scan(response(inner)).size)
    }

    @Test
    fun anAttributeValueContainingAGreaterThanDoesNotEndTheTag() {
        val double = authorization(cdataComprobante, " note=\"a>b\"")
        val single = authorization(cdataComprobante, " note='c>d'")
        val body = utf8(response(double + single))
        val slices = RawScan.authorizations(body)!!
        assertEquals(2, slices.size)
        assertEquals(double, textOf(body, slices[0].element))
        assertEquals(single, textOf(body, slices[1].element))
    }

    @Test
    fun aCommentOpenerInsideCdataDoesNotStartAComment() {
        val comprobante = "<![CDATA[a <!-- ]]]]><![CDATA[> b]]>"
        val body = utf8(response(authorization(comprobante)))
        assertEquals(1, RawScan.authorizations(body)!!.size)
    }

    // --- namespaces ----------------------------------------------------------------------------

    @Test
    fun aPrefixedAutorizacionInheritsANamespace() {
        val prefixed = "<ns3:autorizacion xmlns:ns3=\"http://example.com/ns\"><ns3:comprobante>x</ns3:comprobante></ns3:autorizacion>"
        val body = utf8(response(prefixed))
        val slice = RawScan.authorizations(body)!!.single()
        assertTrue(slice.inheritsNamespace)
        assertEquals(prefixed, textOf(body, slice.element))
        assertEquals("x", textOf(body, slice.comprobanteContent!!))
    }

    @Test
    fun anAncestorDeclaringADefaultNamespaceMakesTheSliceInherit() {
        val slice = scan(response(authorization(cdataComprobante), " xmlns=\"http://example.com/default\"")).single()
        assertTrue(slice.inheritsNamespace)
    }

    @Test
    fun anAutorizacionDeclaringItsOwnDefaultNamespaceIsReportedAsInheriting() {
        // It carries its own declaration, so the slice is standalone as far as namespaces go; the
        // scanner still reports true because it cannot tell a redeclaration from an inheritance
        // without resolving it, and a false negative here would hand Phase 7 a broken element.
        val slice = scan(response(authorization(cdataComprobante, " xmlns=\"http://example.com/own\""))).single()
        assertTrue(slice.inheritsNamespace)
    }

    @Test
    fun aPrefixDeclarationOnAnAncestorIsNotADefaultNamespace() {
        val slice = scan(response(authorization(cdataComprobante), " xmlns:other=\"http://example.com/other\"")).single()
        assertFalse(slice.inheritsNamespace)
    }

    // --- the comprobante child -----------------------------------------------------------------

    @Test
    fun aSelfClosingComprobanteHasAnEmptyContentSpan() {
        val inner = "<autorizacion><estado>X</estado><comprobante/></autorizacion>"
        val body = utf8(response(inner))
        val content = RawScan.authorizations(body)!!.single().comprobanteContent
        assertNotNull(content)
        assertEquals(content!!.start, content.endExclusive)
        assertEquals("", textOf(body, content))
    }

    @Test
    fun aSelfClosingAutorizacionIsASliceWithoutAComprobante() {
        val body = utf8(response("<autorizacion/>"))
        val slice = RawScan.authorizations(body)!!.single()
        assertEquals("<autorizacion/>", textOf(body, slice.element))
        assertNull(slice.comprobanteContent)
    }

    @Test
    fun aComprobanteThatIsNotADirectChildIsNotTheContentSpan() {
        val inner = "<autorizacion><mensajes><comprobante>deep</comprobante></mensajes></autorizacion>"
        assertNull(scan(response(inner)).single().comprobanteContent)
    }

    // --- malformed input -----------------------------------------------------------------------

    @Test
    fun anUnterminatedCdataSectionGivesNull() {
        assertNull(RawScan.authorizations(utf8(response(authorization("<![CDATA[never closed")))))
    }

    @Test
    fun anUnterminatedCommentGivesNull() {
        assertNull(RawScan.authorizations(utf8("<a><!-- never closed </a>")))
    }

    @Test
    fun anUnterminatedProcessingInstructionGivesNull() {
        assertNull(RawScan.authorizations(utf8("<a><?pi never closed </a>")))
    }

    @Test
    fun anUnterminatedTagGivesNull() {
        assertNull(RawScan.authorizations(utf8("<a><b attr=\"x\"")))
        assertNull(RawScan.authorizations(utf8("<a><b attr=\"never closed></b></a>")))
    }

    @Test
    fun anUnbalancedEndTagGivesNull() {
        assertNull(RawScan.authorizations(utf8("<a></b>")))
        assertNull(RawScan.authorizations(utf8("</a>")))
        assertNull(RawScan.authorizations(utf8("<a><b></a></b>")))
    }

    @Test
    fun anElementLeftOpenAtTheEndGivesNull() {
        assertNull(RawScan.authorizations(utf8("<a><autorizacion><comprobante>x</comprobante>")))
    }

    @Test
    fun aDocumentTypeDeclarationGivesNullBecauseTheGuardOwnsRefusingIt() {
        assertNull(RawScan.authorizations(utf8("<!DOCTYPE a><a/>")))
    }

    @Test
    fun nestingBeyondTheDepthCapGivesNullInsteadOfGrowingWithoutBound() {
        val tooDeep = RawScan.MAX_ELEMENT_DEPTH + 1
        val deep = "<a>".repeat(tooDeep) + "</a>".repeat(tooDeep)
        assertNull(RawScan.authorizations(utf8(deep)))
    }

    @Test
    fun nestingAtTheDepthCapIsStillScanned() {
        val atCap = "<a>".repeat(RawScan.MAX_ELEMENT_DEPTH) + "</a>".repeat(RawScan.MAX_ELEMENT_DEPTH)
        assertEquals(emptyList<AuthorizationSlice>(), RawScan.authorizations(utf8(atCap)))
    }

    @Test
    fun inputWithNoMarkupHasNoSlices() {
        assertEquals(emptyList<AuthorizationSlice>(), RawScan.authorizations(utf8("plain text")))
        assertEquals(emptyList<AuthorizationSlice>(), RawScan.authorizations(ByteArray(0)))
    }
}
