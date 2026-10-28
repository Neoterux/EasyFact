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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cases for the elements of the vendored schema that the original fixtures do not carry.
 *
 * Each case starts from a synthetic [SampleFactura] fixture and adds one element, then asserts that
 * the document is written, passes [XsdGate], carries the element in its `xsd:sequence` position and
 * omits it when the field is absent.
 */
class FacturaSchemaCoverageTest {

    private fun rootXml(factura: Factura): String =
        String(SampleFactura.write(factura).root, Charsets.UTF_8)

    private fun error(factura: Factura): FacturaError =
        (FacturaWriter.write(factura) as Outcome.Err).error

    private fun assertSchemaValid(factura: Factura) {
        XsdGate.validate(SampleFactura.write(factura).octets())
    }

    private fun assertInOrder(xml: String, vararg fragments: String) {
        val positions = fragments.map { xml.indexOf(it) }
        assertTrue("every fragment must be present: ${fragments.toList()}", positions.all { it >= 0 })
        assertEquals("fragments out of order: ${fragments.toList()}", positions.sorted(), positions)
    }

    private fun assertNoElement(xml: String, name: String) {
        assertFalse("<$name> must be absent entirely", xml.contains("<$name>") || xml.contains("<$name "))
    }

    // --- maquinaFiscal ----------------------------------------------------------------------------

    private val machine = FiscalMachine(brand = "Test Marca", model = "Test Modelo", serial = "TEST-SERIE-0001")

    @Test
    fun aFiscalMachineIsWrittenInItsSchemaPositionAndPassesTheGate() {
        val factura = SampleFactura.fullyPopulated().copy(fiscalMachine = machine)
        assertSchemaValid(factura)
        assertInOrder(
            rootXml(factura),
            "</tipoNegociable>",
            "<maquinaFiscal>",
            "<marca>Test Marca</marca>",
            "<modelo>Test Modelo</modelo>",
            "<serie>TEST-SERIE-0001</serie>",
            "</maquinaFiscal>",
            "<infoAdicional>",
        )
    }

    @Test
    fun withoutAFiscalMachineNoMaquinaFiscalAppears() {
        val xml = rootXml(SampleFactura.fullyPopulated().copy(fiscalMachine = null))
        assertNoElement(xml, "maquinaFiscal")
    }

    @Test
    fun aThirtyOneCharacterBrandIsRefusedWithTheSchemaLimit() {
        val factura = SampleFactura.fullyPopulated().copy(fiscalMachine = machine.copy(brand = "x".repeat(31)))
        assertEquals(FacturaError.FieldTooLong("marca", 30, 31), error(factura))
    }
}
