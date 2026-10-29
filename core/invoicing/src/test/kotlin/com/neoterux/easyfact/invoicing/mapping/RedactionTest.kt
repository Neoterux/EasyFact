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

package com.neoterux.easyfact.invoicing.mapping

import com.neoterux.easyfact.invoicing.factura.AdditionalField
import com.neoterux.easyfact.invoicing.factura.LineDetailField
import com.neoterux.easyfact.invoicing.placeholder.BillingPeriod
import com.neoterux.easyfact.invoicing.placeholder.CommittedSequence
import com.neoterux.easyfact.invoicing.placeholder.PlaceholderContext
import com.neoterux.easyfact.invoicing.placeholder.PlaceholderResolver
import com.neoterux.easyfact.invoicing.result.Computed
import com.neoterux.easyfact.testing.SyntheticIdNumbers
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * NFR-04 and AGENTS.md section 7: no engine type that can hold customer text renders it through
 * `toString()`. Each case builds the value with a sentinel in every text and asserts the sentinel is
 * absent from the rendering, which is what a stray `println` or a crash report would print.
 */
class RedactionTest {

    private val sentinel = "SENTINEL-" + SyntheticIdentifiers.CUSTOMER_NAME
    private val identification = SyntheticIdNumbers.cedula(1)

    private fun assertRedacted(variant: String, rendered: String) {
        assertFalse("variant $variant: sentinel leaked", rendered.contains(sentinel))
        assertFalse("variant $variant: RUC leaked", rendered.contains(SyntheticIdentifiers.RUC))
        assertFalse("variant $variant: identification leaked", rendered.contains(identification))
    }

    @Test
    fun theResolvedTextWrapperRendersNoText() {
        val context = PlaceholderContext(
            BillingPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
            sentinel,
            identification,
        )
        val resolved: Computed<String> =
            PlaceholderResolver.resolve("{CUSTOMER_NAME} {CUSTOMER_ID}", context, CommittedSequence("001", "002", 1L))

        assertRedacted("computed resolve", resolved.toString())
    }

    @Test
    fun theResolvedLineAndTheTemplateFieldRenderNoText() {
        val line = ResolvedLine.lumpSum(sentinel, "$sentinel $identification", java.math.BigDecimal("10.00"), "4")

        assertRedacted("resolved line", line.toString())
        assertRedacted("template field", TemplateField(sentinel, "$sentinel $identification").toString())
    }

    @Test
    fun theWholeMappedModelRendersNoText() {
        val request = MapperFixtures.request(
            issuer = MapperFixtures.issuer(legalName = sentinel),
            buyer = MapperFixtures.buyer(legalName = sentinel, identification = identification, address = sentinel),
            lines = listOf(MapperFixtures.line(code = "SENTINEL-CODE", description = "$sentinel $identification")),
            templateFields = listOf(TemplateField(sentinel, sentinel)),
        )
        val factura = MapperFixtures.mappedOk("redaction", request).value.factura

        assertRedacted("factura", factura.toString())
        assertRedacted("tax info", factura.taxInfo.toString())
        assertRedacted("invoice info", factura.invoiceInfo.toString())
        assertRedacted("invoice line", factura.lines.toString())
        assertRedacted("additional fields", factura.additionalFields.toString())
        assertRedacted("line detail field", LineDetailField(sentinel, sentinel).toString())
        assertRedacted("additional field", AdditionalField(sentinel, sentinel).toString())
    }
}
