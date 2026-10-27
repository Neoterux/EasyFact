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

import com.neoterux.easyfact.invoicing.factura.SampleFactura
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.signing.FixedIdSource
import com.neoterux.easyfact.invoicing.signing.Rfc2253Jdk
import com.neoterux.easyfact.invoicing.signing.TestIdentities
import com.neoterux.easyfact.invoicing.signing.XadesBesSigner
import com.neoterux.easyfact.invoicing.verify.Jsr105Verifier.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signer's own output, judged by the JDK's XML-DSig implementation, and the same output with
 * one byte changed in each of four places. A verifier that accepted everything would pass the
 * first test and fail all four of the others.
 */
class Jsr105VerifierTest {

    private fun signed(): ByteArray {
        val signer = XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, TestIdentities.fixedClock)
        val outcome = signer.sign(SampleFactura.document(), TestIdentities.plain)
        return (outcome as Outcome.Ok).value.octets()
    }

    /** Replaces the single character at [index] of [text] with [replacement] and checks that exactly one byte moved. */
    private fun changeOneByte(original: ByteArray, text: String, index: Int, replacement: Char): ByteArray {
        require(text[index] != replacement) { "replacement must differ" }
        val changed = StringBuilder(text).also { it.setCharAt(index, replacement) }.toString().toByteArray(Charsets.UTF_8)
        assertEquals(original.size, changed.size)
        assertEquals("exactly one byte must differ", 1, original.indices.count { original[it] != changed[it] })
        return changed
    }

    /** Index of the first character after [marker] in [text]. */
    private fun after(text: String, marker: String): Int {
        val at = text.indexOf(marker)
        assertTrue("marker not found: $marker", at >= 0)
        return at + marker.length
    }

    /** A base64 character different from the one at [index], so the value stays decodable. */
    private fun otherBase64(text: String, index: Int): Char = if (text[index] == 'A') 'B' else 'A'

    @Test
    fun theSignersOwnOutputValidates() {
        assertEquals(Verdict.VALID, Jsr105Verifier.verdict(signed()))
    }

    @Test
    fun aChangeInTheDocumentBodyIsRejected() {
        val original = signed()
        val text = String(original, Charsets.UTF_8)
        val at = after(text, "<importeTotal>")
        val changed = changeOneByte(original, text, at, if (text[at] == '9') '8' else '9')
        assertEquals(Verdict.CORE_INVALID, Jsr105Verifier.verdict(changed))
    }

    @Test
    fun aChangeInsideSignedPropertiesIsRejected() {
        val original = signed()
        val text = String(original, Charsets.UTF_8)
        // The last digit of the seconds in SigningTime, which sits inside SignedProperties.
        val at = after(text, "<etsi:SigningTime>2026-10-27T12:00:0")
        val changed = changeOneByte(original, text, at, '1')
        assertEquals(Verdict.CORE_INVALID, Jsr105Verifier.verdict(changed))
    }

    @Test
    fun aChangeInTheCertificateValueIsRejected() {
        val original = signed()
        val text = String(original, Charsets.UTF_8)
        // Ten characters before the end of the value: still inside the base64 body, so the
        // certificate still parses and the failure is the digest of KeyInfo, not a parse error.
        val at = text.indexOf("</ds:X509Certificate>") - 10
        val changed = changeOneByte(original, text, at, otherBase64(text, at))
        assertEquals(Verdict.CORE_INVALID, Jsr105Verifier.verdict(changed))
    }

    @Test
    fun aChangeInTheSignatureValueIsRejected() {
        val original = signed()
        val text = String(original, Charsets.UTF_8)
        val at = text.indexOf("</ds:SignatureValue>") - 10
        val changed = changeOneByte(original, text, at, otherBase64(text, at))
        assertEquals(Verdict.CORE_INVALID, Jsr105Verifier.verdict(changed))
    }

    @Test
    fun aDocumentWithoutASignatureIsMalformedNotValid() {
        assertEquals(Verdict.MALFORMED, Jsr105Verifier.verdict(SampleFactura.document().octets()))
    }

    @Test
    fun bytesThatAreNotXmlAreMalformedNotValid() {
        assertEquals(Verdict.MALFORMED, Jsr105Verifier.verdict("not xml".toByteArray()))
    }
}
