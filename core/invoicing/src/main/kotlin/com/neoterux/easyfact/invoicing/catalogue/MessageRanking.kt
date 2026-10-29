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

package com.neoterux.easyfact.invoicing.catalogue

import com.neoterux.easyfact.invoicing.soap.SriMessage

/** Placeholder ranking: explains each message and keeps the received order. */
data class RankedMessages(val headline: ExplainedMessage?, val all: List<ExplainedMessage>)

/** Placeholder until the ordering is implemented. */
object MessageRanking {
    fun rank(messages: List<SriMessage>): RankedMessages {
        val explained = messages.map { ErrorCatalogue.explain(it) }
        return RankedMessages(explained.firstOrNull(), explained)
    }
}
