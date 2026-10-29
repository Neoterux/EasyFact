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
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The ranking of several SRI messages (SRI-13, D-06, D-07): errors before warnings, then the specificity of
 * the advised action, then the SRI's own order; the first is the headline and nothing is ever dropped.
 */
class MessageRankingTest {

    private fun message(identifier: String, type: String?) = SriMessage(identifier, "text", null, type)

    private fun error(identifier: String) = message(identifier, "ERROR")

    private fun warning(identifier: String) = message(identifier, "ADVERTENCIA")

    private fun identifiers(ranked: RankedMessages): List<String> = ranked.all.map { it.message.identifier }

    @Test
    fun noMessagesGiveNoHeadlineAndAnEmptyList() {
        val ranked = MessageRanking.rank(emptyList())
        assertNull(ranked.headline)
        assertEquals(emptyList<ExplainedMessage>(), ranked.all)
    }

    @Test
    fun aLoneWarningIsItsOwnHeadlineAndIsNeverSuppressed() {
        val ranked = MessageRanking.rank(listOf(warning("60")))
        assertEquals(1, ranked.all.size)
        val headline = ranked.headline!!
        assertEquals("60", headline.message.identifier)
        assertEquals(Severity.WARNING, headline.severity)
        assertEquals(CatalogueEntry.SRI_60, headline.entry)
        assertSame(headline, ranked.all.single())
    }

    @Test
    fun aLoneErrorIsItsOwnHeadline() {
        val ranked = MessageRanking.rank(listOf(error("39")))
        assertEquals(CatalogueEntry.SRI_39, ranked.headline!!.entry)
        assertEquals(Severity.ERROR, ranked.headline!!.severity)
        assertEquals(1, ranked.all.size)
    }

    @Test
    fun errorsComeBeforeWarningsAndTheWarningStaysInTheList() {
        val ranked = MessageRanking.rank(listOf(warning("60"), error("39")))
        assertEquals("39", ranked.headline!!.message.identifier)
        assertEquals(listOf("39", "60"), identifiers(ranked))
    }

    @Test
    fun anErrorOutranksAWarningWhateverItsAdvisedAction() {
        // 70 asks to wait and 59 asks for nothing; 99 is unknown. The error still leads.
        val ranked = MessageRanking.rank(listOf(warning("59"), warning("123"), error("70")))
        assertEquals(listOf("70", "59", "123"), identifiers(ranked))
    }

    @Test
    fun theMoreSpecificActionLeadsAmongErrors() {
        val ranked = MessageRanking.rank(listOf(error("50"), error("46")))
        assertEquals("46", ranked.headline!!.message.identifier)
        assertEquals(listOf("46", "50"), identifiers(ranked))
    }

    @Test
    fun theFullSpecificityOrderIsFollowed() {
        // 46 fix and resubmit, 2 portal, 70 wait, 99 unknown (contact the SRI), 26 export diagnostics, 60 warning.
        val ranked = MessageRanking.rank(
            listOf(warning("60"), error("26"), error("99"), error("70"), error("2"), error("46")),
        )
        assertEquals(listOf("46", "2", "70", "99", "26", "60"), identifiers(ranked))
    }

    @Test
    fun sameSeverityAndActionKeepTheSriOrder() {
        assertEquals(listOf("46", "69"), identifiers(MessageRanking.rank(listOf(error("46"), error("69")))))
        assertEquals(listOf("69", "46"), identifiers(MessageRanking.rank(listOf(error("69"), error("46")))))
    }

    @Test
    fun sameSeverityAndActionAmongWarningsKeepTheSriOrder() {
        assertEquals(listOf("62", "59", "68"), identifiers(MessageRanking.rank(listOf(warning("62"), warning("59"), warning("68")))))
    }

    @Test
    fun theTipoDecidesEvenWhenTheCatalogueSaysOtherwise() {
        val ranked = MessageRanking.rank(listOf(warning("39"), message("60", "ERROR")))
        assertEquals(listOf("60", "39"), identifiers(ranked))
        assertEquals(Severity.ERROR, ranked.all[0].severity)
        assertEquals(Severity.WARNING, ranked.all[1].severity)
    }

    @Test
    fun aMissingTipoFallsBackToTheCategoryAndAnUnknownCodeIsAnError() {
        val ranked = MessageRanking.rank(listOf(message("60", null), message("39", null), message("99", null)))
        assertEquals(listOf("39", "99", "60"), identifiers(ranked))
        assertEquals(
            listOf(Severity.ERROR, Severity.ERROR, Severity.WARNING),
            ranked.all.map { it.severity },
        )
    }

    @Test
    fun everyMessageIsKeptUnchangedInTheList() {
        val input = listOf(warning("60"), error("39"), error("99"), warning("59"), error("46"), error("50"))
        val ranked = MessageRanking.rank(input)
        assertEquals(input.size, ranked.all.size)
        for (original in input) {
            assertSame(original, ranked.all.single { it.message === original }.message)
        }
        assertSame(ranked.all.first(), ranked.headline)
    }

    @Test
    fun aTurkishDefaultLocaleDoesNotChangeAnySeverityOrOrder() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val ranked = MessageRanking.rank(
                listOf(message("60", "advertencia"), message("123", " Advertencia "), message("39", "error")),
            )
            assertEquals(listOf("39", "60", "123"), identifiers(ranked))
            assertEquals(
                listOf(Severity.ERROR, Severity.WARNING, Severity.WARNING),
                ranked.all.map { it.severity },
            )
            assertEquals(CatalogueEntry.SRI_UNKNOWN_WARNING, ranked.all[2].entry)
        } finally {
            Locale.setDefault(saved)
        }
    }
}
