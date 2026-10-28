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
 * The honest transport: it base64-encodes the signer's own octets and encodes nothing else.
 *
 * It also records the exact array it handed to the encoder. Without that record a test could
 * only compare the decoded payload against a freshly signed document, and two independently
 * produced documents agreeing proves the signer is deterministic, not that the transport
 * transmitted what it was given. Recording closes that gap: the comparison runs
 * `SignedDocument.octets()` -> [encodedOctets] -> decoded payload, and every link is the same
 * array.
 */
class RecordingTransport : DocumentTransport {

    private var recorded: ByteArray? = null

    /** The octets handed to the base64 encoder on the last [submit]. */
    val encodedOctets: ByteArray
        get() = checkNotNull(recorded) { "nothing has been submitted yet" }.copyOf()

    override fun submit(document: SignedDocument): String = TODO("RED: the honest transport is not implemented yet")
}
