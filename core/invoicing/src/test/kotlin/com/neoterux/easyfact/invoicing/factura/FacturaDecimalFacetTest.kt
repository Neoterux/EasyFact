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

package com.neoterux.easyfact.invoicing.factura

import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.verify.XsdGate
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The writer enforces the vendored schema's decimal facets on the value as written, which is the
 * value after [Money]'s half-up rounding.
 *
 * Every case starts from a valid synthetic fixture and changes one amount, so a failure can only be
 * about that amount.
 */
class FacturaDecimalFacetTest {

    private fun withGrandTotal(value: String): Factura {
        val base = SampleFactura.factura()
        return base.copy(invoiceInfo = base.invoiceInfo.copy(grandTotal = BigDecimal(value)))
    }

    private fun outcome(factura: Factura): Outcome<*, FacturaError> = FacturaWriter.write(factura)

    private fun error(factura: Factura): FacturaError = (outcome(factura) as Outcome.Err).error

    private fun xml(factura: Factura): String = String(SampleFactura.write(factura).octets(), Charsets.UTF_8)

    @Test
    fun aGrandTotalOfZeroIsWrittenAsZeroPointZeroZeroAndValidates() {
        val factura = withGrandTotal("0")
        assertTrue(xml(factura).contains("<importeTotal>0.00</importeTotal>"))
        XsdGate.validate(SampleFactura.write(factura).octets())
    }

    @Test
    fun aNegativeGrandTotalIsRefusedOnMinInclusive() {
        assertEquals(
            FacturaError.DecimalOutOfRange("importeTotal", "minInclusive", "0"),
            error(withGrandTotal("-0.01")),
        )
    }

    @Test
    fun aFourteenDigitGrandTotalIsWrittenWithTwoZeroDecimalsAndValidates() {
        val factura = withGrandTotal("99999999999999")
        assertTrue(xml(factura).contains("<importeTotal>99999999999999.00</importeTotal>"))
        XsdGate.validate(SampleFactura.write(factura).octets())
    }

    @Test
    fun aFifteenDigitGrandTotalIsRefusedOnTotalDigits() {
        assertEquals(
            FacturaError.DecimalOutOfRange("importeTotal", "totalDigits", "14"),
            error(withGrandTotal("100000000000000")),
        )
    }

    @Test
    fun aRefusedDecimalProducesNoOctets() {
        assertTrue(outcome(withGrandTotal("-0.01")) is Outcome.Err)
    }
}
