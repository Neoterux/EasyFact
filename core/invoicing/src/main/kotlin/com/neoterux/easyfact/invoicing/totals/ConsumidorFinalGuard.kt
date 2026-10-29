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

import com.neoterux.easyfact.invoicing.result.Outcome
import java.math.BigDecimal

/** Why a buyer-dependent rule refuses an invoice. */
sealed interface BuyerGuardError {

    /** A consumidor-final invoice exceeds the cap. */
    data object ConsumidorFinalCapExceeded : BuyerGuardError
}

/** Compile-time scaffold for the RED step: accepts everything. Replaced by the real check. */
object ConsumidorFinalGuard {

    val CONSUMIDOR_FINAL_CAP: BigDecimal = BigDecimal("50.00")

    @Suppress("UNUSED_PARAMETER")
    fun check(importeTotal: BigDecimal): Outcome<Unit, BuyerGuardError> = Outcome.Ok(Unit)
}
