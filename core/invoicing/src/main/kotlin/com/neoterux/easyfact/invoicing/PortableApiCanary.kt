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

package com.neoterux.easyfact.invoicing

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64
import java.util.Locale

/**
 * Portable-API-only code whose sole purpose is to give the Phase 1 portability gates real
 * bytecode to scan and a real clean fixture to pass.
 *
 * It touches only APIs present in both JDK 17 and Android API 31: [java.util.Base64] (never the
 * Android platform's own Base64 class, whose default flag inserts line breaks),
 * [java.math.BigDecimal] with [RoundingMode.HALF_UP], and [java.time.LocalDate]. It is not a stand-in for engine work
 * and claims nothing beyond that; Phase 3 replaces it with the actual invoicing engine.
 */
object PortableApiCanary {

    /** Encodes [bytes] as standard Base64 with no line breaks. */
    fun encodeBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** Renders [amount] with two decimals using half-up rounding, independent of the locale. */
    fun renderAmount(amount: BigDecimal): String =
        String.format(Locale.ROOT, "%s", amount.setScale(2, RoundingMode.HALF_UP).toPlainString())

    /** Returns today's date in ISO-8601 form; exercises `java.time` on the portable subset. */
    fun isoDate(date: java.time.LocalDate): String = date.toString()
}
