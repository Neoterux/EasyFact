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

    /** Each fragment must occur after the end of the one before it, so a repeated name cannot match early. */
    private fun assertInOrder(xml: String, vararg fragments: String) {
        var from = 0
        for (fragment in fragments) {
            val at = xml.indexOf(fragment, from)
            assertTrue("$fragment not found after offset $from; expected order ${fragments.toList()}", at >= 0)
            from = at + fragment.length
        }
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

    // --- unidadMedida -----------------------------------------------------------------------------

    private fun withUnit(unit: String?): Factura {
        val f = SampleFactura.fullyPopulated()
        return f.copy(lines = listOf(f.lines.single().copy(unitOfMeasure = unit)))
    }

    @Test
    fun aUnitOfMeasureIsWrittenBetweenTheDescriptionAndTheQuantityAndPassesTheGate() {
        val factura = withUnit("unidad")
        assertSchemaValid(factura)
        assertInOrder(
            rootXml(factura),
            "</descripcion>",
            "<unidadMedida>unidad</unidadMedida>",
            "<cantidad>",
        )
    }

    @Test
    fun withoutAUnitOfMeasureNoUnidadMedidaAppears() {
        assertNoElement(rootXml(withUnit(null)), "unidadMedida")
    }

    @Test
    fun aFiftyOneCharacterUnitOfMeasureIsRefusedWithTheSchemaLimit() {
        assertEquals(FacturaError.FieldTooLong("unidadMedida", 50, 51), error(withUnit("x".repeat(51))))
    }

    // --- totalImpuesto / tarifa -------------------------------------------------------------------

    private fun withTaxRate(rate: BigDecimal?): Factura {
        val f = SampleFactura.fullyPopulated()
        return f.copy(
            invoiceInfo = f.invoiceInfo.copy(taxTotals = listOf(f.invoiceInfo.taxTotals.single().copy(rate = rate))),
        )
    }

    /** The text of the first `totalImpuesto` element of [xml]. */
    private fun taxTotalOf(xml: String): String =
        xml.substringAfter("<totalImpuesto>").substringBefore("</totalImpuesto>")

    @Test
    fun aTaxTotalRateIsWrittenBetweenTheBaseAndTheValueAndPassesTheGate() {
        val factura = withTaxRate(BigDecimal("12"))
        assertSchemaValid(factura)
        assertInOrder(
            taxTotalOf(rootXml(factura)),
            "<baseImponible>",
            "<tarifa>12.00</tarifa>",
            "<valor>",
        )
    }

    @Test
    fun aTaxTotalRateIsRoundedHalfUpToTwoDecimals() {
        assertTrue(taxTotalOf(rootXml(withTaxRate(BigDecimal("1.005")))).contains("<tarifa>1.01</tarifa>"))
    }

    @Test
    fun withoutATaxTotalRateNoTarifaAppearsInTheTotal() {
        assertNoElement(taxTotalOf(rootXml(withTaxRate(null))), "tarifa")
    }

    // --- reembolsoDetalle / compensacionesReembolso -----------------------------------------------

    private fun withRefundCompensations(compensations: List<Compensation>): Factura {
        val f = SampleFactura.fullyPopulated()
        return f.copy(refunds = listOf(f.refunds.single().copy(compensations = compensations)))
    }

    /** The text of the first `reembolsoDetalle` element of [xml]. */
    private fun refundOf(xml: String): String =
        xml.substringAfter("<reembolsoDetalle>").substringBefore("</reembolsoDetalle>")

    @Test
    fun refundCompensationsAreTheLastChildOfTheRefundAndPassTheGate() {
        val factura = withRefundCompensations(listOf(Compensation("1", BigDecimal("0"), BigDecimal("0"))))
        assertSchemaValid(factura)
        val refund = refundOf(rootXml(factura))
        assertInOrder(
            refund,
            "</detalleImpuestos>",
            "<compensacionesReembolso>",
            "<compensacionReembolso>",
            "<codigo>1</codigo>",
            "<tarifa>0.00</tarifa>",
            "<valor>0.00</valor>",
            "</compensacionReembolso>",
            "</compensacionesReembolso>",
        )
        assertTrue(refund.endsWith("</compensacionesReembolso>"))
    }

    @Test
    fun withoutRefundCompensationsNoCompensacionesReembolsoAppears() {
        assertNoElement(rootXml(withRefundCompensations(emptyList())), "compensacionesReembolso")
    }

    @Test
    fun theDocumentLevelCompensationsBlockStillUsesItsOwnElementNames() {
        val xml = rootXml(SampleFactura.fullyPopulated())
        assertTrue(xml.contains("<compensaciones><compensacion>"))
        assertFalse(xml.contains("compensacionReembolso"))
    }
}
