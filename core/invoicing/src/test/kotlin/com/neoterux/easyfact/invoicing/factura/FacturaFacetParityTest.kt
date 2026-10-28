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
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * The one text facet the schema leaves untyped, so that nothing bounds it: `factura/@version` is
     * written from a constant, never from user input.
     */
    private val UNBOUNDED_TEXT: Set<String> = setOf("factura/@version")

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
            if (facet.kind == Kind.TEXT && facet.minLength != node.minLength) {
                lines += "DRIFT minLength ${facet.path} facet=${facet.minLength} schema=${node.minLength}"
            }
            if (facet.kind != Kind.TEXT && facet.minLength != null) {
                lines += "DRIFT minLength ${facet.path} facet=${facet.minLength} on a ${facet.kind} facet"
            }
            val schemaOccurs = node.maxOccurs?.takeIf { it > 1 }
            if (facet.maxOccurs != schemaOccurs) {
                lines += "DRIFT maxOccurs ${facet.path} facet=${facet.maxOccurs} schema=$schemaOccurs"
            }
            if (facet.kind == Kind.DECIMAL) {
                if (facet.totalDigits == null) lines += "DRIFT totalDigits ${facet.path} facet=null schema=${node.totalDigits}"
                if (facet.fractionDigits == null) {
                    lines += "DRIFT fractionDigits ${facet.path} facet=null schema=${node.fractionDigits}"
                }
                if (facet.totalDigits != node.totalDigits && facet.totalDigits != null) {
                    lines += "DRIFT totalDigits ${facet.path} facet=${facet.totalDigits} schema=${node.totalDigits}"
                }
                if (facet.fractionDigits != node.fractionDigits && facet.fractionDigits != null) {
                    lines += "DRIFT fractionDigits ${facet.path} facet=${facet.fractionDigits} schema=${node.fractionDigits}"
                }
                for ((name, mine, theirs) in listOf(
                    Triple("minInclusive", facet.minInclusive, node.minInclusive),
                    Triple("maxInclusive", facet.maxInclusive, node.maxInclusive),
                    Triple("minExclusive", facet.minExclusive, node.minExclusive),
                    Triple("maxExclusive", facet.maxExclusive, node.maxExclusive),
                )) {
                    if (!sameBound(mine, theirs)) lines += "DRIFT $name ${facet.path} facet=$mine schema=$theirs"
                }
            } else {
                val stray = listOf(
                    "totalDigits" to facet.totalDigits,
                    "fractionDigits" to facet.fractionDigits,
                    "minInclusive" to facet.minInclusive,
                    "maxInclusive" to facet.maxInclusive,
                    "minExclusive" to facet.minExclusive,
                    "maxExclusive" to facet.maxExclusive,
                )
                for ((name, value) in stray) {
                    if (value != null) lines += "DRIFT $name ${facet.path} facet=$value on a ${facet.kind} facet"
                }
            }
            if (facet.kind == Kind.TEXT) {
                if (facet.patterns != node.patterns) {
                    lines += "DRIFT patterns ${facet.path} facet=${facet.patterns} schema=${node.patterns}"
                }
                if (facet.enumerations != node.enumerations) {
                    lines += "DRIFT enumerations ${facet.path} facet=${facet.enumerations} schema=${node.enumerations}"
                }
                if (facet.maxLength == null && facet.patterns.isEmpty() && facet.enumerations.isEmpty() &&
                    facet.path !in UNBOUNDED_TEXT
                ) {
                    lines += "UNBOUNDED ${facet.path} has no maxLength, pattern or enumeration"
                }
            } else {
                if (facet.patterns.isNotEmpty()) lines += "DRIFT patterns ${facet.path} facet=${facet.patterns} on a ${facet.kind} facet"
                if (facet.enumerations.isNotEmpty()) {
                    lines += "DRIFT enumerations ${facet.path} facet=${facet.enumerations} on a ${facet.kind} facet"
                }
                if (facet.catalogue != null) lines += "DRIFT catalogue ${facet.path} on a ${facet.kind} facet"
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

    /** Bounds compare by value, so that 0 and 0.00 agree; both absent agree too. */
    private fun sameBound(mine: BigDecimal?, theirs: BigDecimal?): Boolean =
        if (mine == null || theirs == null) mine == null && theirs == null else mine.compareTo(theirs) == 0

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
    fun aCantidadTotalDigitsOf16IsReportedAsDrift() {
        val path = "factura/detalles/detalle/cantidad"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(totalDigits = 16) else it }
        assertEquals(listOf("DRIFT totalDigits $path facet=16 schema=18"), drift(mutated))
    }

    @Test
    fun aSecuencialPatternOfEightDigitsIsReportedAsDrift() {
        val path = "factura/infoTributaria/secuencial"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(patterns = listOf("[0-9]{8}")) else it }
        assertEquals(
            listOf("DRIFT patterns $path facet=[[0-9]{8}] schema=[[0-9]{9}]"),
            drift(mutated),
        )
    }

    @Test
    fun aMissingEnumerationIsReportedAsDrift() {
        val path = "factura/infoFactura/obligadoContabilidad"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(enumerations = listOf("SI")) else it }
        assertEquals(
            listOf("DRIFT enumerations $path facet=[SI] schema=[SI, NO]"),
            drift(mutated),
        )
    }

    @Test
    fun aReorderedFormaPagoPatternListIsReportedAsDrift() {
        val path = "factura/infoFactura/pagos/pago/formaPago"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(patterns = it.patterns.reversed()) else it }
        assertEquals(1, drift(mutated).size)
    }

    @Test
    fun aTextFacetBoundedByNothingIsReportedAsUnbounded() {
        val path = "factura/infoTributaria/ruc"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(patterns = emptyList()) else it }
        val report = drift(mutated)
        assertTrue(report.toString(), "UNBOUNDED $path has no maxLength, pattern or enumeration" in report)
    }

    @Test
    fun onlyTheVersionAttributeIsUnboundedText() {
        val unbounded = FacturaWriter.FACETS
            .filter { it.kind == Kind.TEXT && it.maxLength == null && it.patterns.isEmpty() && it.enumerations.isEmpty() }
            .map { it.path }
        assertEquals(listOf("factura/@version"), unbounded)
    }

    @Test
    fun aMissingMinInclusiveIsReportedAsDrift() {
        val path = "factura/infoFactura/importeTotal"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(minInclusive = null) else it }
        assertEquals(listOf("DRIFT minInclusive $path facet=null schema=0"), drift(mutated))
    }

    @Test
    fun aMinInclusiveWrittenAsZeroPointZeroZeroAgreesWithZero() {
        val path = "factura/infoFactura/importeTotal"
        val mutated = FacturaWriter.FACETS.map {
            if (it.path == path) it.copy(minInclusive = BigDecimal("0.00")) else it
        }
        assertEquals(emptyList<String>(), drift(mutated))
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
    fun aChangedContribuyenteEspecialMinLengthIsReportedAsDrift() {
        val path = "factura/infoFactura/contribuyenteEspecial"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(minLength = 4) else it }
        assertEquals(listOf("DRIFT minLength $path facet=4 schema=3"), drift(mutated))
    }

    @Test
    fun aChangedDetAdicionalMaxOccursIsReportedAsDrift() {
        val path = "factura/detalles/detalle/detallesAdicionales/detAdicional"
        val mutated = FacturaWriter.FACETS.map { if (it.path == path) it.copy(maxOccurs = 4) else it }
        assertEquals(listOf("DRIFT maxOccurs $path facet=4 schema=3"), drift(mutated))
    }

    /**
     * Every way [pattern] could mean one thing to the schema and another to `java.util.regex`,
     * which on Android is backed by ICU. The patterns the vendored schema uses are limited to
     * character classes, quantifiers, groups, alternation, literals and the `\n` escape, whose
     * meaning is the same in all three. Anything else is reported by name, so a schema revision
     * cannot introduce a pattern the writer would read differently from the SRI.
     *
     * A hyphen followed by an opening bracket is only subtraction inside a class: outside one it is
     * a literal pair, as in `[0-9]{3}-[0-9]{3}`.
     */
    private fun dialectIssues(pattern: String): List<String> {
        val issues = mutableListOf<String>()
        var inClass = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            val next = pattern.getOrNull(i + 1)
            when {
                c == '\\' -> {
                    if (next == 'n') {
                        i++
                    } else {
                        issues += "escape \\${next ?: ""} at $i"
                        i++
                    }
                }
                inClass && c == '[' -> issues += "nested or subtracted class at $i"
                inClass && c == '&' && next == '&' -> issues += "class intersection at $i"
                inClass && c == ']' -> inClass = false
                inClass -> Unit
                c == '[' -> inClass = true
                c == '^' || c == '$' -> issues += "anchor '$c' outside a class at $i"
                c == '.' -> issues += "dot outside a class at $i"
                c == '(' && next == '?' -> issues += "extended group at $i"
                (c == '*' || c == '+' || c == '?' || c == '}') && (next == '?' || next == '+') ->
                    issues += "lazy or possessive quantifier at ${i + 1}"
            }
            i++
        }
        if (inClass) issues += "unterminated class"
        return issues
    }

    private fun schemaPatterns(): List<String> = schema.values.flatMap { it.patterns }.distinct()

    @Test
    fun theSchemaUsesOnlyPatternConstructsWithOneMeaningInEveryDialect() {
        val patterns = schemaPatterns()
        assertTrue("the schema states no pattern, so the guard would prove nothing", patterns.size > 10)
        val report = patterns.associateWith { dialectIssues(it) }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap<String, List<String>>(), report)
    }

    @Test
    fun theDialectGuardRejectsABackslashDEscape() {
        assertEquals(listOf("escape \\d at 0"), dialectIssues("\\d{3}"))
    }

    @Test
    fun theDialectGuardRejectsEveryOtherEscapeButTheLineFeed() {
        for (escape in listOf("i", "c", "d", "w", "s", "p{L}", "I", "C", "D", "W", "S", "P{L}", "\\")) {
            assertTrue(escape, dialectIssues("[a]\\$escape").isNotEmpty())
        }
        assertEquals(emptyList<String>(), dialectIssues("[^\\n]*"))
    }

    @Test
    fun theDialectGuardRejectsClassSubtractionAndNestingButNotALiteralHyphenBeforeAClass() {
        assertEquals(listOf("nested or subtracted class at 5"), dialectIssues("[a-z-[aeiou]]"))
        assertEquals(listOf("nested or subtracted class at 2"), dialectIssues("[a[b]]"))
        assertEquals(listOf("class intersection at 2"), dialectIssues("[a&&b]"))
        assertEquals(emptyList<String>(), dialectIssues("[0-9]{3}-[0-9]{3}-[0-9]{9}"))
    }

    @Test
    fun theDialectGuardRejectsAnchorsDotsExtendedGroupsAndLazyQuantifiers() {
        assertEquals(listOf("anchor '^' outside a class at 0", "anchor '$' outside a class at 4"), dialectIssues("^abc$"))
        assertEquals(listOf("dot outside a class at 1"), dialectIssues("a.b"))
        assertEquals(listOf("extended group at 0"), dialectIssues("(?:a)"))
        assertEquals(listOf("lazy or possessive quantifier at 2"), dialectIssues("a*?"))
        assertEquals(listOf("lazy or possessive quantifier at 6"), dialectIssues("a{1,3}+"))
        assertEquals(emptyList<String>(), dialectIssues("(NEGOCIO POPULAR - )?RÉGIMEN"))
    }

    @Test
    fun everyCatalogueValueSatisfiesItsElementsSchemaPatterns() {
        val catalogued = FacturaWriter.FACETS.filter { it.catalogue != null }
        assertEquals(
            listOf(
                "factura/infoTributaria/ambiente",
                "factura/infoTributaria/tipoEmision",
                "factura/infoTributaria/codDoc",
                "factura/infoFactura/tipoIdentificacionComprador",
                "factura/infoFactura/totalConImpuestos/totalImpuesto/codigo",
                "factura/detalles/detalle/impuestos/impuesto/codigo",
                "factura/reembolsos/reembolsoDetalle/detalleImpuestos/detalleImpuesto/codigo",
            ),
            catalogued.map { it.path },
        )
        for (facet in catalogued) {
            val schemaNode = checkNotNull(schema[facet.path])
            assertTrue("${facet.path} has no schema pattern", schemaNode.patterns.isNotEmpty())
            for (code in checkNotNull(facet.catalogue)) {
                assertTrue("${facet.path}: $code is outside the schema's pattern language", facet.acceptsFormat(code))
            }
        }
    }

    @Test
    fun aCatalogueValueOutsideThePatternLanguageIsCaught() {
        val facet = facet("factura/infoTributaria/ambiente").copy(catalogue = setOf("1", "2", "3"))
        assertTrue(facet.catalogue!!.any { !facet.acceptsFormat(it) })
    }

    @Test
    fun aFacetAcceptsAValueOnlyWhenOnePatternMatchesItInFull() {
        val payment = facet("factura/infoFactura/pagos/pago/formaPago")
        for (code in listOf("01", "09", "10", "19", "20", "21")) assertTrue(code, payment.acceptsFormat(code))
        for (code in listOf("00", "22", "1", "001", "20 ", " 20")) assertFalse(code, payment.acceptsFormat(code))
        assertFalse(facet("factura/infoTributaria/dirMatriz").acceptsFormat("a\nb"))
        assertTrue(facet("factura/infoTributaria/dirMatriz").acceptsFormat("a\rb"))
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
        assertEquals(3, facet("factura/infoFactura/contribuyenteEspecial").minLength)
        assertEquals(10, facet("factura/reembolsos/reembolsoDetalle/numeroautorizacionDocReemb").minLength)
        assertEquals(15, facet("factura/infoAdicional/campoAdicional").maxOccurs)
    }
}
