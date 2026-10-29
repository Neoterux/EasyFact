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
import java.math.RoundingMode

/** Why a buyer-dependent rule refuses an invoice. The variants name the rule and never carry an amount. */
sealed interface BuyerGuardError {

    /** A consumidor-final invoice has an `importeTotal` above [ConsumidorFinalGuard.CONSUMIDOR_FINAL_CAP]. */
    data object ConsumidorFinalCapExceeded : BuyerGuardError
}

/**
 * The consumidor-final cap (D-31): a pure check that an invoice to an anonymous buyer does not exceed
 * [CONSUMIDOR_FINAL_CAP].
 *
 * The owner decided on 2026-10-28 that the basis is `importeTotal`, the written total the SRI
 * recomputes, and nothing else is modelled: there is no subtotal variant. The value is read at the scale
 * it would be written (two decimals, half up) before it is compared, so 50.004 passes and 50.005 does not.
 *
 * Source: Ficha v2.34 section 9.10 (p.29) says, paraphrased, that when the value of an invoice is above
 * 50 USD the buyer's data must be given in full, so an invoice to a consumidor final above that value is
 * not accepted. The cap is a named constant rather than an inline literal because the changelog
 * (version 2.23, p.6) records that the maximum has changed before. The Ficha states that rule in its
 * free-invoicer section and does not name the basis; the owner's decision settles it.
 *
 * Phase 5's issue screen and Phase 6's preflight call this same function, and the invoice mapper calls
 * it for buyer type 07.
 */
object ConsumidorFinalGuard {

    private const val AMOUNT_SCALE = 2

    /** The largest `importeTotal` a consumidor-final invoice may carry, inclusive. */
    val CONSUMIDOR_FINAL_CAP: BigDecimal = BigDecimal("50.00")

    /** [Outcome.Ok] while [importeTotal] is at most the cap, [BuyerGuardError.ConsumidorFinalCapExceeded] above it. */
    fun check(importeTotal: BigDecimal): Outcome<Unit, BuyerGuardError> {
        val written = importeTotal.setScale(AMOUNT_SCALE, RoundingMode.HALF_UP)
        return if (written.compareTo(CONSUMIDOR_FINAL_CAP) > 0) {
            Outcome.Err(BuyerGuardError.ConsumidorFinalCapExceeded)
        } else {
            Outcome.Ok(Unit)
        }
    }
}
