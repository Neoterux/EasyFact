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

import com.neoterux.easyfact.invoicing.accesskey.AccessKey
import com.neoterux.easyfact.invoicing.signing.SignedDocument

/** RED-phase stub: compiles, builds nothing. Replaced in the GREEN commit. */
object SoapRequests {

    fun validarComprobante(document: SignedDocument): ByteArray = ByteArray(0).also { document.octets() }

    fun autorizacionComprobante(accessKey: AccessKey): ByteArray = ByteArray(0).also { accessKey.digits }
}
