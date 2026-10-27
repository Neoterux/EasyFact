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

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.DERPrintableString
import org.bouncycastle.asn1.DERUTF8String
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date

/**
 * The issuer-name variants a synthetic certificate can carry. They exist because the issuer
 * name is the part of the signature most likely to differ between what a certificate holds and
 * what the signed document must say (research Pitfall 5).
 */
enum class IssuerShape {
    /** Country, organization and common name only. */
    PLAIN,

    /** Adds OID 2.5.4.97 (organizationIdentifier) encoded as a `UTF8String`; renders as `#0c...`. */
    ORGANIZATION_IDENTIFIER_UTF8,

    /** Adds OID 2.5.4.97 encoded as a `PrintableString`; renders as `#13...`. */
    ORGANIZATION_IDENTIFIER_PRINTABLE,

    /** A common name containing non-ASCII letters, to prove they survive a PKCS#12 round trip. */
    NON_ASCII,
}

/**
 * Which cryptographic parameters protect a PKCS#12 store. A certificate file produced by an older
 * tool uses [LEGACY_PBE] (SHA-1 with Triple DES and RC2); one produced by a current JDK uses
 * [MODERN_AES] (PBES2 with AES and a SHA-256 MAC). Whether Android API 31 can open both is an
 * open question, and this switch is what makes it testable on a device.
 */
enum class Pkcs12Encoding { LEGACY_PBE, MODERN_AES }

/** A freshly minted key pair and its self-signed certificate. Held in memory only. */
class SyntheticIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)

/**
 * Mints obviously synthetic RSA-2048 identities and PKCS#12 stores for tests (AGENTS.md section
 * 2.2). Nothing here touches the file system: every function returns bytes or objects, and a
 * caller that wants a file chooses a destination under `build/`. No minted key may ever be written
 * to a tracked path.
 *
 * BouncyCastle is used only to build the certificate, because the JDK has no public API for it.
 * The certificate's own signature algorithm (SHA-256 with RSA) is unrelated to the RSA-SHA1 the SRI
 * requires for signing documents; it only has to make the certificate well formed.
 */
object SyntheticCertificates {

    /** An obviously synthetic store and key password. */
    const val DEFAULT_PASSWORD: String = "synthetic-password"

    private const val ORGANIZATION_IDENTIFIER_OID = "2.5.4.97"
    private const val ORGANIZATION_IDENTIFIER_VALUE = "VATES-A00000000"
    private const val RSA_KEY_BITS = 2048

    private const val KEY_PROTECTION = "keystore.pkcs12.keyProtectionAlgorithm"
    private const val CERT_PROTECTION = "keystore.pkcs12.certProtectionAlgorithm"
    private const val MAC_ALGORITHM = "keystore.pkcs12.macAlgorithm"

    /**
     * A new RSA-2048 key pair with a self-signed certificate valid from [notBefore] to [notAfter].
     * Serial numbers are fixed per [shape], not random, so a regenerated fixture is diffable.
     */
    fun rsa2048(
        shape: IssuerShape = IssuerShape.PLAIN,
        notBefore: Instant,
        notAfter: Instant,
    ): SyntheticIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_BITS) }.generateKeyPair()
        val name = issuerName(shape)
        val holder = JcaX509v3CertificateBuilder(
            name,
            BigInteger.valueOf(1000L + shape.ordinal),
            Date.from(notBefore),
            Date.from(notAfter),
            name,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        return SyntheticIdentity(keyPair.private, JcaX509CertificateConverter().getCertificate(holder))
    }

    /**
     * Serializes [identity] as a PKCS#12 store protected by [password], using the parameter set
     * [encoding] names.
     *
     * The JDK picks its PKCS#12 algorithms from JVM-wide security properties, so they are set
     * around the store call and restored in a `finally` block; the function is synchronized so two
     * callers cannot interleave. A store written here and read back on the same JVM proves the
     * format, not the platform: the Android side is exercised separately by the device matrix.
     */
    @Synchronized
    fun pkcs12(
        identity: SyntheticIdentity,
        password: CharArray,
        encoding: Pkcs12Encoding,
        alias: String = "signing",
    ): ByteArray {
        val names = listOf(KEY_PROTECTION, CERT_PROTECTION, MAC_ALGORITHM)
        val previous = names.associateWith { Security.getProperty(it) }
        try {
            if (encoding == Pkcs12Encoding.LEGACY_PBE) {
                Security.setProperty(KEY_PROTECTION, "PBEWithSHA1AndDESede")
                Security.setProperty(CERT_PROTECTION, "PBEWithSHA1AndRC2_40")
                Security.setProperty(MAC_ALGORITHM, "HmacPBESHA1")
            }
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry(alias, identity.privateKey, password, arrayOf(identity.certificate))
            }
            return ByteArrayOutputStream().also { store.store(it, password) }.toByteArray()
        } finally {
            for ((name, value) in previous) Security.setProperty(name, value ?: "")
        }
    }

    private fun issuerName(shape: IssuerShape): X500Name {
        val builder = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.C, "EC")
            .addRDN(BCStyle.O, "Test CA S.A.")
            .addRDN(BCStyle.CN, if (shape == IssuerShape.NON_ASCII) "Test Ñandú CA" else "Test Signing CA")
        when (shape) {
            IssuerShape.ORGANIZATION_IDENTIFIER_UTF8 ->
                builder.addRDN(ASN1ObjectIdentifier(ORGANIZATION_IDENTIFIER_OID), DERUTF8String(ORGANIZATION_IDENTIFIER_VALUE))
            IssuerShape.ORGANIZATION_IDENTIFIER_PRINTABLE ->
                builder.addRDN(ASN1ObjectIdentifier(ORGANIZATION_IDENTIFIER_OID), DERPrintableString(ORGANIZATION_IDENTIFIER_VALUE))
            IssuerShape.PLAIN, IssuerShape.NON_ASCII -> Unit
        }
        return builder.build()
    }
}
