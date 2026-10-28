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
 * Why stored octets could not be re-wrapped as a [SignedDocument].
 *
 * Neither variant carries the octets or either digest. These values are rendered into the
 * on-device diagnostics log (AGENTS.md section 7), and a signed factura is customer data with a
 * certificate inside it; a digest of a document whose shape is known is not a safe thing to
 * print either.
 */
sealed interface RehydrationError {

    /** The SHA-256 of the supplied octets is not the digest the caller recorded. */
    data object DigestMismatch : RehydrationError

    /** There were no octets to re-wrap. */
    data object EmptyOctets : RehydrationError
}
