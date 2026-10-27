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
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One list of tamper positions, both verifiers, one loop.
 *
 * [Jsr105Verifier] and [Xades4jVerifier] each had their own rejection tests. Holding the
 * positions in a single list is what makes the evidence symmetric: a position cannot be asserted
 * in one verifier and silently skipped in the other, because there is only one place a position
 * is written down.
 *
 * Every position is located by searching for a landmark substring and moving a fixed offset from
 * it, never by an absolute byte index. An absolute index would still be a valid integer the day
 * an element id changes length, and the test would quietly start flipping a byte somewhere
 * harmless while continuing to pass.
 */
class TamperMatrixTest {

    /**
     * One byte, one place, one expectation.
     *
     * @param landmark the substring the position is measured from
     * @param offset where the changed byte sits relative to the end of [landmark]; negative counts
     * back from the landmark's start
     * @param replace the byte to write, chosen from the byte that is there so the change is always real
     * @param expectValid whether both verifiers must still accept the document afterwards
     */
    private class TamperCase(
        val name: String,
        val landmark: String,
        val offset: Int,
        val expectValid: Boolean,
        val replace: (Char) -> Char,
    )

    /** A different digit, so the value stays a number. */
    private val otherDigit: (Char) -> Char = { if (it == '9') '8' else '9' }

    /** A different base64 character, so the value still decodes and the failure is the digest. */
    private val otherBase64: (Char) -> Char = { if (it == 'A') 'B' else 'A' }

    /** A different letter, so the text stays text. */
    private val otherLetter: (Char) -> Char = { if (it == 'x') 'y' else 'x' }

    private val cases = listOf(
        TamperCase(
            name = "document body: the first digit of importeTotal",
            landmark = "<importeTotal>",
            offset = 0,
            expectValid = false,
            replace = otherDigit,
        ),
        TamperCase(
            // Inside SignedDataObjectProperties, which sits inside the digested SignedProperties.
            name = "SignedProperties: the first letter of the DataObjectFormat description",
            landmark = "<etsi:Description>",
            offset = 0,
            expectValid = false,
            replace = otherLetter,
        ),
        TamperCase(
            // Ten characters before the closing tag: still inside the base64 body, so the
            // certificate still parses and the failure is the KeyInfo digest, not a parse error.
            name = "KeyInfo: a base64 character of the X509Certificate value",
            landmark = "</ds:X509Certificate>",
            offset = -10,
            expectValid = false,
            replace = otherBase64,
        ),
        TamperCase(
            name = "SignatureValue: a base64 character of the signature itself",
            landmark = "</ds:SignatureValue>",
            offset = -10,
            expectValid = false,
            replace = otherBase64,
        ),
        TamperCase(
            // The deliberate positive. The signer digests the root element alone, so the XML
            // declaration is transmitted but not covered by any reference. Writing `utf-8` where
            // `UTF-8` stood changes one transmitted byte and changes no digest, which is the
            // digest boundary this phase's byte-identity story rests on (AGENTS.md section 6.2,
            // D-13). The change is deliberately encoding-equivalent so the document still parses;
            // what would break a digest covering the prolog is the byte, not its meaning.
            name = "prolog: the encoding name in the XML declaration, outside the digested root",
            landmark = "encoding=\"",
            offset = 0,
            expectValid = true,
            replace = { 'u' },
        ),
    )

    private val identity = TestIdentities.plain

    private fun signed(): ByteArray {
        val signer = XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, TestIdentities.fixedClock)
        return (signer.sign(SampleFactura.document(), identity) as Outcome.Ok).value.octets()
    }

    /** [original] with exactly one byte changed at the position [case] names. */
    private fun apply(case: TamperCase, original: ByteArray): ByteArray {
        val text = String(original, Charsets.UTF_8)
        val at = text.indexOf(case.landmark)
        assertTrue("${case.name}: landmark not found", at >= 0)
        val index = if (case.offset < 0) at + case.offset else at + case.landmark.length + case.offset
        val replacement = case.replace(text[index])
        assertTrue("${case.name}: the replacement must differ", text[index] != replacement)
        val changed = StringBuilder(text).also { it.setCharAt(index, replacement) }.toString()
            .toByteArray(Charsets.UTF_8)
        assertEquals("${case.name}: the document must keep its length", original.size, changed.size)
        assertEquals(
            "${case.name}: exactly one byte must differ",
            1,
            original.indices.count { original[it] != changed[it] },
        )
        return changed
    }

    private fun verdicts(octets: ByteArray, anchor: X509Certificate): Pair<Boolean, Boolean> =
        Jsr105Verifier.validate(octets) to Xades4jVerifier.validate(octets, anchor)

    private fun row(name: String, jsr105: Boolean, xades4j: Boolean) = "$name | jsr105=$jsr105 | xades4j=$xades4j"

    @Test
    fun everyTamperPositionIsJudgedTheSameWayByBothVerifiers() {
        val original = signed()
        val anchor = identity.certificate

        // The whole matrix is compared in one assertion so that a failure reports every position
        // at once, rather than stopping at the first and hiding the rest.
        val expected = mutableListOf<String>()
        val actual = mutableListOf<String>()

        // The untampered document is judged inside the same test as the tampers, so a verifier
        // that rejected everything could not pass this matrix.
        expected += row("untampered", jsr105 = true, xades4j = true)
        val (jsr105Clean, xades4jClean) = verdicts(original, anchor)
        actual += row("untampered", jsr105Clean, xades4jClean)

        for (case in cases) {
            val changed = apply(case, original)
            val (jsr105, xades4j) = verdicts(changed, anchor)
            expected += row(case.name, case.expectValid, case.expectValid)
            actual += row(case.name, jsr105, xades4j)
        }

        assertEquals(expected.joinToString("\n"), actual.joinToString("\n"))
    }

    @Test
    fun theMatrixCoversFourRejectingPositionsAndOneAcceptingOne() {
        // Guards the matrix itself: a case deleted by accident would otherwise shrink the
        // evidence without failing anything.
        assertEquals(4, cases.count { !it.expectValid })
        assertEquals(1, cases.count { it.expectValid })
        assertEquals(cases.size, cases.map { it.name }.toSet().size)
    }
}
