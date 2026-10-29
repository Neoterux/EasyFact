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

package com.neoterux.easyfact.invoicing.soap

/** A half-open range of byte indices into the caller's array. */
data class ByteSpan(val start: Int, val endExclusive: Int)

/** One top-level `autorizacion` element and what it carries. */
data class AuthorizationSlice(
    val element: ByteSpan,
    val comprobanteContent: ByteSpan?,
    val inheritsNamespace: Boolean,
)

/** RED-phase stub: compiles, finds nothing. Replaced in the GREEN commit. */
object RawScan {
    const val MAX_ELEMENT_DEPTH = 64

    fun authorizations(bytes: ByteArray): List<AuthorizationSlice>? = bytes.let { null }
}
