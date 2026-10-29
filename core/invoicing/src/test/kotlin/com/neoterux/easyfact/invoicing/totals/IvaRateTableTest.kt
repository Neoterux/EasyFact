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

package com.neoterux.easyfact.invoicing.totals

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue is pinned row by row against Ficha v2.34 Table 17. Percentages are compared with
 * [BigDecimal.compareTo] through a rendered string, never with [BigDecimal.equals], which is
 * scale-sensitive.
 */
class IvaRateTableTest {

    private fun row(code: String): IvaRate =
        IvaRateTable.lookup(code) ?: throw AssertionError("code $code must be listed")

    private fun percentOf(code: String): String =
        (row(code).percent ?: throw AssertionError("code $code must carry a percentage")).toPlainString()

    @Test
    fun everyRatedCodeCarriesItsTablePercentage() {
        assertEquals("0", percentOf("0"))
        assertEquals("12", percentOf("2"))
        assertEquals("14", percentOf("3"))
        assertEquals("15", percentOf("4"))
        assertEquals("5", percentOf("5"))
        assertEquals("13", percentOf("10"))
        for (code in listOf("0", "2", "3", "4", "5", "10")) {
            assertEquals("code $code", IvaRateKind.RATED, row(code).kind)
        }
    }

    @Test
    fun noObjetoIsNotSubjectWithAZeroPercentage() {
        assertEquals(IvaRateKind.NOT_SUBJECT, row("6").kind)
        assertEquals("0", percentOf("6"))
    }

    @Test
    fun exentoIsExemptWithAZeroPercentage() {
        assertEquals(IvaRateKind.EXEMPT, row("7").kind)
        assertEquals("0", percentOf("7"))
    }

    @Test
    fun ivaDiferenciadoHasNoFixedPercentage() {
        assertEquals(IvaRateKind.DIFFERENTIATED, row("8").kind)
        assertNull(row("8").percent)
    }

    @Test
    fun anUnlistedCodeIsNeitherTrimmedNorGuessed() {
        for (code in listOf("1", "9", "11", "", " 4", "4 ", "04", "four")) {
            assertNull("code '$code' must not resolve", IvaRateTable.lookup(code))
        }
    }

    @Test
    fun theTableHasExactlyNineRowsWithUniqueCodes() {
        assertEquals(9, IvaRateTable.entries.size)
        assertEquals(9, IvaRateTable.entries.map { it.code }.toSet().size)
        assertEquals(setOf("0", "2", "3", "4", "5", "6", "7", "8", "10"), IvaRateTable.entries.map { it.code }.toSet())
    }

    @Test
    fun everyListedCodeResolvesToItsOwnRow() {
        for (entry in IvaRateTable.entries) {
            assertEquals(entry, IvaRateTable.lookup(entry.code))
        }
    }

    @Test
    fun aRatedRowAlwaysHasAPercentageAndOnlyDifferentiatedDoesNot() {
        for (entry in IvaRateTable.entries) {
            if (entry.kind == IvaRateKind.DIFFERENTIATED) {
                assertNull("code ${entry.code}", entry.percent)
            } else {
                assertNotNull("code ${entry.code}", entry.percent)
            }
        }
    }

    @Test
    fun aRowIsExactlyCodePercentAndKindWithNoDateOfAnyKind() {
        // D-29's effective dates were superseded by the owner on 2026-10-28: the table is date-free.
        val fields = IvaRate::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet()
        assertEquals(setOf("code", "percent", "kind"), fields)
        for (field in IvaRate::class.java.declaredFields) {
            assertTrue("no java.time field: ${field.name}", !field.type.name.startsWith("java.time"))
        }
        // Compile-time check that the three named arguments are the whole constructor.
        val built = IvaRate(code = "x", percent = BigDecimal("1"), kind = IvaRateKind.RATED)
        assertEquals("x", built.code)
    }
}
