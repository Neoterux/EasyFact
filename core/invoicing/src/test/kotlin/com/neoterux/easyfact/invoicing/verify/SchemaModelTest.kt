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

package com.neoterux.easyfact.invoicing.verify

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Facts read from the vendored `factura_V1.1.0.xsd` through [SchemaModel], each addressed by its
 * absolute path. The expected values were read off the schema file, not off the writer.
 */
class SchemaModelTest {

    private val model = SchemaModel.factura()

    private fun node(path: String): SchemaNode =
        checkNotNull(model[path]) { "no node at $path; known paths: ${model.keys.sorted()}" }

    private val infoFactura = "factura/infoFactura"
    private val totalImpuesto = "$infoFactura/totalConImpuestos/totalImpuesto"
    private val detalle = "factura/detalles/detalle"

    @Test
    fun theRootIsAComplexElementThatOccursOnce() {
        val root = node("factura")
        assertEquals(SchemaKind.COMPLEX, root.kind)
        assertEquals(1, root.minOccurs)
        assertEquals(1, root.maxOccurs)
    }

    @Test
    fun campoAdicionalIsSimpleContentWithTheBaseTypeFacetsAndAnAttribute() {
        val field = node("factura/infoAdicional/campoAdicional")
        assertEquals(SchemaKind.STRING, field.kind)
        assertEquals(300, field.maxLength)
        assertEquals(1, field.minLength)
        assertEquals(15, field.maxOccurs)

        val name = node("factura/infoAdicional/campoAdicional/@nombre")
        assertEquals(SchemaKind.STRING, name.kind)
        assertEquals(1, name.minOccurs)
        assertEquals(300, name.maxLength)
        assertEquals(1, name.minLength)
    }

    @Test
    fun detAdicionalOccursAtMostThreeTimesAndHasInlineTypedAttributes() {
        val extra = node("$detalle/detallesAdicionales/detAdicional")
        assertEquals(3, extra.maxOccurs)
        assertEquals(SchemaKind.COMPLEX, extra.kind)
        for (attribute in listOf("nombre", "valor")) {
            val inline = node("$detalle/detallesAdicionales/detAdicional/@$attribute")
            assertEquals(SchemaKind.STRING, inline.kind)
            assertEquals(1, inline.minOccurs)
            assertEquals(300, inline.maxLength)
            assertEquals(1, inline.minLength)
        }
    }

    @Test
    fun stringLengthFactsMatchTheSchema() {
        assertEquals(100, node("factura/tipoNegociable/correo").maxLength)
        assertEquals(8, node("factura/infoTributaria/agenteRetencion").maxLength)
        assertEquals(3, node("$infoFactura/contribuyenteEspecial").minLength)
    }

    @Test
    fun totalSinImpuestosIsADecimalBoundedBelowByZero() {
        val total = node("$infoFactura/totalSinImpuestos")
        assertEquals(SchemaKind.DECIMAL, total.kind)
        assertEquals(14, total.totalDigits)
        assertEquals(2, total.fractionDigits)
        assertEquals(BigDecimal("0"), total.minInclusive)
    }

    @Test
    fun totalDescuentoAndDescuentoAdicionalAreDecimalsWithNoLowerBound() {
        for (path in listOf("$infoFactura/totalDescuento", "$totalImpuesto/descuentoAdicional")) {
            val discount = node(path)
            assertEquals(path, SchemaKind.DECIMAL, discount.kind)
            assertEquals(path, 14, discount.totalDigits)
            assertEquals(path, 2, discount.fractionDigits)
            assertNull(path, discount.minInclusive)
        }
    }

    @Test
    fun cantidadAllowsEighteenDigitsOfWhichSixAreFractional() {
        val quantity = node("$detalle/cantidad")
        assertEquals(SchemaKind.DECIMAL, quantity.kind)
        assertEquals(18, quantity.totalDigits)
        assertEquals(6, quantity.fractionDigits)
    }

    @Test
    fun theTwoTarifaTypesDifferInTotalDigits() {
        assertEquals(4, node("$detalle/impuestos/impuesto/tarifa").totalDigits)
        assertEquals(5, node("factura/retenciones/retencion/tarifa").totalDigits)
    }

    @Test
    fun formaPagoKeepsItsThreePatternsInSchemaOrder() {
        assertEquals(
            listOf("[0][1-9]", "[1][0-9]", "[2][0-1]"),
            node("$infoFactura/pagos/pago/formaPago").patterns,
        )
    }

    @Test
    fun razonSocialPatternIsTheSixCharactersBackslashNNotALineFeed() {
        val pattern = node("factura/infoTributaria/razonSocial").patterns.single()
        assertEquals(6, pattern.length)
        assertEquals(listOf('[', '^', '\\', 'n', ']', '*'), pattern.toList())
    }

    @Test
    fun theRootAttributesAreAnEnumerationAndAnUntypedAttribute() {
        val id = node("factura/@id")
        assertEquals(SchemaKind.STRING, id.kind)
        assertEquals(listOf("comprobante"), id.enumerations)
        assertEquals(0, id.minOccurs)

        val version = node("factura/@version")
        assertEquals(SchemaKind.UNTYPED, version.kind)
        assertEquals(0, version.minOccurs)
    }

    @Test
    fun totalImpuestoRepeatsWithoutBound() {
        val tax = node(totalImpuesto)
        assertNull(tax.maxOccurs)
        assertEquals(1, tax.minOccurs)
    }

    @Test
    fun theFourElementsTheWriterStillOmitsAreModelled() {
        val brand = node("factura/maquinaFiscal/marca")
        assertEquals(30, brand.maxLength)
        assertEquals(1, brand.minLength)

        val unit = node("$detalle/unidadMedida")
        assertEquals(50, unit.maxLength)
        assertEquals(0, unit.minOccurs)

        val rate = node("$totalImpuesto/tarifa")
        assertEquals(SchemaKind.DECIMAL, rate.kind)
        assertEquals(4, rate.totalDigits)
        assertEquals(0, rate.minOccurs)

        val compensation =
            node("factura/reembolsos/reembolsoDetalle/compensacionesReembolso/compensacionReembolso")
        assertNull(compensation.maxOccurs)
    }

    @Test
    fun noReachableNodeCarriesAnUpperOrExclusiveBound() {
        // The only bounded integer type (codTipoDocModificado) is not referenced from <factura>. If a
        // later revision starts to, this test names the node, and the parity work compares it.
        val bounded = model.values.filter {
            it.maxInclusive != null || it.minExclusive != null || it.maxExclusive != null
        }
        assertTrue(bounded.joinToString { it.path }, bounded.isEmpty())
    }

    @Test
    fun everyPathIsRootedAtFacturaAndTheModelIsNotEmpty() {
        assertTrue(model.size > 100)
        assertNotNull(model["factura/infoTributaria/ruc"])
        assertTrue(model.keys.all { it == "factura" || it.startsWith("factura/") })
    }
}
