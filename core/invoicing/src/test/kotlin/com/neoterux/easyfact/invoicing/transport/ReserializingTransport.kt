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

package com.neoterux.easyfact.invoicing.transport

import com.neoterux.easyfact.invoicing.signing.SignedDocument

/**
 * **This class exists to fail.** It parses the signed octets into a DOM and serializes them again
 * before encoding, which is exactly the mistake AGENTS.md section 6.2 forbids, and it is the
 * committed proof that [ByteIdentityTest] can detect that mistake rather than merely assert
 * against it.
 *
 * It must never be used by production code or by the probe. A gate that has never been seen to
 * fail is not evidence; this is how the byte-identity gate is seen to fail.
 */
class ReserializingTransport : DocumentTransport {

    private var recorded: ByteArray? = null

    /** The octets handed to the base64 encoder on the last [submit] — the re-serialized ones. */
    val encodedOctets: ByteArray
        get() = checkNotNull(recorded) { "nothing has been submitted yet" }.copyOf()

    override fun submit(document: SignedDocument): String = TODO("RED: the mutation fixture is not implemented yet")
}
