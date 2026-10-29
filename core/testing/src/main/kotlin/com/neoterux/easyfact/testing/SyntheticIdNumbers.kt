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

/** Red-phase stub: compiles, asserts nothing, returns values that fail every behaviour check. */
object SyntheticIdNumbers {
    fun cedula(index: Int): String = ""

    fun naturalRuc(index: Int): String = ""

    fun privateRuc(index: Int): String = ""

    fun publicRuc(index: Int): String = ""

    fun consumidorFinal(): String = ""

    fun withWrongCheckDigit(value: String, checkIndex: Int): String = value
}
