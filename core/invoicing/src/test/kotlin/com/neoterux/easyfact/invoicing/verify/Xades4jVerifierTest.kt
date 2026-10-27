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
import com.neoterux.easyfact.invoicing.signing.SigningIdentity
import com.neoterux.easyfact.invoicing.signing.TestIdentities
import com.neoterux.easyfact.invoicing.signing.XadesBesSigner
import com.neoterux.easyfact.invoicing.verify.Xades4jVerifier.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xades4j.verification.XAdESForm

/**
 * xades4j, the second verifier, judging the signer's own output. It answers the question the
 * JDK's XML-DSig implementation cannot: the document is XAdES-**BES**, not merely a well-formed
 * XML signature.
 *
 * The three issuer shapes are here and not only in the probe because `organizationIdentifier`
 * (OID 2.5.4.97) and non-ASCII attributes are what research singled out as the renderings most
 * likely to break a verifier, and a `keytool`-minted certificate cannot produce them.
 *
 * **Expected noise.** On a modern JDK, xades4j logs warnings from Guava and from `sun.misc.Unsafe`
 * (`A terminally deprecated method in sun.misc.Unsafe has been called`). They come from the
 * verifier's own dependencies, they are not a failure, and nothing in this project ships them.
 */
class Xades4jVerifierTest {

    /** A fresh signer per call: one instance consumes eight ids per signature. */
    private fun signedWith(identity: SigningIdentity): ByteArray {
        val signer = XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, TestIdentities.fixedClock)
        return ((signer.sign(SampleFactura.document(), identity)) as Outcome.Ok).value.octets()
    }

    private fun verify(identity: SigningIdentity) =
        Xades4jVerifier.verify(signedWith(identity), identity.certificate)

    @Test
    fun theSignersOwnOutputIsReportedAsXadesBes() {
        val report = verify(TestIdentities.plain)
        assertEquals(report.failure, Verdict.VALID, report.verdict)
        assertEquals(XAdESForm.BES, report.form)
    }

    @Test
    fun theSignedDocumentCarriesTheThreeReferencesTheSriMandates() {
        val report = verify(TestIdentities.plain)
        assertEquals(Verdict.VALID, report.verdict)
        // SignedProperties, KeyInfo and the document itself (docs/sri-electronic-invoicing.md
        // section 6). xades4j counts the latter two as signed data objects; the SignedProperties
        // reference is a qualifying property, not a data object.
        assertEquals(3, report.references)
        assertEquals(2, report.signedDataObjects)
    }

    @Test
    fun anIssuerWithAUtf8OrganizationIdentifierVerifiesAsBes() {
        val report = verify(TestIdentities.organizationIdentifierUtf8)
        assertEquals(report.failure, Verdict.VALID, report.verdict)
        assertEquals(XAdESForm.BES, report.form)
    }

    @Test
    fun anIssuerWithAPrintableStringOrganizationIdentifierVerifiesAsBes() {
        val report = verify(TestIdentities.organizationIdentifierPrintable)
        assertEquals(report.failure, Verdict.VALID, report.verdict)
        assertEquals(XAdESForm.BES, report.form)
    }

    @Test
    fun aNonAsciiIssuerVerifiesAsBes() {
        val report = verify(TestIdentities.nonAscii)
        assertEquals(report.failure, Verdict.VALID, report.verdict)
        assertEquals(XAdESForm.BES, report.form)
    }

    @Test
    fun aOneByteChangeIsRejectedAndNotAnUnrelatedError() {
        val identity = TestIdentities.plain
        val original = signedWith(identity)
        val text = String(original, Charsets.UTF_8)
        val at = text.indexOf("<importeTotal>") + "<importeTotal>".length
        val changed = StringBuilder(text)
            .also { it.setCharAt(at, if (text[at] == '9') '8' else '9') }
            .toString()
            .toByteArray(Charsets.UTF_8)
        assertEquals("exactly one byte must differ", 1, original.indices.count { original[it] != changed[it] })

        val report = Xades4jVerifier.verify(changed, identity.certificate)
        assertEquals(report.failure, Verdict.INVALID, report.verdict)
        assertNotNull(report.failure)
    }

    @Test
    fun aTrustAnchorThatIsNotTheSignersIsRejected() {
        val report = Xades4jVerifier.verify(
            signedWith(TestIdentities.plain),
            TestIdentities.other.certificate,
        )
        assertEquals(report.failure, Verdict.INVALID, report.verdict)
        assertNotNull(report.failure)
    }

    @Test
    fun anUnsignedDocumentIsMalformedNotValid() {
        val report = Xades4jVerifier.verify(
            SampleFactura.document().octets(),
            TestIdentities.plain.certificate,
        )
        assertEquals(Verdict.MALFORMED, report.verdict)
    }

    @Test
    fun bytesThatAreNotXmlAreMalformedNotValid() {
        val report = Xades4jVerifier.verify("not xml".toByteArray(), TestIdentities.plain.certificate)
        assertEquals(Verdict.MALFORMED, report.verdict)
    }

    @Test
    fun theTwoVerifiersAgreeOnTheSignersOwnOutput() {
        val identity = TestIdentities.plain
        val octets = signedWith(identity)
        assertTrue(Jsr105Verifier.validate(octets))
        assertTrue(Xades4jVerifier.validate(octets, identity.certificate))
    }
}
