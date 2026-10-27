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

import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * A private key and the certificate that vouches for its public half.
 *
 * [toString] is fixed text. The default rendering of a certificate carries the subject and the
 * issuer, and these values reach logs.
 */
class SigningIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    override fun toString(): String = "SigningIdentity(redacted)"
}
