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
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import com.neoterux.easyfact.invoicing.xml.CanonicalEmitter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AGENTS.md section 6.2 says the writer emits already-canonical form. The testable consequence is
 * that the JDK's inclusive canonicalizer is the identity transform on those bytes, so that is
 * what these tests assert, over the emitter's output and over the signer's output.
 *
 * Three mutations, each a single edit to a passing fixture, prove the oracle can say no.
 */
class C14nIdentityTest {

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    private fun assertIdentity(message: String, octets: ByteArray) {
        val canonical = C14nIdentityOracle.canonicalize(octets)
        assertArrayEquals("$message\n  input:     ${text(octets)}\n  canonical: ${text(canonical)}\n", octets, canonical)
    }

    /** A fixture that exercises every escaping rule the emitter has. */
    private fun fixture(): CanonicalDocument = CanonicalEmitter()
        .open("a", mapOf("nombre" to "Ñandú \"q\" & <x> \t \n \r", "id" to "comprobante"))
        .open("b").text("1 > 0 & 2 < 3, Ñandú, tab\t, line\nfeed, cr\r end").close()
        .open("c").close()
        .open("d", mapOf("z" to "1", "m" to "2", "b" to "3")).text("x").close()
        .close()
        .document(prolog = false)

    // --- the emitter's output is a fixed point of canonicalization --------------------------

    @Test
    fun emitterOutputIsTheIdentityUnderInclusiveC14n() {
        assertIdentity("emitter fixture", fixture().root)
    }

    @Test
    fun theFixtureReallyContainsTheCasesItClaims() {
        val xml = text(fixture().root)
        assertTrue(xml.contains("1 &gt; 0 &amp; 2 &lt; 3"))
        assertTrue(xml.contains("Ñandú"))
        assertTrue(xml.contains("nombre=\"Ñandú"))
        assertTrue(xml.contains("&#xD; end"))
        assertTrue(xml.contains("<c></c>"))
        assertTrue(xml.contains("<d b=\"3\" m=\"2\" z=\"1\">"))
    }

    @Test
    fun theWriterOutputForAFullFacturaIsTheIdentity() {
        assertIdentity("factura root", SampleFactura.document().root)
    }

    @Test
    fun withTheDeclarationPresentCanonicalizationYieldsTheRootElementBytes() {
        val document = SampleFactura.document()
        assertTrue(document.prolog.isNotEmpty())
        val canonical = C14nIdentityOracle.canonicalize(document.octets())
        assertArrayEquals("the declaration is dropped and the root is unchanged", document.root, canonical)
    }

    // --- the signer's output, the other end of the pipeline ---------------------------------

    @Test
    fun theSignersOutputIsTheIdentityOverTheSignedRoot() {
        val signer = XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, TestIdentities.fixedClock)
        val document = SampleFactura.document()
        val signed = (signer.sign(document, TestIdentities.plain) as Outcome.Ok).value.octets()
        val signedRoot = signed.copyOfRange(document.prolog.size, signed.size)

        assertIdentity("signed root", signedRoot)
        assertArrayEquals(signedRoot, C14nIdentityOracle.canonicalize(signed))
    }

    // --- three mutations, each one edit away from a passing fixture -------------------------

    @Test
    fun mutationSelfClosingTagIsNotAnIdentity() {
        val good = fixture().root
        assertIdentity("baseline", good)
        val mutated = text(good).replace("<c></c>", "<c/>").toByteArray(Charsets.UTF_8)
        assertFalse("a self-closing tag must not be an identity", C14nIdentityOracle.canonicalize(mutated).contentEquals(mutated))
    }

    @Test
    fun mutationUnsortedAttributesAreNotAnIdentity() {
        val good = fixture().root
        assertIdentity("baseline", good)
        val mutated = text(good).replace("<d b=\"3\" m=\"2\" z=\"1\">", "<d z=\"1\" b=\"3\" m=\"2\">").toByteArray(Charsets.UTF_8)
        assertFalse("out-of-order attributes must not be an identity", C14nIdentityOracle.canonicalize(mutated).contentEquals(mutated))
    }

    @Test
    fun mutationLiteralCarriageReturnIsNotAnIdentity() {
        val good = fixture().root
        assertIdentity("baseline", good)
        val mutated = text(good).replace("cr&#xD; end", "cr\r end").toByteArray(Charsets.UTF_8)
        assertTrue("the mutation must have introduced a literal CR", mutated.any { it == 0x0D.toByte() })
        assertFalse("a literal CR must not be an identity", C14nIdentityOracle.canonicalize(mutated).contentEquals(mutated))
    }
}
