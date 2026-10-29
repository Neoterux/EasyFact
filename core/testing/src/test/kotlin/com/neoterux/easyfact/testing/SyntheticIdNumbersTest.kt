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

package com.neoterux.easyfact.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The synthetic identification generator. Check digits are verified here with the sum-to-zero
 * formulation (every digit, check digit included, weighted and summed), which is written
 * independently of the generator's compute-the-digit formulation.
 *
 * Assertion messages name the kind and the index only. A generated value may coincide with an
 * issued number, so it is never put in a message.
 */
class SyntheticIdNumbersTest {

    private val indices = 0..999

    private val provinces: Set<String> = ((1..24) + 30).map { it.toString().padStart(2, '0') }.toSet()

    private fun isAsciiDigits(value: String): Boolean = value.all { it in '0'..'9' }

    private fun weightedSum(value: String, weights: IntArray): Int =
        weights.indices.sumOf { (value[it] - '0') * weights[it] }

    /** Cedula rule over the first ten digits: weights 2,1,2,1..., products above 9 reduced by 9. */
    private fun cedulaSumIsDivisibleByTen(value: String): Boolean {
        var sum = 0
        for (position in 0 until 10) {
            val product = (value[position] - '0') * (if (position % 2 == 0) 2 else 1)
            sum += if (product > 9) product - 9 else product
        }
        return sum % 10 == 0
    }

    private fun forEachIndex(kind: String, check: (index: Int, message: String) -> Unit) {
        for (index in indices) check(index, "$kind index $index")
    }

    @Test
    fun cedula_isTenAsciiDigitsWithAllowedProvinceAndThirdDigit() {
        forEachIndex("cedula") { index, message ->
            val value = SyntheticIdNumbers.cedula(index)
            assertEquals(message, 10, value.length)
            assertTrue(message, isAsciiDigits(value))
            assertTrue("$message province", value.substring(0, 2) in provinces)
            assertTrue("$message third digit", value[2] in '0'..'5')
        }
    }

    @Test
    fun cedula_checkDigitSatisfiesTheSumToZeroRule() {
        forEachIndex("cedula") { index, message ->
            assertTrue(message, cedulaSumIsDivisibleByTen(SyntheticIdNumbers.cedula(index)))
        }
    }

    @Test
    fun naturalRuc_isACedulaFollowedByANonZeroSuffix() {
        forEachIndex("naturalRuc") { index, message ->
            val value = SyntheticIdNumbers.naturalRuc(index)
            assertEquals(message, 13, value.length)
            assertTrue(message, isAsciiDigits(value))
            assertEquals("$message body", SyntheticIdNumbers.cedula(index), value.substring(0, 10))
            assertNotEquals("$message suffix", "000", value.substring(10))
        }
    }

    @Test
    fun privateRuc_hasThirdDigitNineAndPassesModulus11OverTenDigits() {
        val weights = intArrayOf(4, 3, 2, 7, 6, 5, 4, 3, 2, 1)
        forEachIndex("privateRuc") { index, message ->
            val value = SyntheticIdNumbers.privateRuc(index)
            assertEquals(message, 13, value.length)
            assertTrue(message, isAsciiDigits(value))
            assertTrue("$message province", value.substring(0, 2) in provinces)
            assertEquals("$message third digit", '9', value[2])
            assertEquals("$message modulus", 0, weightedSum(value, weights) % 11)
            assertNotEquals("$message suffix", "000", value.substring(10))
        }
    }

    @Test
    fun publicRuc_hasThirdDigitSixAndPassesModulus11OverNineDigits() {
        val weights = intArrayOf(3, 2, 7, 6, 5, 4, 3, 2, 1)
        forEachIndex("publicRuc") { index, message ->
            val value = SyntheticIdNumbers.publicRuc(index)
            assertEquals(message, 13, value.length)
            assertTrue(message, isAsciiDigits(value))
            assertTrue("$message province", value.substring(0, 2) in provinces)
            assertEquals("$message third digit", '6', value[2])
            assertEquals("$message modulus", 0, weightedSum(value, weights) % 11)
            assertNotEquals("$message suffix", "0000", value.substring(9))
        }
    }

    @Test
    fun generators_areDeterministic() {
        forEachIndex("every kind") { index, message ->
            assertEquals(message, SyntheticIdNumbers.cedula(index), SyntheticIdNumbers.cedula(index))
            assertEquals(message, SyntheticIdNumbers.naturalRuc(index), SyntheticIdNumbers.naturalRuc(index))
            assertEquals(message, SyntheticIdNumbers.privateRuc(index), SyntheticIdNumbers.privateRuc(index))
            assertEquals(message, SyntheticIdNumbers.publicRuc(index), SyntheticIdNumbers.publicRuc(index))
        }
    }

    @Test
    fun generators_giveDistinctValuesAcrossTheFirstThousandIndices() {
        val kinds: Map<String, (Int) -> String> = mapOf(
            "cedula" to SyntheticIdNumbers::cedula,
            "naturalRuc" to SyntheticIdNumbers::naturalRuc,
            "privateRuc" to SyntheticIdNumbers::privateRuc,
            "publicRuc" to SyntheticIdNumbers::publicRuc,
        )
        for ((kind, generator) in kinds) {
            val distinct = indices.map(generator).toSet().size
            assertEquals("$kind distinct count", indices.count(), distinct)
        }
    }

    @Test
    fun withWrongCheckDigit_differsFromTheInputAtExactlyOneIndex() {
        val sources = mapOf(
            "cedula" to (SyntheticIdNumbers::cedula to 9),
            "privateRuc" to (SyntheticIdNumbers::privateRuc to 9),
            "publicRuc" to (SyntheticIdNumbers::publicRuc to 8),
        )
        for ((kind, source) in sources) {
            val (generator, checkIndex) = source
            for (index in 0..99) {
                val message = "$kind index $index"
                val value = generator(index)
                val wrong = SyntheticIdNumbers.withWrongCheckDigit(value, checkIndex)
                assertEquals(message, value.length, wrong.length)
                val differing = value.indices.filter { value[it] != wrong[it] }
                assertEquals(message, listOf(checkIndex), differing)
                assertTrue(message, isAsciiDigits(wrong))
            }
        }
    }

    @Test
    fun consumidorFinal_isThirteenNines() {
        val value = SyntheticIdNumbers.consumidorFinal()
        assertEquals(13, value.length)
        assertTrue(value.all { it == '9' })
    }
}
