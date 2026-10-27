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

package com.neoterux.easyfact.testing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Test

class SyntheticCertificatesTest {

    private val now: Instant = Instant.parse("2026-01-15T12:00:00Z")
    private val notBefore: Instant = now.minus(1, ChronoUnit.DAYS)
    private val notAfter: Instant = now.plus(365, ChronoUnit.DAYS)
    private val password: CharArray = SyntheticCertificates.DEFAULT_PASSWORD.toCharArray()

    private fun mint(shape: IssuerShape = IssuerShape.PLAIN): SyntheticIdentity =
        SyntheticCertificates.rsa2048(shape, notBefore, notAfter)

    private fun reopen(bytes: ByteArray, alias: String = "signing"): Pair<PrivateKey, X509Certificate> {
        val store = KeyStore.getInstance("PKCS12")
        store.load(ByteArrayInputStream(bytes), password)
        val key = store.getKey(alias, password) as PrivateKey
        return key to (store.getCertificate(alias) as X509Certificate)
    }

    /** DER encoding of an OBJECT IDENTIFIER whose content octets are [content]. */
    private fun oidDer(vararg content: Int): ByteArray =
        byteArrayOf(0x06, content.size.toByte(), *ByteArray(content.size) { content[it].toByte() })

    private fun ByteArray.contains(needle: ByteArray): Boolean =
        (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }

    @Test
    fun plainIdentity_hasA2048BitRsaKeyAndIsValidAtTheRequestedInstant() {
        val identity = mint()
        val publicKey = identity.certificate.publicKey as RSAPublicKey
        assertEquals(2048, publicKey.modulus.bitLength())
        assertEquals(2048, (identity.privateKey as RSAPrivateKey).modulus.bitLength())
        identity.certificate.checkValidity(java.util.Date.from(now))
        identity.certificate.verify(publicKey)
    }

    @Test
    fun plainIdentity_isSelfSignedWithSyntheticNames() {
        val certificate = mint().certificate
        assertEquals(certificate.subjectX500Principal, certificate.issuerX500Principal)
        assertTrue(certificate.issuerX500Principal.name.contains("Test"))
    }

    @Test
    fun organizationIdentifierUtf8_rendersTheOidAsAUtf8StringHexValue() {
        val issuer = mint(IssuerShape.ORGANIZATION_IDENTIFIER_UTF8).certificate.issuerX500Principal.name
        assertTrue(issuer, Regex("2\\.5\\.4\\.97=#0c").containsMatchIn(issuer))
    }

    @Test
    fun organizationIdentifierPrintable_rendersTheOidAsAPrintableStringHexValue() {
        val issuer = mint(IssuerShape.ORGANIZATION_IDENTIFIER_PRINTABLE).certificate.issuerX500Principal.name
        assertTrue(issuer, Regex("2\\.5\\.4\\.97=#13").containsMatchIn(issuer))
    }

    @Test
    fun plainIdentity_carriesNoOrganizationIdentifier() {
        assertFalse(mint().certificate.issuerX500Principal.name.contains("2.5.4.97"))
    }

    @Test
    fun nonAscii_issuerSurvivesAPkcs12RoundTripUnchanged() {
        val identity = mint(IssuerShape.NON_ASCII)
        val issuer = identity.certificate.issuerX500Principal
        assertTrue(issuer.name, issuer.name.any { it.code > 127 })
        for (encoding in Pkcs12Encoding.values()) {
            val (_, reopened) = reopen(SyntheticCertificates.pkcs12(identity, password, encoding))
            assertEquals(encoding.name, issuer, reopened.issuerX500Principal)
            assertArrayEquals(encoding.name, issuer.encoded, reopened.issuerX500Principal.encoded)
        }
    }

    @Test
    fun bothEncodings_reopenWithTheCorrectPasswordAndKeepTheKeyPair() {
        val identity = mint()
        val publicKey = identity.certificate.publicKey as RSAPublicKey
        for (encoding in Pkcs12Encoding.values()) {
            val (key, certificate) = reopen(SyntheticCertificates.pkcs12(identity, password, encoding))
            assertEquals(encoding.name, publicKey.modulus, (key as RSAPrivateKey).modulus)
            assertEquals(encoding.name, identity.certificate, certificate)
        }
    }

    @Test
    fun theTwoEncodings_areActuallyDifferentOnTheWire() {
        val identity = mint()
        val legacy = SyntheticCertificates.pkcs12(identity, password, Pkcs12Encoding.LEGACY_PBE)
        val modern = SyntheticCertificates.pkcs12(identity, password, Pkcs12Encoding.MODERN_AES)
        // pbeWithSHA1And3-KeyTripleDES-CBC, 1.2.840.113549.1.12.1.3
        val tripleDesPbe = oidDer(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x0C, 0x01, 0x03)
        // PBES2, 1.2.840.113549.1.5.13
        val pbes2 = oidDer(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x05, 0x0D)
        assertTrue("legacy store lacks the 3DES PBE OID", legacy.contains(tripleDesPbe))
        assertFalse("legacy store unexpectedly uses PBES2", legacy.contains(pbes2))
        assertTrue("modern store lacks the PBES2 OID", modern.contains(pbes2))
        assertFalse("modern store unexpectedly uses 3DES PBE", modern.contains(tripleDesPbe))
    }

    @Test
    fun encodingSelection_restoresTheJvmWidePkcs12SecurityProperties() {
        val names = listOf(
            "keystore.pkcs12.keyProtectionAlgorithm",
            "keystore.pkcs12.certProtectionAlgorithm",
            "keystore.pkcs12.macAlgorithm",
        )
        val before = names.map { java.security.Security.getProperty(it) }
        val identity = mint()
        for (encoding in Pkcs12Encoding.values()) SyntheticCertificates.pkcs12(identity, password, encoding)
        assertEquals(before, names.map { java.security.Security.getProperty(it) })
    }

    @Test
    fun wrongPassword_raisesAnUnrecoverableKeyOrKeystoreExceptionNeverANullKey() {
        val identity = mint()
        val wrong = "not-the-password".toCharArray()
        for (encoding in Pkcs12Encoding.values()) {
            val bytes = SyntheticCertificates.pkcs12(identity, password, encoding)
            val failure = assertThrows(Exception::class.java) {
                val store = KeyStore.getInstance("PKCS12")
                store.load(ByteArrayInputStream(bytes), wrong)
                val key = store.getKey("signing", wrong)
                assertNotNull("a wrong password must not yield a null key", key)
            }
            assertTrue(
                "${encoding.name}: $failure",
                failure is UnrecoverableKeyException ||
                    (failure is IOException && failure.cause is UnrecoverableKeyException),
            )
        }
    }
}
