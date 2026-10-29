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

import com.neoterux.easyfact.invoicing.factura.Money
import com.neoterux.easyfact.invoicing.result.Outcome
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The consumidor-final cap compares the written `importeTotal` with a named constant (D-31). The cases
 * sit on both sides of the 50.00 boundary and on both sides of the half-cent that decides how an
 * unrounded value is read.
 */
class ConsumidorFinalGuardTest {

    private fun check(total: String): Outcome<Unit, BuyerGuardError> =
        ConsumidorFinalGuard.check(BigDecimal(total))

    @Test
    fun aTotalBelowTheCapPasses() {
        assertEquals(Outcome.Ok(Unit), check("49.99"))
    }

    @Test
    fun aTotalExactlyOnTheCapPasses() {
        assertEquals(Outcome.Ok(Unit), check("50.00"))
    }

    @Test
    fun aTotalOneCentOverTheCapIsRefused() {
        assertEquals(Outcome.Err(BuyerGuardError.ConsumidorFinalCapExceeded), check("50.01"))
    }

    @Test
    fun aTotalWrittenWithoutDecimalsIsReadAtScaleTwo() {
        assertEquals(Outcome.Ok(Unit), check("50"))
        assertEquals(Outcome.Err(BuyerGuardError.ConsumidorFinalCapExceeded), check("51"))
    }

    @Test
    fun anUnroundedTotalIsJudgedOnTheValueThatWouldBeWritten() {
        // 50.004 is written as 50.00 and passes; 50.005 is written as 50.01 (half up) and is refused.
        assertEquals(Outcome.Ok(Unit), check("50.004"))
        assertEquals(Outcome.Err(BuyerGuardError.ConsumidorFinalCapExceeded), check("50.005"))
    }

    @Test
    fun zeroAndSmallTotalsPass() {
        assertEquals(Outcome.Ok(Unit), check("0.00"))
        assertEquals(Outcome.Ok(Unit), check("0.01"))
    }

    @Test
    fun theCapIsANamedConstantRenderedAtTwoDecimals() {
        assertEquals("50.00", Money.amount(ConsumidorFinalGuard.CONSUMIDOR_FINAL_CAP))
        assertEquals("50.00", ConsumidorFinalGuard.CONSUMIDOR_FINAL_CAP.toPlainString())
    }

    @Test
    fun theErrorCarriesNoAmount() {
        // The refusal is a data object: it names the rule and never the customer's total.
        assertEquals("ConsumidorFinalCapExceeded", BuyerGuardError.ConsumidorFinalCapExceeded.toString())
    }
}
