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
 * The seam between a signed document and the wire, expressed so that nothing else can reach it.
 *
 * [submit] takes a [SignedDocument] and there is deliberately no `ByteArray` or `String`
 * overload. D-11 made that a compiler-enforced property of the type; this interface is the
 * behavioural restatement of it, and [ByteIdentityTest] is what turns the restatement into
 * evidence. AGENTS.md section 6.2: "The bytes signed must be the bytes transmitted. Never
 * serialize, parse and re-serialize between signing and sending."
 *
 * Test source. The production codec is Phase 3's work and must not grow out of this file; what
 * belongs to the production path is the shape of the contract, not this implementation of it.
 *
 * @return the envelope text that would be transmitted
 */
fun interface DocumentTransport {
    fun submit(document: SignedDocument): String
}
