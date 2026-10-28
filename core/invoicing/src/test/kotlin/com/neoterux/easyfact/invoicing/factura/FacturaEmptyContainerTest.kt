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
import com.neoterux.easyfact.invoicing.verify.SchemaKind
import com.neoterux.easyfact.invoicing.verify.SchemaModel
import com.neoterux.easyfact.invoicing.verify.SchemaNode
import com.neoterux.easyfact.invoicing.verify.XsdGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A list container is never written empty. Each case starts from a valid synthetic fixture that
 * carries every element of the schema and empties exactly one list. A container the schema
 * requires, or that the writer always emits, is refused with [FacturaError.EmptyCollection] and
 * yields no octets; an optional container is omitted and the document still validates.
 *
 * The table of cases is checked against the vendored schema, so a list container added later
 * cannot be written empty unnoticed.
 */
class FacturaEmptyContainerTest {

    /** What emptying one list must do. */
    private sealed interface Expected {
        /** The writer refuses the factura with [FacturaError.EmptyCollection] naming [element]. */
        data class Refused(val element: String) : Expected

        /** The writer omits the container [element] entirely and the document stays schema-valid. */
        data class Omitted(val element: String) : Expected
    }

    private class EmptiedList(val expected: Expected, val emptied: (Factura) -> Factura)

    /** Schema path of each list container, to the case that empties its list. */
    private val cases: Map<String, EmptiedList> = mapOf(
        "factura/detalles" to EmptiedList(Expected.Refused("detalles")) {
            it.copy(lines = emptyList())
        },
        "factura/infoFactura/totalConImpuestos" to EmptiedList(Expected.Refused("totalConImpuestos")) {
            it.copy(invoiceInfo = it.invoiceInfo.copy(taxTotals = emptyList()))
        },
        "factura/infoFactura/pagos" to EmptiedList(Expected.Refused("pagos")) {
            it.copy(invoiceInfo = it.invoiceInfo.copy(payments = emptyList()))
        },
        "factura/detalles/detalle/impuestos" to EmptiedList(Expected.Refused("impuestos")) {
            it.copy(lines = listOf(it.lines.first().copy(taxes = emptyList())))
        },
        "factura/reembolsos/reembolsoDetalle/detalleImpuestos" to EmptiedList(Expected.Refused("detalleImpuestos")) {
            it.copy(refunds = listOf(it.refunds.single().copy(taxes = emptyList())))
        },
        "factura/infoFactura/compensaciones" to EmptiedList(Expected.Omitted("compensaciones")) {
            it.copy(invoiceInfo = it.invoiceInfo.copy(compensations = emptyList()))
        },
        "factura/detalles/detalle/detallesAdicionales" to EmptiedList(Expected.Omitted("detallesAdicionales")) {
            it.copy(lines = listOf(it.lines.first().copy(additionalDetails = emptyList())))
        },
        "factura/reembolsos" to EmptiedList(Expected.Omitted("reembolsos")) {
            it.copy(refunds = emptyList())
        },
        "factura/retenciones" to EmptiedList(Expected.Omitted("retenciones")) {
            it.copy(withholdings = emptyList())
        },
        "factura/infoAdicional" to EmptiedList(Expected.Omitted("infoAdicional")) {
            it.copy(additionalFields = emptyList())
        },
        "factura/reembolsos/reembolsoDetalle/compensacionesReembolso" to EmptiedList(Expected.Omitted("compensacionesReembolso")) {
            it.copy(refunds = listOf(it.refunds.single().copy(compensations = emptyList())))
        },
    )

    private fun xml(factura: Factura): String = String(SampleFactura.write(factura).root, Charsets.UTF_8)

    private fun assertNoElement(xml: String, name: String) {
        assertFalse("<$name> must be absent entirely", xml.contains("<$name>") || xml.contains("<$name "))
    }

    @Test
    fun aFacturaWithNoLinesIsRefusedAndYieldsNoDocument() {
        val outcome = FacturaWriter.write(SampleFactura.factura().copy(lines = emptyList()))
        assertEquals(Outcome.Err(FacturaError.EmptyCollection("detalles")), outcome)
    }

    @Test
    fun theFixtureThePerContainerCasesStartFromIsValid() {
        XsdGate.validate(SampleFactura.write(SampleFactura.everyElement()).octets())
    }

    @Test
    fun everyMandatoryListThatIsEmptyIsRefusedWithItsContainerName() {
        for ((path, case) in cases) {
            val expected = case.expected as? Expected.Refused ?: continue
            val outcome = FacturaWriter.write(case.emptied(SampleFactura.everyElement()))
            assertEquals(path, Outcome.Err(FacturaError.EmptyCollection(expected.element)), outcome)
        }
    }

    @Test
    fun everyOptionalListThatIsEmptyIsOmittedAndTheDocumentValidates() {
        for ((path, case) in cases) {
            val expected = case.expected as? Expected.Omitted ?: continue
            val factura = case.emptied(SampleFactura.everyElement())
            val document = SampleFactura.write(factura)
            assertNoElement(String(document.root, Charsets.UTF_8), expected.element)
            XsdGate.validate(document.octets())
            assertTrue("$path is a container the writer never refuses", FacturaWriter.write(factura) is Outcome.Ok)
        }
    }

    @Test
    fun theFixtureWritesEveryContainerSoOmissionIsOnlyEverCausedByTheEmptiedList() {
        val whole = xml(SampleFactura.everyElement())
        for ((path, case) in cases) {
            val name = when (val expected = case.expected) {
                is Expected.Refused -> expected.element
                is Expected.Omitted -> expected.element
            }
            assertTrue("$path is written by the unmodified fixture", whole.contains("<$name>"))
        }
    }

    @Test
    fun noEmptyContainerElementAppearsInAnyWriterOutput() {
        val emptyElement = Regex("<([A-Za-z][A-Za-z0-9]*)>\\s*</\\1>")
        for ((path, case) in cases) {
            val outcome = FacturaWriter.write(case.emptied(SampleFactura.everyElement()))
            if (outcome is Outcome.Ok) {
                val found = emptyElement.find(String(outcome.value.root, Charsets.UTF_8))
                assertEquals("$path left an empty element", null, found?.value)
            }
        }
    }

    // ---- the cases cover every list container the schema has ---------------------------------------

    /**
     * Every complex element of [model] whose only child is a repeating element that must occur at
     * least once: a list container that cannot be written empty.
     */
    private fun mandatoryListContainers(model: Map<String, SchemaNode>): Set<String> =
        model.values
            .filter { it.kind == SchemaKind.COMPLEX }
            .filter { container ->
                val children = model.values.filter { child ->
                    child.path.startsWith(container.path + "/") &&
                        '/' !in child.path.removePrefix(container.path + "/") &&
                        !child.path.removePrefix(container.path + "/").startsWith("@")
                }
                val only = children.singleOrNull()
                only != null && only.minOccurs >= 1 && (only.maxOccurs == null || only.maxOccurs > 1)
            }
            .map { it.path }
            .toSet()

    @Test
    fun everyModelledListContainerWithAMandatoryChildHasACase() {
        assertEquals(cases.keys, mandatoryListContainers(SchemaModel.factura()))
    }

    @Test
    fun aListContainerMissingFromTheTableIsReported() {
        val xsd = """
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema">
              <xsd:element name="factura">
                <xsd:complexType>
                  <xsd:sequence>
                    <xsd:element name="extras">
                      <xsd:complexType>
                        <xsd:sequence>
                          <xsd:element name="extra" type="xsd:string" minOccurs="1" maxOccurs="unbounded"/>
                        </xsd:sequence>
                      </xsd:complexType>
                    </xsd:element>
                    <xsd:element name="optional" minOccurs="0">
                      <xsd:complexType>
                        <xsd:sequence>
                          <xsd:element name="one" type="xsd:string" minOccurs="0" maxOccurs="unbounded"/>
                        </xsd:sequence>
                      </xsd:complexType>
                    </xsd:element>
                  </xsd:sequence>
                </xsd:complexType>
              </xsd:element>
            </xsd:schema>
        """.trimIndent()
        assertEquals(setOf("factura/extras"), mandatoryListContainers(SchemaModel.parse(xsd.toByteArray())))
    }
}
