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

import com.neoterux.easyfact.invoicing.result.Outcome

/** Placeholder with the final signatures; replaced by the real parser in the GREEN commit. */
object SriResponseParser {

    fun parseReception(bytes: ByteArray): Outcome<ReceptionResponse, ResponseProblem> =
        Outcome.Err(ResponseProblem.UnexpectedBody(RawResponse.of(bytes)))

    fun parseAuthorization(bytes: ByteArray): Outcome<AuthorizationResponse, ResponseProblem> =
        Outcome.Err(ResponseProblem.UnexpectedBody(RawResponse.of(bytes)))
}
