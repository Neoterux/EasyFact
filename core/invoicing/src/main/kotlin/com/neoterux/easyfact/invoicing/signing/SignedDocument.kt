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

/**
 * The exact octets that were signed and that must be transmitted.
 *
 * The constructor is private and the only factory is [Companion.wrap], which only the signing
 * package calls. Code that needs to send a signed document can therefore only take it from a
 * signer; it cannot hand the transport a string or a re-parsed document. That is how "the bytes
 * signed are the bytes transmitted" is enforced by the compiler instead of by convention.
 *
 * There is no `String` accessor and [toString] does not render the content.
 */
class SignedDocument private constructor(private val bytes: ByteArray) {

    /** A copy of the signed octets. */
    fun octets(): ByteArray = bytes.copyOf()

    /** Whether the signed octets are exactly [other]. */
    fun contentEquals(other: ByteArray): Boolean = bytes.contentEquals(other)

    /** Never renders the content, which carries customer data and a certificate. */
    override fun toString(): String = "SignedDocument(${bytes.size} bytes)"

    internal companion object {
        /** Wraps a copy of [bytes]. Reserved for the signer. */
        fun wrap(bytes: ByteArray): SignedDocument = SignedDocument(bytes.copyOf())
    }
}
