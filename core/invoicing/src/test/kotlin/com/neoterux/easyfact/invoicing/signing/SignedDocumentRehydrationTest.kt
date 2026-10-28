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

package com.neoterux.easyfact.invoicing.signing

import com.neoterux.easyfact.invoicing.factura.SampleFactura
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.verify.Jsr105Verifier
import com.neoterux.easyfact.invoicing.verify.Xades4jVerifier
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one narrow door back into [SignedDocument]: re-wrapping octets signed on an earlier run,
 * for the resend path `docs/sri-electronic-invoicing.md` section 7 requires.
 *
 * Every call to [SignedDocument.fromStoredOctets] in this file carries `@OptIn` at the call
 * site. That is not ceremony — it is the documentation of how Phase 6 is expected to use the
 * factory, and the reason a `grep` for `StoredOctetRehydration` lists every place in the
 * codebase that re-admits bytes into the type the submission path trusts (T-02-17).
 *
 * Verification claims here name their oracle: the JDK's JSR-105 implementation, or xades4j.
 * Neither is the SRI.
 */
class SignedDocumentRehydrationTest {

    private val identity = TestIdentities.plain

    /** A freshly signed document; a fresh signer per signature, because one consumes eight ids. */
    private fun signed(): SignedDocument {
        val signer = XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, TestIdentities.fixedClock)
        return when (val outcome = signer.sign(SampleFactura.document(), identity)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("the fixture must sign: ${outcome.error}")
        }
    }

    /** SHA-256 computed here, independently of the implementation under test. */
    private fun digestOf(octets: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(octets)

    @Test
    fun sha256IsTheDigestOfTheSignedOctets() {
        val document = signed()

        assertArrayEquals(digestOf(document.octets()), document.sha256())
        assertEquals("SHA-256 is 32 bytes", 32, document.sha256().size)
    }

    @Test
    @OptIn(StoredOctetRehydration::class)
    fun storedOctetsWhoseDigestMatchesAreRehydrated() {
        val original = signed()
        val stored = original.octets()
        val recorded = original.sha256()

        val outcome = SignedDocument.fromStoredOctets(stored, recorded)

        assertTrue("a matching digest must rehydrate: $outcome", outcome is Outcome.Ok)
        assertArrayEquals(original.octets(), (outcome as Outcome.Ok).value.octets())
    }

    @Test
    @OptIn(StoredOctetRehydration::class)
    fun aDigestThatDoesNotMatchIsRejectedAndNoDocumentIsReturned() {
        val original = signed()
        val wrong = original.sha256().also { it[0] = (it[0].toInt() xor 0x01).toByte() }

        val outcome = SignedDocument.fromStoredOctets(original.octets(), wrong)

        assertEquals(Outcome.Err(RehydrationError.DigestMismatch), outcome)
        assertNull((outcome as? Outcome.Ok)?.value)
    }

    @Test
    @OptIn(StoredOctetRehydration::class)
    fun aDigestOfTheWrongLengthIsRejectedRatherThanThrowing() {
        val original = signed()

        val outcome = SignedDocument.fromStoredOctets(original.octets(), ByteArray(0))

        assertEquals(Outcome.Err(RehydrationError.DigestMismatch), outcome)
    }

    @Test
    @OptIn(StoredOctetRehydration::class)
    fun anEmptyArrayIsEmptyOctetsEvenWhenTheDigestItselfMatches() {
        // The digest of the empty array is a perfectly valid SHA-256, so without an explicit
        // emptiness check this call would succeed and produce a zero-byte "signed document".
        val outcome = SignedDocument.fromStoredOctets(ByteArray(0), digestOf(ByteArray(0)))

        assertEquals(Outcome.Err(RehydrationError.EmptyOctets), outcome)
    }

    @Test
    @OptIn(StoredOctetRehydration::class)
    fun aRehydratedDocumentIsIndistinguishableFromASignedOne() {
        val original = signed()
        val rehydrated = (SignedDocument.fromStoredOctets(original.octets(), original.sha256()) as Outcome.Ok).value

        assertTrue("the octets must be the same", rehydrated.contentEquals(original.octets()))
        assertArrayEquals(original.sha256(), rehydrated.sha256())
        assertEquals(original.toString(), rehydrated.toString())
        assertEquals(Jsr105Verifier.Verdict.VALID, Jsr105Verifier.verdict(rehydrated.octets()))
        assertEquals(
            Xades4jVerifier.Verdict.VALID,
            Xades4jVerifier.verify(rehydrated.octets(), identity.certificate).verdict,
        )
    }

    @Test
    @OptIn(StoredOctetRehydration::class)
    fun theFactoryCopiesTheCallersArray() {
        val original = signed()
        val stored = original.octets()
        val rehydrated = (SignedDocument.fromStoredOctets(stored, original.sha256()) as Outcome.Ok).value

        stored[0] = (stored[0].toInt() xor 0x7F).toByte()

        assertTrue("a later write by the caller must not reach the document", rehydrated.contentEquals(original.octets()))
    }

    @Test
    fun neitherFailureRendersTheOctetsOrTheDigest() {
        // These values reach the on-device diagnostics log (AGENTS.md section 7).
        for (error in listOf(RehydrationError.DigestMismatch, RehydrationError.EmptyOctets)) {
            val rendered = error.toString()
            assertFalse(rendered, rendered.contains("["))
            assertTrue(rendered, rendered.length < 32)
        }
        assertEquals("DigestMismatch", RehydrationError.DigestMismatch.toString())
        assertEquals("EmptyOctets", RehydrationError.EmptyOctets.toString())
    }

    @Test
    fun theFactoryIsGatedBehindAnErrorLevelOptInAndComparesInConstantTime() {
        // The compiler enforces the opt-in; what it cannot enforce is that the marker keeps its
        // ERROR level or that the comparison stays constant-time, so both are pinned here. The
        // marker has BINARY retention, which is the idiomatic choice for an opt-in and is
        // invisible to java.lang.reflect, so the declaration itself is what is read.
        val marker = source("StoredOctetRehydration.kt")
        assertTrue("the marker must be an opt-in", marker.contains("@RequiresOptIn("))
        assertTrue("the opt-in must be ERROR level", marker.contains("level = RequiresOptIn.Level.ERROR"))

        val document = source("SignedDocument.kt")
        assertTrue("the factory must be annotated", document.contains("@StoredOctetRehydration\n        fun fromStoredOctets("))
        assertTrue("the comparison must be constant-time", document.contains("MessageDigest.isEqual("))
        assertFalse(
            "array equality would leak the matching prefix length through timing",
            document.contains("expectedSha256.contentEquals(") || document.contains(".contentEquals(expectedSha256)"),
        )
    }

    /** A main-source file of the signing package, read from the module directory. */
    private fun source(name: String): String {
        val file = File("src/main/kotlin/com/neoterux/easyfact/invoicing/signing/$name")
        assertTrue("expected to find ${file.absolutePath}", file.isFile)
        return file.readText()
    }
}
