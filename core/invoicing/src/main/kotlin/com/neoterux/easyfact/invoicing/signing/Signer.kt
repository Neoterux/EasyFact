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
import com.neoterux.easyfact.invoicing.result.SigningError
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument

/**
 * Turns a canonical document into a signed one.
 *
 * There is exactly one entry point and it takes a [CanonicalDocument]. A `sign(String)` or
 * `sign(ByteArray)` overload would let a caller sign bytes that never passed through the
 * emitter, which is the seam this type exists to close.
 *
 * The interface has one implementation today, [XadesBesSigner]. It exists so that a replacement
 * can be swapped in as one class with the whole proof suite running against it unchanged.
 */
interface Signer {
    fun sign(document: CanonicalDocument, identity: SigningIdentity): Outcome<SignedDocument, SigningError>
}
