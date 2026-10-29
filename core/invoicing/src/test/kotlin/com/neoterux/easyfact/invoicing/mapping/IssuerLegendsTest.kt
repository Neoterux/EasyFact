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

package com.neoterux.easyfact.invoicing.mapping

import com.neoterux.easyfact.invoicing.factura.FacturaWriter
import com.neoterux.easyfact.invoicing.result.Outcome
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The typed issuer legends (D-20): the two legal RIMPE texts and the withholding-agent resolution.
 *
 * The expected lengths are written by hand so that a changed legend shows up here, and each text is also
 * run through the writer's own `contribuyenteRimpe` facet, which `FacturaFacetParityTest` keeps equal to
 * the vendored schema.
 */
class IssuerLegendsTest {

    private val rimpeFacet: FacturaWriter.Facet = FacturaWriter.FACETS.single {
        it.path == "factura/infoTributaria/contribuyenteRimpe"
    }

    private fun resolution(raw: String): Outcome<WithholdingAgentResolution, WithholdingAgentError> =
        WithholdingAgentResolution.of(raw)

    private fun numberOf(raw: String): String = when (val outcome = resolution(raw)) {
        is Outcome.Ok -> outcome.value.number
        is Outcome.Err -> throw AssertionError("expected a resolution for the case, got ${outcome.error}")
    }

    @Test
    fun theEmprendedorLegendIsTwentySevenCharactersLong() {
        assertEquals(27, RimpeLegend.EMPRENDEDOR.documentText?.length)
    }

    @Test
    fun theNegocioPopularLegendIsFortyFiveCharactersLong() {
        assertEquals(45, RimpeLegend.NEGOCIO_POPULAR.documentText?.length)
    }

    @Test
    fun noLegendHasNoText() {
        assertNull(RimpeLegend.NONE.documentText)
    }

    @Test
    fun bothLegendsAreAlreadyInNormalisationFormC() {
        for (legend in listOf(RimpeLegend.EMPRENDEDOR, RimpeLegend.NEGOCIO_POPULAR)) {
            val text = legend.documentText
            assertNotNull("legend ${legend.name} has no text", text)
            assertTrue("legend ${legend.name} is not NFC", Normalizer.isNormalized(text!!, Normalizer.Form.NFC))
        }
    }

    @Test
    fun bothLegendsSatisfyTheWritersContribuyenteRimpeFacet() {
        for (legend in listOf(RimpeLegend.EMPRENDEDOR, RimpeLegend.NEGOCIO_POPULAR)) {
            val text = legend.documentText
            assertNotNull("legend ${legend.name} has no text", text)
            assertTrue("legend ${legend.name} is refused by the facet", rimpeFacet.acceptsFormat(text!!))
        }
    }

    @Test
    fun theTwoLegendsAreDistinct() {
        assertNotEquals(RimpeLegend.EMPRENDEDOR.documentText, RimpeLegend.NEGOCIO_POPULAR.documentText)
    }

    @Test
    fun leadingZerosAreStrippedFromTheResolutionNumber() {
        assertEquals("12", numberOf("00012"))
    }

    @Test
    fun anEightDigitNumberIsAccepted() {
        assertEquals("12345678", numberOf("12345678"))
    }

    @Test
    fun aNumberWithLeadingZerosThatShrinksToEightDigitsIsAccepted() {
        assertEquals("12345678", numberOf("00" + "12345678"))
    }

    @Test
    fun aNineDigitNumberIsTooLong() {
        assertEquals(Outcome.Err(WithholdingAgentError.TooLong(8, 9)), resolution("123456789"))
    }

    @Test
    fun theReportedLengthIsTheLengthAfterTheZerosAreStripped() {
        assertEquals(Outcome.Err(WithholdingAgentError.TooLong(8, 10)), resolution("0".repeat(4) + "1".repeat(10)))
    }

    @Test
    fun anEmptyTextIsEmpty() {
        assertEquals(Outcome.Err(WithholdingAgentError.Empty), resolution(""))
    }

    @Test
    fun blankTextIsEmpty() {
        assertEquals(Outcome.Err(WithholdingAgentError.Empty), resolution("   "))
    }

    @Test
    fun onlyZerosIsEmpty() {
        assertEquals(Outcome.Err(WithholdingAgentError.Empty), resolution("000"))
    }

    @Test
    fun aLetterAmongTheDigitsIsNotNumeric() {
        assertEquals(Outcome.Err(WithholdingAgentError.NotNumeric), resolution("12A"))
    }

    @Test
    fun anArabicIndicDigitIsNotNumeric() {
        assertEquals(Outcome.Err(WithholdingAgentError.NotNumeric), resolution("١٢"))
    }

    @Test
    fun aFullwidthDigitIsNotNumeric() {
        assertEquals(Outcome.Err(WithholdingAgentError.NotNumeric), resolution("１２"))
    }

    @Test
    fun aSignOrASeparatorIsNotNumeric() {
        for (raw in listOf("-12", "+12", "1 2", "1.2")) {
            assertEquals("case $raw", Outcome.Err(WithholdingAgentError.NotNumeric), resolution(raw))
        }
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("12", numberOf(" 12 "))
    }

    @Test
    fun theNumericCheckRunsBeforeTheEmptyCheck() {
        assertEquals(Outcome.Err(WithholdingAgentError.NotNumeric), resolution("0A"))
    }

    @Test
    fun resolutionsAreComparedByValue() {
        val first = (resolution("0012") as Outcome.Ok).value
        val second = (resolution("12") as Outcome.Ok).value
        val other = (resolution("13") as Outcome.Ok).value

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertNotEquals(first, other)
    }
}
