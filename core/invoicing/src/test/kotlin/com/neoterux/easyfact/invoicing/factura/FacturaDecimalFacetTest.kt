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
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.verify.XsdGate
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node

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

    // ---- every decimal class at its boundaries, judged by the schema validator as well --------------

    /**
     * One decimal element a case can change. [apply] puts a value into an otherwise valid fixture,
     * [render] is [Money]'s rendering for the element, and [sentinel] is a valid value that renders
     * to text occurring exactly once in the document, so the oracle can replace it.
     */
    private class Field(
        val element: String,
        val render: (BigDecimal) -> String,
        val sentinel: BigDecimal,
        val apply: (Factura, BigDecimal) -> Factura,
    )

    private val grandTotal = Field("importeTotal", Money::amount, BigDecimal("7")) { f, v ->
        f.copy(invoiceInfo = f.invoiceInfo.copy(grandTotal = v))
    }

    private val lineTaxRate = Field("tarifa", Money::rate, BigDecimal("7")) { f, v ->
        f.copy(lines = f.lines.map { it.copy(taxes = listOf(it.taxes.single().copy(rate = v))) })
    }

    private val withholdingRate = Field("tarifa", Money::rate, BigDecimal("7")) { f, v ->
        f.copy(withholdings = listOf(Withholding("4", "327", v, BigDecimal("0"))))
    }

    private val quantity = Field("cantidad", Money::quantity, BigDecimal("7")) { f, v ->
        f.copy(lines = f.lines.map { it.copy(quantity = v) })
    }

    private val totalDiscount = Field("totalDescuento", Money::amount, BigDecimal("7")) { f, v ->
        f.copy(invoiceInfo = f.invoiceInfo.copy(totalDiscount = v))
    }

    /** What the writer is expected to do with one value. */
    private sealed interface Verdict {
        data class Written(val text: String) : Verdict
        data class Refused(val facet: String, val limit: String) : Verdict
    }

    private fun check(field: Field, input: String, verdict: Verdict) {
        val value = BigDecimal(input)
        val factura = field.apply(SampleFactura.factura(), value)
        val label = "${field.element} $input"
        val outcome = FacturaWriter.write(factura)
        when (verdict) {
            is Verdict.Written -> {
                assertTrue("$label: $outcome", outcome is Outcome.Ok)
                val xml = String(SampleFactura.write(factura).octets(), Charsets.UTF_8)
                val tag = "<${field.element}>${verdict.text}</${field.element}>"
                assertTrue("$label: expected $tag", xml.contains(tag))
                assertNull("$label: the schema gate disagrees", XsdGate.validationErrorOrNull(xml.toByteArray()))
            }
            is Verdict.Refused -> {
                assertEquals(
                    label,
                    Outcome.Err(FacturaError.DecimalOutOfRange(field.element, verdict.facet, verdict.limit)),
                    outcome,
                )
                // The oracle: the same value, rendered as Money would, inside an otherwise valid document.
                val valid = String(SampleFactura.write(field.apply(SampleFactura.factura(), field.sentinel)).octets(), Charsets.UTF_8)
                val sentinelTag = "<${field.element}>${field.render(field.sentinel)}</${field.element}>"
                assertEquals("$label: the sentinel must occur once", 1, valid.split(sentinelTag).size - 1)
                val refused = valid.replace(sentinelTag, "<${field.element}>${field.render(value)}</${field.element}>")
                assertNotNull("$label: the schema gate accepts what the writer refuses", XsdGate.validationErrorOrNull(refused.toByteArray()))
            }
        }
    }

    private fun written(text: String) = Verdict.Written(text)

    private fun refused(facet: String, limit: String) = Verdict.Refused(facet, limit)

    @Test
    fun theGrandTotalBoundariesAgreeWithTheSchemaGate() {
        check(grandTotal, "0", written("0.00"))
        check(grandTotal, "999999999999.99", written("999999999999.99"))
        check(grandTotal, "99999999999999", written("99999999999999.00"))
        check(grandTotal, "9".repeat(13) + ".99", refused("totalDigits", "14"))
        check(grandTotal, "100000000000000", refused("totalDigits", "14"))
        check(grandTotal, "-0.01", refused("minInclusive", "0"))
    }

    @Test
    fun roundingHappensBeforeJudgingSoTheBoundaryIsTheWrittenValue() {
        check(grandTotal, "-0.004", written("0.00"))
        check(grandTotal, "-0.005", refused("minInclusive", "0"))
        check(grandTotal, "1.005", written("1.01"))
        check(grandTotal, "99999999999999.004", written("99999999999999.00"))
        check(grandTotal, "99999999999999.005", refused("totalDigits", "14"))
        check(grandTotal, "99999999999999.995", refused("totalDigits", "14"))
    }

    @Test
    fun aValueBreakingTwoFacetsReportsTheFirstInTheDocumentedOrder() {
        check(grandTotal, "-100000000000000", refused("minInclusive", "0"))
    }

    @Test
    fun theNarrowRateTypesAreEnforcedAsTheSchemaStatesThem() {
        check(lineTaxRate, "99.99", written("99.99"))
        check(lineTaxRate, "9999", written("9999.00"))
        check(lineTaxRate, "100.01", refused("totalDigits", "4"))
        check(lineTaxRate, "10000", refused("totalDigits", "4"))
        check(withholdingRate, "999.99", written("999.99"))
        check(withholdingRate, "1000.01", refused("totalDigits", "5"))
    }

    @Test
    fun aQuantityIsEnforcedAtEighteenDigitsWithSixDecimals() {
        check(quantity, "999999999999.999999", written("999999999999.999999"))
        check(quantity, "9".repeat(13) + ".999999", refused("totalDigits", "18"))
        check(quantity, "1.0000005", written("1.000001"))
    }

    @Test
    fun aTotalDiscountHasNoLowerBoundBecauseTheSchemaStatesNone() {
        check(totalDiscount, "-1", written("-1.00"))
    }

    // ---- the pure check ----------------------------------------------------------------------------

    private fun facet(
        totalDigits: Int? = null,
        fractionDigits: Int? = null,
        minInclusive: String? = null,
        maxInclusive: String? = null,
        minExclusive: String? = null,
        maxExclusive: String? = null,
    ) = Facet(
        path = "factura/synthetic/amount",
        kind = Kind.DECIMAL,
        totalDigits = totalDigits,
        fractionDigits = fractionDigits,
        minInclusive = minInclusive?.let(::BigDecimal),
        maxInclusive = maxInclusive?.let(::BigDecimal),
        minExclusive = minExclusive?.let(::BigDecimal),
        maxExclusive = maxExclusive?.let(::BigDecimal),
    )

    private fun violation(facet: Facet, value: String) = FacturaWriter.decimalViolation(facet, BigDecimal(value))

    @Test
    fun aValueEqualToMaxExclusiveOrAboveMaxInclusiveIsRefused() {
        val exclusive = facet(maxExclusive = "10")
        assertEquals(FacturaError.DecimalOutOfRange("amount", "maxExclusive", "10"), violation(exclusive, "10"))
        assertNull(violation(exclusive, "9.99"))
        val inclusive = facet(maxInclusive = "10")
        assertEquals(FacturaError.DecimalOutOfRange("amount", "maxInclusive", "10"), violation(inclusive, "10.01"))
        assertNull(violation(inclusive, "10"))
    }

    @Test
    fun aValueEqualToMinExclusiveOrBelowMinInclusiveIsRefused() {
        val exclusive = facet(minExclusive = "0")
        assertEquals(FacturaError.DecimalOutOfRange("amount", "minExclusive", "0"), violation(exclusive, "0"))
        assertEquals(FacturaError.DecimalOutOfRange("amount", "minExclusive", "0"), violation(exclusive, "-0.01"))
        assertNull(violation(exclusive, "0.01"))
        val inclusive = facet(minInclusive = "0")
        assertEquals(FacturaError.DecimalOutOfRange("amount", "minInclusive", "0"), violation(inclusive, "-0.01"))
        assertNull(violation(inclusive, "0"))
    }

    @Test
    fun theBoundsAreJudgedBeforeTheDigitCounts() {
        val strict = facet(totalDigits = 3, fractionDigits = 1, minInclusive = "0", maxExclusive = "100")
        assertEquals(FacturaError.DecimalOutOfRange("amount", "minInclusive", "0"), violation(strict, "-12345.67"))
        assertEquals(FacturaError.DecimalOutOfRange("amount", "maxExclusive", "100"), violation(strict, "12345.67"))
        assertEquals(FacturaError.DecimalOutOfRange("amount", "totalDigits", "3"), violation(strict, "99.99"))
        assertEquals(FacturaError.DecimalOutOfRange("amount", "fractionDigits", "1"), violation(strict, "9.99"))
    }

    @Test
    fun digitsAreCountedAsTheSchemaCountsThem() {
        val four = facet(totalDigits = 4)
        assertNull("trailing fraction zeros are not digits", violation(four, "99.9900"))
        assertNull("a trailing integer zero is a digit but 4 fit", violation(four, "9990"))
        assertEquals(FacturaError.DecimalOutOfRange("amount", "totalDigits", "4"), violation(four, "99990"))
        assertNull("leading zeros are not digits", violation(four, "0009.99"))
        assertNull("the zeros after the point count once the integer part is empty", violation(four, "0.0012"))
        assertEquals(FacturaError.DecimalOutOfRange("amount", "totalDigits", "4"), violation(four, "0.00123"))
        assertNull(violation(four, "0"))
        assertNull(violation(four, "-9999"))
        assertEquals(FacturaError.DecimalOutOfRange("amount", "totalDigits", "4"), violation(four, "-10000"))
        assertNull("a negative scale stands for integer zeros", violation(four, "1E+3"))
        assertEquals(FacturaError.DecimalOutOfRange("amount", "totalDigits", "4"), violation(four, "1E+4"))
    }

    @Test
    fun moreFractionDigitsThanTheFacetAllowsIsRefusedOnTheValueItWasGiven() {
        val two = facet(fractionDigits = 2)
        assertNull(violation(two, "1.50"))
        assertNull(violation(two, "1.5000"))
        assertEquals(FacturaError.DecimalOutOfRange("amount", "fractionDigits", "2"), violation(two, "1.005"))
    }

    @Test
    fun aFacetWithNoDecimalFactsJudgesNothing() {
        assertNull(violation(facet(), "-123456789012345678.123456789"))
    }

    // ---- Money's scales cannot drift from the schema -----------------------------------------------

    private fun parse(octets: ByteArray): Element = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder().parse(ByteArrayInputStream(octets)).documentElement

    private fun walk(element: Element, parentPath: String?, into: MutableList<Pair<String, String>>) {
        val path = if (parentPath == null) element.localName else "$parentPath/${element.localName}"
        var child = element.firstChild
        var hasElementChild = false
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                hasElementChild = true
                walk(child as Element, path, into)
            }
            child = child.nextSibling
        }
        if (!hasElementChild) into += path to element.textContent
    }

    @Test
    fun everyDecimalInTheEveryElementDocumentCarriesExactlyItsFractionDigits() {
        val leaves = mutableListOf<Pair<String, String>>()
        walk(parse(SampleFactura.write(SampleFactura.everyElement()).octets()), null, leaves)
        val decimals = FacturaWriter.FACETS.filter { it.kind == Kind.DECIMAL }.associateBy { it.path }
        val checked = mutableSetOf<String>()
        val scales = mutableSetOf<Int>()
        for ((path, text) in leaves) {
            val facet = decimals[path] ?: continue
            val declared = checkNotNull(facet.fractionDigits) { "$path has no fractionDigits" }
            val actual = text.substringAfter('.', "").length
            assertEquals("$path is written as $text", declared, actual)
            checked += path
            scales += actual
        }
        assertTrue("scale 2 was not seen: $scales", 2 in scales)
        assertTrue("scale 6 was not seen: $scales", 6 in scales)
        assertTrue(
            "factura/infoFactura/totalConImpuestos/totalImpuesto/tarifa" in checked,
        )
        assertTrue(
            "factura/reembolsos/reembolsoDetalle/compensacionesReembolso/compensacionReembolso/tarifa" in checked,
        )
        assertEquals("a decimal facet the document never wrote", decimals.keys, checked)
    }

    @Test
    fun aDecimalFailureRendersWithoutTheAmount() {
        val rendered = error(withGrandTotal("-123456.78")).toString()
        assertEquals("DecimalOutOfRange(element=importeTotal, facet=minInclusive, limit=0)", rendered)
        assertFalse(rendered, rendered.contains("123456"))
    }
}
