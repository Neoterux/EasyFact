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

package com.neoterux.easyfact.invoicing.signing

import java.security.SecureRandom

/**
 * The six-digit number from which a signature's element ids are derived.
 *
 * Ids are a concept, not a random number: production wants them unpredictable-looking, while a
 * golden-byte test across the JVM and ART needs them fixed, and a seeded [SecureRandom] is not
 * reproducible across providers.
 */
fun interface IdSource {
    fun next(): Int
}

/** The production source: a number in `100000..999999`, as in the SRI sample signatures. */
class RandomIdSource(private val random: SecureRandom = SecureRandom()) : IdSource {
    override fun next(): Int = random.nextInt(900000) + 100000
}

/** The deterministic source: [first], then each following integer. */
class FixedIdSource(first: Int) : IdSource {
    private var current: Int = first

    @Synchronized
    override fun next(): Int = current++
}
