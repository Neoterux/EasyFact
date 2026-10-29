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

/**
 * Several SRI messages ranked for display (SRI-13).
 *
 * @property headline the message to lead with, or `null` when there were no messages; always the first of [all]
 * @property all every message in rank order, warnings included; nothing is dropped
 */
data class RankedMessages(
    val headline: ExplainedMessage?,
    val all: List<ExplainedMessage>,
)

/**
 * Ranks the messages of one SRI answer (D-06, D-07).
 *
 * The order is: severity, errors before warnings; then the specificity of the advised [NextAction], most
 * specific first, which is the declaration order of that enum; then the SRI's own order as a stable tiebreak.
 * Severity is the one [ErrorCatalogue.explain] derives: the tipo first, then the entry's category, and an
 * undeterminable severity is an error (research A6), so a warning is never promoted to an error and a rejection
 * is never hidden (threat T-03-20).
 *
 * **Nothing is suppressed (D-06).** The headline is only the first message to lead with. Every warning,
 * including code 60 that the SRI attaches to every authorization issued in the test environment, stays in
 * [RankedMessages.all] for the caller to show.
 *
 * The action hint is advisory (D-02): it orders messages for display and decides nothing; Phase 6's `decide()`
 * keeps sole authority over every state transition.
 */
object MessageRanking {

    /** Explains and ranks [messages]; an empty list gives no headline and an empty list. */
    fun rank(messages: List<SriMessage>): RankedMessages {
        val ranked = messages
            .map { ErrorCatalogue.explain(it) }
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<ExplainedMessage>> { it.value.severity.ordinal }
                    .thenBy { it.value.entry.nextAction.ordinal }
                    .thenBy { it.index },
            )
            .map { it.value }
        return RankedMessages(ranked.firstOrNull(), ranked)
    }
}
