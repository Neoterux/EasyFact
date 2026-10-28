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

import java.security.InvalidKeyException
import java.security.InvalidParameterException
import java.security.PrivateKey
import java.security.Provider
import java.security.ProviderException
import java.security.PublicKey
import java.security.Security
import java.security.Signature
import java.security.SignatureSpi
import java.util.concurrent.atomic.AtomicInteger

/**
 * A private key whose class the platform providers do not recognise: it is not an `RSAKey`, so the
 * signer must treat it as opaque, as it would a hardware-backed or Android Keystore key. It wraps a
 * real synthetic private key so that a provider which does recognise the class can still sign.
 */
class OpaqueTestKey(internal val real: PrivateKey) : PrivateKey {
    override fun getAlgorithm(): String = "RSA"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

/**
 * A test-only JCA provider that signs with `SHA1withRSA` for [OpaqueTestKey] and then fails.
 *
 * Android Keystore-backed keys surface provider trouble (a locked key store, a revoked key, a
 * hardware fault) as `java.security.ProviderException`, which is a `RuntimeException` and not a
 * `GeneralSecurityException`. This provider reproduces that on the JVM so the signer's promise that
 * no exception leaves it can be tested.
 *
 * The first [allowedSignatures] sign calls delegate to the JDK's own `SHA1withRSA` and succeed;
 * every later one throws. `0` fails the signer's key check, which is its first sign call, and `1`
 * lets the key check pass and fails the signature over `SignedInfo`.
 *
 * Provider registration is JVM-wide, so the provider restricts itself with `SupportedKeyClasses`
 * to [OpaqueTestKey]: no other key in any other test is ever routed to it. Use it only through
 * [use], which removes it again in a `finally` block.
 */
class ThrowingSignatureProvider private constructor(
    private val allowedSignatures: Int,
) : Provider(NAME, "1.0", "Test-only provider that throws ProviderException after N signatures") {

    private val signCalls = AtomicInteger()

    init {
        putService(
            object : Service(
                this,
                "Signature",
                "SHA1withRSA",
                ThrowingSignatureSpi::class.java.name,
                null,
                mapOf("SupportedKeyClasses" to OpaqueTestKey::class.java.name),
            ) {
                override fun newInstance(constructorParameter: Any?): Any = ThrowingSignatureSpi(this@ThrowingSignatureProvider)
            },
        )
    }

    private fun beforeSign() {
        if (signCalls.getAndIncrement() >= allowedSignatures) {
            throw ProviderException("test provider failure")
        }
    }

    private class ThrowingSignatureSpi(private val owner: ThrowingSignatureProvider) : SignatureSpi() {
        private val delegate = Signature.getInstance("SHA1withRSA", "SunRsaSign")

        override fun engineInitVerify(publicKey: PublicKey) = delegate.initVerify(publicKey)

        override fun engineInitSign(privateKey: PrivateKey) {
            val key = privateKey as? OpaqueTestKey ?: throw InvalidKeyException("not the test key class")
            delegate.initSign(key.real)
        }

        override fun engineUpdate(b: Byte) = delegate.update(b)

        override fun engineUpdate(b: ByteArray, off: Int, len: Int) = delegate.update(b, off, len)

        override fun engineSign(): ByteArray {
            owner.beforeSign()
            return delegate.sign()
        }

        override fun engineVerify(sigBytes: ByteArray): Boolean = delegate.verify(sigBytes)

        @Deprecated("Deprecated in SignatureSpi")
        override fun engineSetParameter(param: String?, value: Any?) {
            throw InvalidParameterException("no parameters")
        }

        @Deprecated("Deprecated in SignatureSpi")
        override fun engineGetParameter(param: String?): Any? {
            throw InvalidParameterException("no parameters")
        }
    }

    companion object {
        const val NAME = "EasyFactThrowingTestProvider"

        /**
         * Runs [block] with the provider at position 1, allowing [allowedSignatures] sign calls to
         * succeed, and removes it afterwards whatever happens.
         */
        fun <T> use(allowedSignatures: Int, block: () -> T): T {
            check(Security.getProvider(NAME) == null) { "the test provider is already registered" }
            Security.insertProviderAt(ThrowingSignatureProvider(allowedSignatures), 1)
            try {
                return block()
            } finally {
                Security.removeProvider(NAME)
            }
        }
    }
}
