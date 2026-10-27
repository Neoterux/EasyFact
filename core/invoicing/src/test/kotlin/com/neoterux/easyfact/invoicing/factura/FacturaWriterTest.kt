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

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FacturaWriterTest {

    private fun rootXml(factura: Factura = SampleFactura.factura()): String =
        String(FacturaWriter.write(factura).root, Charsets.UTF_8)

    @Test
    fun prologIsTheDeclarationFollowedByOneLineFeed() {
        val document = SampleFactura.document()
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n", String(document.prolog, Charsets.UTF_8))
    }

    @Test
    fun rootStartsAndEndsWithTheFacturaElement() {
        val xml = rootXml()
        assertTrue(xml.startsWith("<factura"))
        assertTrue(xml.endsWith("</factura>"))
    }

    @Test
    fun rootCarriesLowercaseIdAndVersionInC14nOrder() {
        assertTrue(rootXml().startsWith("<factura id=\"comprobante\" version=\"1.1.0\">"))
    }

    @Test
    fun outputIsCompactWithNoInterElementWhitespace() {
        val xml = rootXml()
        assertFalse(xml.contains(">\n"))
        assertFalse(xml.contains("> <"))
        assertFalse(xml.contains("\r"))
        assertFalse(xml.contains("/>"))
    }

    @Test
    fun sectionsAreInSchemaOrder() {
        val xml = rootXml()
        val order = listOf("<infoTributaria>", "<infoFactura>", "<detalles>", "<infoAdicional>").map { xml.indexOf(it) }
        assertTrue(order.all { it >= 0 })
        assertEquals(order.sorted(), order)
    }

    @Test
    fun infoTributariaChildrenAreInSchemaOrder() {
        val xml = rootXml()
        val names = listOf(
            "ambiente", "tipoEmision", "razonSocial", "nombreComercial", "ruc", "claveAcceso",
            "codDoc", "estab", "ptoEmi", "secuencial", "dirMatriz",
        )
        val positions = names.map { xml.indexOf("<$it>") }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
    }

    @Test
    fun tradeNameIsOmittedWhenAbsent() {
        val base = SampleFactura.factura()
        assertFalse(rootXml(base.copy(taxInfo = base.taxInfo.copy(tradeName = null))).contains("nombreComercial"))
    }

    @Test
    fun infoAdicionalIsOmittedWhenThereAreNoFields() {
        assertFalse(rootXml(SampleFactura.factura().copy(additionalFields = emptyList())).contains("infoAdicional"))
    }

    @Test
    fun additionalFieldNameIsAnAttributeAndItsValueIsText() {
        assertTrue(rootXml().contains("<campoAdicional nombre=\"Email\">billing@example.com</campoAdicional>"))
    }

    @Test
    fun moneyIsWrittenWithTwoDecimalsHalfUpAndNeverInScientificNotation() {
        val base = SampleFactura.factura()
        val info = base.invoiceInfo.copy(
            totalWithoutTaxes = BigDecimal("0.125"), // rounds up to 0.13
            totalDiscount = BigDecimal("0.124"), // rounds down to 0.12
            tip = BigDecimal("1E+2"), // a plain 100.00, not 1E+2
            grandTotal = BigDecimal("2.675"), // rounds up to 2.68
        )
        val xml = rootXml(base.copy(invoiceInfo = info))
        assertTrue(xml.contains("<totalSinImpuestos>0.13</totalSinImpuestos>"))
        assertTrue(xml.contains("<totalDescuento>0.12</totalDescuento>"))
        assertTrue(xml.contains("<propina>100.00</propina>"))
        assertTrue(xml.contains("<importeTotal>2.68</importeTotal>"))
    }

    @Test
    fun quantityAndUnitPriceAreWrittenWithSixDecimals() {
        val xml = rootXml()
        assertTrue(xml.contains("<cantidad>1.000000</cantidad>"))
        assertTrue(xml.contains("<precioUnitario>100.000000</precioUnitario>"))
    }

    @Test
    fun issueDateIsDayMonthYear() {
        assertTrue(rootXml().contains("<fechaEmision>27/10/2026</fechaEmision>"))
    }

    @Test
    fun freeTextIsEscapedAndNonAsciiIsKept() {
        val xml = rootXml()
        assertTrue(xml.contains("<razonSocial>Test Emisor S.A. &amp; Cia</razonSocial>"))
        assertTrue(xml.contains("<dirMatriz>Calle Falsa 123 &lt;N&gt;</dirMatriz>"))
        assertTrue(xml.contains("Cliente de prueba Ñandú"))
        assertTrue(xml.contains("Servicio de prueba \"ABC\" &gt; 1"))
    }

    @Test
    fun paymentsFollowTheCurrencyInInfoFactura() {
        val xml = rootXml()
        assertTrue(xml.indexOf("<moneda>") < xml.indexOf("<pagos>"))
        assertTrue(xml.contains("<pagos><pago><formaPago>01</formaPago><total>100.00</total></pago></pagos>"))
    }
}
