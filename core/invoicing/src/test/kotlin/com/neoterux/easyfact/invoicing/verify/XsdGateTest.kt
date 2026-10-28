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

import com.neoterux.easyfact.invoicing.factura.SampleFactura
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.signing.FixedIdSource
import com.neoterux.easyfact.invoicing.signing.Rfc2253Jdk
import com.neoterux.easyfact.invoicing.signing.TestIdentities
import com.neoterux.easyfact.invoicing.signing.XadesBesSigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The schema-validity gate (NFR-06): what `FacturaWriter` emits must match the factura 1.1.0
 * schema the SRI publishes, and the build fails when it does not.
 *
 * The gate runs against the UNSIGNED canonical document. The vendored revision declares no
 * `ds:Signature` in `<factura>`, so signed output cannot pass it; the signature is covered by the
 * JSR-105 and xades4j verifiers. Every passing document here comes from the writer, never typed
 * by hand, and each failing one is the writer's own output with exactly one edit.
 *
 * Offline operation is a configuration assertion (external DTD and schema access restricted, the
 * schema served from the classpath, `DOCTYPE` refused), not a proof of no egress.
 */
class XsdGateTest {

    private fun unsigned(): String = String(SampleFactura.document().octets(), Charsets.UTF_8)

    private fun fullyPopulated(): String =
        String(SampleFactura.write(SampleFactura.fullyPopulated()).octets(), Charsets.UTF_8)

    private fun errors(xml: String): List<String> = XsdGate.validationErrors(xml.toByteArray(Charsets.UTF_8))

    private fun assertValid(xml: String) {
        val found = errors(xml)
        assertTrue("schema violations:\n${found.joinToString("\n")}", found.isEmpty())
    }

    /** Replaces the one occurrence of [old] and checks that the document really changed. */
    private fun replaceOnce(xml: String, old: String, new: String): String {
        assertEquals("expected exactly one occurrence of $old", 1, Regex(Regex.escape(old)).findAll(xml).count())
        return xml.replace(old, new)
    }

    private fun element(xml: String, name: String): String =
        checkNotNull(Regex("<$name>[^<]*</$name>").find(xml)) { "no <$name> in the document" }.value

    @Test
    fun theOnlySchemaWarningIsTheUnresolvedXmldsigImport() {
        // The factura schema imports xmldsig-core-schema.xsd but never references a ds: component. If a
        // future revision starts to, compilation fails; if it grows another warning, this test says so.
        val warnings = XsdGate.schemaWarnings
        assertEquals(warnings.joinToString("\n"), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().contains("xmldsig-core-schema.xsd"))
    }

    @Test
    fun aMinimalUnsignedFacturaValidates() {
        assertValid(unsigned())
        assertNull(XsdGate.validationErrorOrNull(SampleFactura.document().octets()))
    }

    @Test
    fun aFullyPopulatedUnsignedFacturaValidates() {
        assertValid(fullyPopulated())
    }

    @Test
    fun aSignedDocumentIsNotValidatedHereBecauseThisSchemaRevisionHasNoSignatureElement() {
        // Documents why the gate runs on the unsigned document: the signature is a child of the
        // root, which this revision's content model does not allow. This asserts a property of the
        // vendored schema, not a defect in the signer; the signature is judged by the verifiers.
        val signer = XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, TestIdentities.fixedClock)
        val signed = (signer.sign(SampleFactura.document(), TestIdentities.plain) as Outcome.Ok).value.octets()
        val message = XsdGate.validationErrorOrNull(signed)
        assertNotNull("a signed document unexpectedly validated; revisit the gate's scope", message)
        assertTrue(message, message!!.contains("Signature"))
    }

    @Test
    fun aDocumentTypeDeclarationIsRefused() {
        val withDoctype = unsigned().replaceFirst("<factura", "<!DOCTYPE factura []><factura")
        val message = XsdGate.validationErrorOrNull(withDoctype.toByteArray(Charsets.UTF_8))
        assertNotNull(message)
        assertTrue(message, message!!.contains("DOCTYPE", ignoreCase = true))
    }

    @Test
    fun everyViolationIsReportedNotOnlyTheFirst() {
        val twoViolations = replaceOnce(
            replaceOnce(unsigned(), element(unsigned(), "ambiente"), "<ambiente>9</ambiente>"),
            element(unsigned(), "tipoEmision"),
            "<tipoEmision>9</tipoEmision>",
        )
        val found = errors(twoViolations)
        assertTrue(found.joinToString("\n"), found.any { it.contains("'ambiente'") })
        assertTrue(found.joinToString("\n"), found.any { it.contains("'tipoEmision'") })
    }

    // --- the three mutations that must fail the gate -----------------------------------------------

    @Test
    fun mutationElementOrderSwapFails() {
        val xml = unsigned()
        val ambiente = element(xml, "ambiente")
        val tipoEmision = element(xml, "tipoEmision")
        assertTrue("the two elements must be adjacent in schema order", xml.contains(ambiente + tipoEmision))
        val swapped = replaceOnce(xml, ambiente + tipoEmision, tipoEmision + ambiente)
        val found = errors(swapped)
        assertTrue("no violation reported for a swapped pair", found.isNotEmpty())
        assertTrue(found.joinToString("\n"), found.any { it.contains("cvc-complex-type") && it.contains("'tipoEmision'") })
    }

    @Test
    fun mutationMissingRequiredElementFails() {
        val xml = unsigned()
        val withoutRuc = replaceOnce(xml, element(xml, "ruc"), "")
        val found = errors(withoutRuc)
        assertTrue("no violation reported for a missing required element", found.isNotEmpty())
        assertTrue(
            found.joinToString("\n"),
            found.any { it.contains("cvc-complex-type") && it.contains("'claveAcceso'") && it.contains("ruc") },
        )
    }

    @Test
    fun mutationAccessKeyOfTheWrongLengthFails() {
        val xml = unsigned()
        val key = element(xml, "claveAcceso")
        val shortened = key.replace(Regex("[0-9]</claveAcceso>"), "</claveAcceso>")
        assertFalse("the key must actually have changed", key == shortened)
        val found = errors(replaceOnce(xml, key, shortened))
        assertTrue("no violation reported for a 48-digit key", found.isNotEmpty())
        assertTrue(found.joinToString("\n"), found.any { it.contains("cvc-pattern-valid") && it.contains("[0-9]{49}") })
    }
}
