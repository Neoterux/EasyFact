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

/**
 * Runtime generator of structurally valid synthetic Ecuadorian identification numbers: cedulas
 * and the three RUC classes.
 *
 * Every value is valid by construction (right length, an accepted province, a legal third digit
 * and a correct check digit) and is generated at runtime from an index, so no identifier-shaped
 * literal is ever tracked (AGENTS.md section 2.2). A generated value may nonetheless coincide with
 * a number that has really been issued, so it must never be logged, printed or put in an
 * assertion message: name the kind and the index instead.
 *
 * Check digits are computed forward here: the digit that completes the weighted sum is derived
 * from the body. The validator that consumes these values uses the sum-to-zero formulation
 * instead, so the two are written independently of one another and a shared misreading of the
 * rules cannot make them agree.
 *
 * Bodies come from a fixed multiplicative mix of the index. Within the supported index range the
 * mix is a bijection, so distinct indices give distinct values of one kind.
 */
object SyntheticIdNumbers {

    /** Indices `0 until MAX_INDEX` are supported; this keeps the mix a bijection for every kind. */
    const val MAX_INDEX: Int = 5000

    /** Candidate bodies tried per index when a check digit would come out as ten. */
    private const val ATTEMPTS_PER_INDEX = 16
    private const val MIX_MULTIPLIER = 7919L
    private const val MIX_OFFSET = 104_729L

    private const val PRIVATE_THIRD_DIGIT = '9'
    private const val PUBLIC_THIRD_DIGIT = '6'
    private const val CEDULA_BODY_DIGITS = 6
    private const val PRIVATE_BODY_DIGITS = 6
    private const val PUBLIC_BODY_DIGITS = 5
    private const val THIRD_DIGIT_CLASSES = 6
    private const val NATURAL_SUFFIX_VALUES = 999

    private val PRIVATE_WEIGHTS = intArrayOf(4, 3, 2, 7, 6, 5, 4, 3, 2)
    private val PUBLIC_WEIGHTS = intArrayOf(3, 2, 7, 6, 5, 4, 3, 2)

    /** Provinces 01 to 24 and 30, each as two digits. */
    private val PROVINCES: List<String> = (1..24).map { it.toString().padStart(2, '0') } + "30"

    /** A ten-digit cedula: province, third digit 0 to 5, six body digits, check digit. */
    fun cedula(index: Int): String {
        requireIndex("cedula", index)
        val firstNine = province(index) + (index % THIRD_DIGIT_CLASSES) + body(index, 0, CEDULA_BODY_DIGITS)
        return firstNine + cedulaCheckDigit(firstNine)
    }

    /** A thirteen-digit natural-person RUC: [cedula] for the same index plus an establishment suffix 001 to 999. */
    fun naturalRuc(index: Int): String {
        val suffix = (index % NATURAL_SUFFIX_VALUES + 1).toString().padStart(3, '0')
        return cedula(index) + suffix
    }

    /** A thirteen-digit private-company RUC: third digit 9, modulus-11 check digit at position 10, suffix 001. */
    fun privateRuc(index: Int): String {
        requireIndex("privateRuc", index)
        for (attempt in 0 until ATTEMPTS_PER_INDEX) {
            val firstNine = province(index) + PRIVATE_THIRD_DIGIT + body(index, attempt, PRIVATE_BODY_DIGITS)
            val check = modulus11CheckDigit(firstNine, PRIVATE_WEIGHTS) ?: continue
            return firstNine + check + "001"
        }
        error("privateRuc index $index: no body with a usable check digit")
    }

    /** A thirteen-digit public-entity RUC: third digit 6, modulus-11 check digit at position 9, suffix 0001. */
    fun publicRuc(index: Int): String {
        requireIndex("publicRuc", index)
        for (attempt in 0 until ATTEMPTS_PER_INDEX) {
            val firstEight = province(index) + PUBLIC_THIRD_DIGIT + body(index, attempt, PUBLIC_BODY_DIGITS)
            val check = modulus11CheckDigit(firstEight, PUBLIC_WEIGHTS) ?: continue
            return firstEight + check + "0001"
        }
        error("publicRuc index $index: no body with a usable check digit")
    }

    /** The consumidor final placeholder: thirteen nines, built at runtime so no literal is tracked. */
    fun consumidorFinal(): String = "9".repeat(13)

    /** [value] with the digit at [checkIndex] replaced by `(digit + 1) % 10`; every other position is unchanged. */
    fun withWrongCheckDigit(value: String, checkIndex: Int): String {
        require(checkIndex in value.indices) { "checkIndex $checkIndex is outside a value of length ${value.length}" }
        val digit = value[checkIndex]
        require(digit in '0'..'9') { "position $checkIndex is not an ASCII digit" }
        val replaced = '0' + ((digit - '0') + 1) % 10
        return value.substring(0, checkIndex) + replaced + value.substring(checkIndex + 1)
    }

    private fun requireIndex(kind: String, index: Int) {
        require(index in 0 until MAX_INDEX) { "$kind index $index is outside 0 until $MAX_INDEX" }
    }

    private fun province(index: Int): String = PROVINCES[index % PROVINCES.size]

    /**
     * [digits] body digits drawn from the multiplicative mix of `index` and `attempt`. The
     * candidate number is injective in (index, attempt) while attempt stays below
     * [ATTEMPTS_PER_INDEX], and the multiplier is coprime to ten, so the mix is a bijection.
     */
    private fun body(index: Int, attempt: Int, digits: Int): String {
        var modulus = 1L
        repeat(digits) { modulus *= 10 }
        val candidate = index.toLong() * ATTEMPTS_PER_INDEX + attempt
        val mixed = (candidate * MIX_MULTIPLIER + MIX_OFFSET) % modulus
        return mixed.toString().padStart(digits, '0')
    }

    /**
     * Cedula check digit: positions 1, 3, 5, 7 and 9 are doubled, products above nine lose nine,
     * and the digit that brings the sum up to the next multiple of ten is the check digit.
     */
    private fun cedulaCheckDigit(firstNine: String): Int {
        var sum = 0
        for (position in firstNine.indices) {
            val digit = firstNine[position] - '0'
            val product = if (position % 2 == 0) digit * 2 else digit
            sum += if (product > 9) product - 9 else product
        }
        return (10 - sum % 10) % 10
    }

    /**
     * Modulus-11 check digit: `11 - (weighted sum mod 11)`, with eleven written as zero. A result
     * of ten has no single-digit form, so the body is unusable and `null` asks for another.
     */
    private fun modulus11CheckDigit(body: String, weights: IntArray): Int? {
        var sum = 0
        for (position in weights.indices) sum += (body[position] - '0') * weights[position]
        return when (val raw = 11 - sum % 11) {
            11 -> 0
            10 -> null
            else -> raw
        }
    }
}
