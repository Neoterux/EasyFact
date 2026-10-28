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

import com.neoterux.easyfact.invoicing.result.Outcome

/**
 * The exact octets that were signed and that must be transmitted.
 *
 * The constructor is private. Code that needs to send a signed document can only take one from a
 * signer; it cannot hand the transport a string or a re-parsed document. That is how "the bytes
 * signed are the bytes transmitted" is enforced by the compiler instead of by convention.
 *
 * There are exactly two factories. [Companion.wrap] is internal and only the signer calls it.
 * [Companion.fromStoredOctets] re-admits octets that were signed on an earlier run, for the
 * resend path; it requires the digest recorded when those octets were stored and is gated behind
 * the [StoredOctetRehydration] opt-in, so every use of it is deliberate and greppable.
 *
 * There is no `String` accessor and [toString] does not render the content.
 */
class SignedDocument private constructor(private val bytes: ByteArray) {

    /** A copy of the signed octets. */
    fun octets(): ByteArray = bytes.copyOf()

    /** Whether the signed octets are exactly [other]. */
    fun contentEquals(other: ByteArray): Boolean = bytes.contentEquals(other)

    /**
     * The SHA-256 of the signed octets, for the caller that stores the bytes to record at the
     * same moment it stores them. [Companion.fromStoredOctets] requires it back.
     */
    fun sha256(): ByteArray = TODO("RED: the digest is not implemented yet")

    /** Never renders the content, which carries customer data and a certificate. */
    override fun toString(): String = "SignedDocument(${bytes.size} bytes)"

    companion object {
        /** Wraps a copy of [bytes]. Reserved for the signer. */
        internal fun wrap(bytes: ByteArray): SignedDocument = SignedDocument(bytes.copyOf())

        /**
         * Re-wraps octets that were signed on an earlier run, for a resend.
         *
         * This is the only way back into this type that does not go through a [Signer], and it
         * exists because `docs/sri-electronic-invoicing.md` section 7 requires a resend to
         * retransmit the stored bytes rather than sign again. It is deliberately narrow: the
         * caller must supply the SHA-256 recorded when the octets were stored, the comparison is
         * constant-time, and no document is produced unless it matches.
         *
         * @param octets the stored signed document
         * @param expectedSha256 the digest recorded when [octets] were stored
         */
        @StoredOctetRehydration
        fun fromStoredOctets(
            octets: ByteArray,
            expectedSha256: ByteArray,
        ): Outcome<SignedDocument, RehydrationError> = TODO("RED: rehydration is not implemented yet")
    }
}
