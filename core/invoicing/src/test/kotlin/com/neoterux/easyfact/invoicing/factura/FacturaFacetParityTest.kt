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

import com.neoterux.easyfact.invoicing.factura.FacturaWriter.Facet
import com.neoterux.easyfact.invoicing.factura.FacturaWriter.Kind
import com.neoterux.easyfact.invoicing.verify.SchemaKind
import com.neoterux.easyfact.invoicing.verify.SchemaModel
import com.neoterux.easyfact.invoicing.verify.SchemaNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves that the facets [FacturaWriter] applies are the ones the vendored `factura_V1.1.0.xsd`
 * states. The writer copies them by hand and never reads the schema at run time; this test is the
 * only thing that connects the two, and it runs on every build.
 *
 * The comparison is [drift], a pure function from a facet list and the schema model to a sorted list
 * of drift lines, so the same function judges the real table and the deliberately wrong copies that
 * prove it can fail.
 */
class FacturaFacetParityTest {

    /**
     * Schema paths the writer deliberately does not model, each mapped to the reason the owner
     * wrote down. An entry needs a reason the owner has written down, and there are none: every
     * element of the vendored revision is modelled.
     */
    private val NOT_MODELLED: Map<String, String> = emptyMap()

    private val schema: Map<String, SchemaNode> = SchemaModel.factura()

    private fun drift(
        facets: List<Facet>,
        model: Map<String, SchemaNode> = schema,
        notModelled: Map<String, String> = NOT_MODELLED,
    ): List<String> {
        val lines = mutableListOf<String>()
        val seen = HashSet<String>()
        for (facet in facets) {
            if (!seen.add(facet.path)) lines += "DUPLICATE ${facet.path}"
            val node = model[facet.path]
            if (node == null) {
                lines += "UNKNOWN ${facet.path}"
                continue
            }
            if (!kindsAgree(facet.kind, node.kind)) {
                lines += "DRIFT kind ${facet.path} facet=${facet.kind} schema=${node.kind}"
            }
            if (facet.kind == Kind.TEXT && facet.maxLength != node.maxLength) {
                lines += "DRIFT maxLength ${facet.path} facet=${facet.maxLength} schema=${node.maxLength}"
            }
            if (facet.kind != Kind.TEXT && facet.maxLength != null) {
                lines += "DRIFT maxLength ${facet.path} facet=${facet.maxLength} on a ${facet.kind} facet"
            }
            if (facet.guardLength != null && node.maxLength != null) {
                lines += "DRIFT guardLength ${facet.path} guard=${facet.guardLength} beside schema maxLength=${node.maxLength}"
            }
        }
        for (path in model.keys) {
            if (path !in seen && path !in notModelled) lines += "NOT MODELLED $path"
        }
        for (path in notModelled.keys) {
            if (path !in model) lines += "NOT_MODELLED UNKNOWN $path"
            if (path in seen) lines += "NOT_MODELLED ALSO A FACET $path"
        }
        return lines.sorted()
    }

    private fun kindsAgree(kind: Kind, schemaKind: SchemaKind): Boolean = when (kind) {
        Kind.CONTAINER -> schemaKind == SchemaKind.COMPLEX
        Kind.TEXT -> schemaKind == SchemaKind.STRING || schemaKind == SchemaKind.UNTYPED
        Kind.DECIMAL -> schemaKind == SchemaKind.DECIMAL
    }

    private fun facet(path: String): Facet = FacturaWriter.FACETS.single { it.path == path }

    @Test
    fun theRealTableDoesNotDriftFromTheVendoredSchema() {
        assertEquals(emptyList<String>(), drift(FacturaWriter.FACETS))
    }

    @Test
    fun theNotModelledMapStaysEmpty() {
        assertTrue("an entry needs a reason the owner has written down: $NOT_MODELLED", NOT_MODELLED.isEmpty())
    }

    @Test
    fun aCampoAdicionalMaxLengthOf32IsReportedAsDrift() {
        val path = "factura/infoAdicional/campoAdicional"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(maxLength = 32) else it }
        assertEquals(
            listOf("DRIFT maxLength $path facet=32 schema=300"),
            drift(mutated),
        )
    }

    @Test
    fun aMisspeltPathIsReportedAsUnknown() {
        val path = "factura/infoTributaria/ruc"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(path = "${it.path}c") else it }
        val report = drift(mutated)
        assertTrue(report.toString(), "UNKNOWN ${path}c" in report)
        assertTrue(report.toString(), "NOT MODELLED $path" in report)
    }

    @Test
    fun aMissingFacetIsReportedAsNotModelled() {
        val path = "factura/maquinaFiscal/marca"
        val mutated = FacturaWriter.FACETS.filterNot { it.path == path }
        assertEquals(listOf("NOT MODELLED $path"), drift(mutated))
    }

    @Test
    fun theCorrectedFacetsAreInTheTable() {
        assertEquals(300, facet("factura/infoAdicional/campoAdicional").maxLength)
        assertEquals(8, facet("factura/infoTributaria/agenteRetencion").maxLength)
        assertEquals(10, facet("factura/infoFactura/incoTermFactura").maxLength)
        assertEquals(10, facet("factura/infoFactura/incoTermTotalSinImpuestos").maxLength)
        assertEquals(100, facet("factura/tipoNegociable/correo").maxLength)
        assertEquals(1, facet("factura/infoFactura/totalConImpuestos/totalImpuesto/codigo").maxLength)
        assertEquals(1, facet("factura/detalles/detalle/impuestos/impuesto/codigo").maxLength)
    }
}
