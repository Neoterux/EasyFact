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

/** Why response bytes were refused. */
enum class MalformedReason {
    EMPTY,
    TOO_LARGE,
    NUL_BYTE,
    NOT_UTF8,
    DOCTYPE,
    ENTITY_DECLARATION,
    NOT_WELL_FORMED,
    RAW_SLICE_MISMATCH,
}

/** RED-phase stub: compiles, accepts everything. Replaced in the GREEN commit. */
object ResponseGuard {
    const val COMPROBANTE_MAX_BYTES: Int = 320 * 1024
    const val ESCAPING_ALLOWANCE: Int = 2
    const val MAX_AUTHORIZATION_ENTRIES: Int = 12
    const val MAX_RESPONSE_BYTES: Int = 8 * 1024 * 1024

    fun check(bytes: ByteArray): MalformedReason? = bytes.let { null }
}
