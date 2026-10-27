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

package com.neoterux.easyfact.invoicing.signing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three renderings of an issuer name. The signer's use of them is covered by
 * `XadesBesSignerTest`; these tests pin down what each one actually produces.
 */
class IssuerNameFormatTest {

    private val utf8 = TestIdentities.organizationIdentifierUtf8.certificate
    private val printable = TestIdentities.organizationIdentifierPrintable.certificate
    private val plain = TestIdentities.plain.certificate

    @Test
    fun rfc2253JdkRendersAnUnknownOidValueAsHexDer() {
        val name = Rfc2253Jdk.render(utf8)
        assertTrue(name, name.startsWith("2.5.4.97=#0c"))
        assertTrue(name, name.contains(",CN=Test Signing CA,"))
    }

    @Test
    fun decodedOidRfc2253WritesTheSameValueAsText() {
        assertEquals(
            "2.5.4.97=VATES-A00000000,CN=Test Signing CA,O=Test CA S.A.,C=EC",
            DecodedOidRfc2253.render(utf8),
        )
    }

    @Test
    fun decodedOidRfc2253DecodesAPrintableStringToo() {
        val name = DecodedOidRfc2253.render(printable)
        assertTrue(name, name.contains("2.5.4.97=VATES-A00000000"))
        assertFalse(name, name.contains("#"))
    }

    @Test
    fun decodedOidRfc2253KeepsTheJdkOrderingAndPunctuation() {
        val typesOf = { name: String -> Regex("(?:^|,)([^=,]+)=").findAll(name).map { it.groupValues[1] }.toList() }
        val decoded = DecodedOidRfc2253.render(utf8)
        assertFalse(decoded.contains(", "))
        assertEquals(typesOf(Rfc2253Jdk.render(utf8)), typesOf(decoded))
    }

    @Test
    fun forAnIssuerWithOnlyWellKnownTypesDecodedAndJdkRenderingsAreEqual() {
        assertEquals(Rfc2253Jdk.render(plain), DecodedOidRfc2253.render(plain))
        assertEquals(Rfc2253Jdk.render(TestIdentities.nonAscii.certificate), DecodedOidRfc2253.render(TestIdentities.nonAscii.certificate))
    }

    @Test
    fun rfc1779JdkUsesCommaSpaceAndOidPrefix() {
        val name = Rfc1779Jdk.render(utf8)
        assertTrue(name, name.contains("OID.2.5.4.97=VATES-A00000000, CN=Test Signing CA, "))
    }

    @Test
    fun allListsEveryRenderingByNameAndTheDefaultComesFirst() {
        assertEquals(listOf("Rfc2253Jdk", "DecodedOidRfc2253", "Rfc1779Jdk"), IssuerNameFormats.ALL.map { it.first })
        assertEquals(3, IssuerNameFormats.ALL.map { it.second.render(utf8) }.toSet().size)
    }

    // --- the hex-value decoder, on strings the JDK would not produce from these certificates ---

    @Test
    fun aValueThatIsNotHexDerIsPassedThroughUnchanged() {
        assertEquals("CN=a\\,b,O=c", DecodedOidRfc2253.decodeHexValues("CN=a\\,b,O=c"))
        assertEquals("2.5.4.97=#zz", DecodedOidRfc2253.decodeHexValues("2.5.4.97=#zz"))
        assertEquals("2.5.4.97=#13", DecodedOidRfc2253.decodeHexValues("2.5.4.97=#13"))
    }

    @Test
    fun aHexValueWhoseLengthDisagreesWithItsContentIsLeftAsIs() {
        // PrintableString of declared length 5 holding 2 octets.
        assertEquals("2.5.4.97=#13054142", DecodedOidRfc2253.decodeHexValues("2.5.4.97=#13054142"))
    }

    @Test
    fun decodedTextIsEscapedAndTheMultiValuedSeparatorSurvives() {
        // 0x13 0x03 'a' ',' 'b' decodes to the text a,b which must come back escaped.
        assertEquals("2.5.4.97=a\\,b+CN=x", DecodedOidRfc2253.decodeHexValues("2.5.4.97=#1303612c62+CN=x"))
    }

    @Test
    fun aLongFormLengthIsDecoded() {
        val text = "x".repeat(130)
        val hex = text.map { "%02x".format(it.code) }.joinToString("")
        // 0x0c, long form with one length byte (0x81), length 130 (0x82).
        assertEquals("2.5.4.97=$text", DecodedOidRfc2253.decodeHexValues("2.5.4.97=#0c8182$hex"))
    }
}
