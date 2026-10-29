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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FieldLimits] reads the writer's facet table. The expected numbers are written by hand here, once,
 * so that a facet changed in the writer (and, through `FacturaFacetParityTest`, in the schema) is seen.
 */
class FieldLimitsTest {

    private val everyPath = listOf(
        FieldLimits.ISSUER_LEGAL_NAME,
        FieldLimits.ISSUER_TRADE_NAME,
        FieldLimits.HEAD_OFFICE_ADDRESS,
        FieldLimits.ESTABLISHMENT_ADDRESS,
        FieldLimits.SPECIAL_TAXPAYER,
        FieldLimits.BUYER_LEGAL_NAME,
        FieldLimits.BUYER_IDENTIFICATION,
        FieldLimits.LINE_CODE,
        FieldLimits.LINE_DESCRIPTION,
        FieldLimits.ADDITIONAL_FIELD,
        FieldLimits.ADDITIONAL_FIELD_NAME,
        FieldLimits.CREDIT_TERM_UNIT,
        FieldLimits.RIMPE_LEGEND,
    )

    @Test
    fun everyPathConstantResolvesToAFacet() {
        for (path in everyPath) {
            assertNotNull("no facet for $path", FieldLimits.facet(path))
        }
    }

    @Test
    fun theFacetFoundIsTheOneAtThatPath() {
        for (path in everyPath) {
            assertEquals(path, FieldLimits.facet(path)?.path)
        }
    }

    @Test
    fun theThreeHundredCharacterTextsHaveThatLimit() {
        val paths = listOf(
            FieldLimits.ISSUER_LEGAL_NAME,
            FieldLimits.ISSUER_TRADE_NAME,
            FieldLimits.HEAD_OFFICE_ADDRESS,
            FieldLimits.ESTABLISHMENT_ADDRESS,
            FieldLimits.BUYER_LEGAL_NAME,
            FieldLimits.LINE_DESCRIPTION,
            FieldLimits.ADDITIONAL_FIELD,
            FieldLimits.ADDITIONAL_FIELD_NAME,
        )
        for (path in paths) {
            assertEquals("limit of $path", 300, FieldLimits.maxLength(path))
        }
    }

    @Test
    fun theLineCodeHasTwentyFiveCharacters() {
        assertEquals(25, FieldLimits.maxLength(FieldLimits.LINE_CODE))
    }

    @Test
    fun theBuyerIdentificationHasTwentyCharacters() {
        assertEquals(20, FieldLimits.maxLength(FieldLimits.BUYER_IDENTIFICATION))
    }

    @Test
    fun theSpecialTaxpayerNumberHasThirteenCharacters() {
        assertEquals(13, FieldLimits.maxLength(FieldLimits.SPECIAL_TAXPAYER))
    }

    @Test
    fun theCreditTermUnitHasTenCharacters() {
        assertEquals(10, FieldLimits.maxLength(FieldLimits.CREDIT_TERM_UNIT))
    }

    @Test
    fun anUnknownPathHasNoFacetAndNoLimit() {
        assertNull(FieldLimits.facet("factura/noSuchElement"))
        assertNull(FieldLimits.maxLength("factura/noSuchElement"))
    }

    @Test
    fun theAdditionalFieldFacetCarriesTheRepeatCapTheMapperEnforces() {
        assertEquals(15, FieldLimits.facet(FieldLimits.ADDITIONAL_FIELD)?.maxOccurs)
    }

    @Test
    fun aPatternedElementHasNoLengthLimit() {
        assertNull(FieldLimits.maxLength(FieldLimits.RIMPE_LEGEND))
    }
}
