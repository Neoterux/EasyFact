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

import com.neoterux.easyfact.invoicing.factura.SampleFactura
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.result.SigningError
import com.neoterux.easyfact.invoicing.verify.Jsr105Verifier
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import com.neoterux.easyfact.testing.SyntheticCertificates
import java.math.BigInteger
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XadesBesSignerTest {

    private fun signer(idSource: IdSource = FixedIdSource(100000), format: IssuerNameFormat = Rfc2253Jdk) =
        XadesBesSigner(idSource, format, TestIdentities.fixedClock)

    private fun sign(
        document: CanonicalDocument = SampleFactura.document(),
        identity: SigningIdentity = TestIdentities.plain,
    ): Outcome<SignedDocument, SigningError> = signer().sign(document, identity)

    private fun signedText(identity: SigningIdentity = TestIdentities.plain): String =
        String((sign(identity = identity) as Outcome.Ok).value.octets(), Charsets.UTF_8)

    private fun errorOf(outcome: Outcome<SignedDocument, SigningError>): SigningError =
        (outcome as? Outcome.Err)?.error ?: throw AssertionError("expected a typed failure, got $outcome")

    private fun documentOf(root: String, prolog: String = ""): CanonicalDocument =
        CanonicalDocument(prolog.toByteArray(Charsets.UTF_8), root.toByteArray(Charsets.UTF_8))

    private fun rootText(): String = String(SampleFactura.document().root, Charsets.UTF_8)

    // --- success path -----------------------------------------------------------------------

    @Test
    fun signingProducesADocumentTheJdkAccepts() {
        val signed = (sign() as Outcome.Ok).value
        assertTrue(Jsr105Verifier.validate(signed.octets()))
    }

    @Test
    fun theSameInputsGiveTheSameOctets() {
        val first = (sign() as Outcome.Ok).value
        val second = (sign() as Outcome.Ok).value
        assertTrue(first.contentEquals(second.octets()))
    }

    @Test
    fun aDifferentIdSourceGivesDifferentOctetsThatStillValidate() {
        val other = (signer(FixedIdSource(555555)).sign(SampleFactura.document(), TestIdentities.plain) as Outcome.Ok).value
        val baseline = (sign() as Outcome.Ok).value
        assertFalse(baseline.contentEquals(other.octets()))
        assertTrue(Jsr105Verifier.validate(other.octets()))
    }

    @Test
    fun aSignerInstanceConsumesEightIdsPerSignature() {
        val seen = mutableListOf<Int>()
        val source = IdSource { (100000 + seen.size).also { seen += it } }
        signer(source).sign(SampleFactura.document(), TestIdentities.plain)
        assertEquals(8, seen.size)
    }

    @Test
    fun theDeclarationIsTransmittedButNotDigestedAndTheSignedRootIsTheSameEitherWay() {
        val withProlog = SampleFactura.document()
        val withoutProlog = documentOf(String(withProlog.root, Charsets.UTF_8))
        assertTrue(withProlog.prolog.isNotEmpty())

        val signedWith = (sign(withProlog) as Outcome.Ok).value.octets()
        val signedWithout = (sign(withoutProlog) as Outcome.Ok).value.octets()

        assertTrue(Jsr105Verifier.validate(signedWith))
        assertTrue(Jsr105Verifier.validate(signedWithout))
        assertArrayEquals(withProlog.prolog, signedWith.copyOfRange(0, withProlog.prolog.size))
        assertArrayEquals(signedWithout, signedWith.copyOfRange(withProlog.prolog.size, signedWith.size))
    }

    @Test
    fun theDocumentBodyBeforeTheSignatureIsTheInputRootUnchanged() {
        val document = SampleFactura.document()
        val root = String(document.root, Charsets.UTF_8)
        val prolog = String(document.prolog, Charsets.UTF_8)
        val signed = signedText()
        val cut = root.length - "</factura>".length
        assertTrue(signed.startsWith(prolog))
        assertEquals(root.substring(0, cut), signed.substring(prolog.length, prolog.length + cut))
        assertTrue(signed.endsWith("</ds:Signature></factura>"))
    }

    @Test
    fun everyIdentityShapeSignsAndValidates() {
        for (identity in listOf(
            TestIdentities.plain,
            TestIdentities.organizationIdentifierUtf8,
            TestIdentities.organizationIdentifierPrintable,
            TestIdentities.nonAscii,
        )) {
            assertTrue(Jsr105Verifier.validate((sign(identity = identity) as Outcome.Ok).value.octets()))
        }
    }

    @Test
    fun everyIssuerNameFormatSignsAndValidates() {
        for ((name, format) in IssuerNameFormats.ALL) {
            val outcome = signer(format = format).sign(SampleFactura.document(), TestIdentities.organizationIdentifierUtf8)
            assertTrue("format $name", Jsr105Verifier.validate((outcome as Outcome.Ok).value.octets()))
        }
    }

    @Test
    fun theIssuerNameFormatChangesWhatIsWrittenIntoSigningCertificate() {
        val identity = TestIdentities.organizationIdentifierUtf8
        val rfc2253 = (signer(format = Rfc2253Jdk).sign(SampleFactura.document(), identity) as Outcome.Ok).value
        val decoded = (signer(format = DecodedOidRfc2253).sign(SampleFactura.document(), identity) as Outcome.Ok).value
        val rfc2253Text = String(rfc2253.octets(), Charsets.UTF_8)
        val decodedText = String(decoded.octets(), Charsets.UTF_8)
        assertTrue(rfc2253Text.contains("2.5.4.97=#0c"))
        assertTrue(decodedText.contains("2.5.4.97=VATES-A00000000"))
    }

    @Test
    fun theIssuerNameIsEscapedAsXmlText() {
        val format = IssuerNameFormat { "CN=a & b <c>" }
        val outcome = signer(format = format).sign(SampleFactura.document(), TestIdentities.plain)
        val text = String((outcome as Outcome.Ok).value.octets(), Charsets.UTF_8)
        assertTrue(text.contains("<ds:X509IssuerName>CN=a &amp; b &lt;c&gt;</ds:X509IssuerName>"))
        assertTrue(Jsr105Verifier.validate((outcome).value.octets()))
    }

    @Test
    fun signingTimeIsWrittenAtTheSriOffset() {
        assertTrue(signedText().contains("<etsi:SigningTime>2026-10-27T12:00:00-05:00</etsi:SigningTime>"))
    }

    // --- the SRI parameter table, asserted structurally -------------------------------------

    @Test
    fun theSignatureUsesTheParametersTheSriMandates() {
        val text = signedText()
        assertEquals("three references", 3, Regex("<ds:Reference[ >]").findAll(text).count())
        assertTrue(text.contains("<ds:Transform Algorithm=\"http://www.w3.org/2000/09/xmldsig#enveloped-signature\">"))
        assertTrue(text.contains("<ds:CanonicalizationMethod Algorithm=\"http://www.w3.org/TR/2001/REC-xml-c14n-20010315\">"))
        assertTrue(text.contains("<ds:SignatureMethod Algorithm=\"http://www.w3.org/2000/09/xmldsig#rsa-sha1\">"))
        val digestMethods = Regex("<ds:DigestMethod Algorithm=\"([^\"]*)\">").findAll(text).map { it.groupValues[1] }.toList()
        assertTrue(digestMethods.isNotEmpty())
        assertTrue(digestMethods.all { it == "http://www.w3.org/2000/09/xmldsig#sha1" })
        assertFalse(text.contains("xml-exc-c14n"))
        assertFalse(text.contains("sha256"))
    }

    @Test
    fun theReferencesPointAtSignedPropertiesTheCertificateAndTheComprobante() {
        val text = signedText()
        assertTrue(text.contains("Type=\"http://uri.etsi.org/01903#SignedProperties\" URI=\"#Signature100000-SignedProperties100004\""))
        assertTrue(text.contains("<ds:Reference URI=\"#Certificate100003\">"))
        assertTrue(text.contains("<ds:Reference Id=\"Reference-ID-100006\" URI=\"#comprobante\">"))
        assertTrue(text.contains("<factura id=\"comprobante\" version=\"1.1.0\">"))
    }

    @Test
    fun theKeyInfoCarriesTheCertificateAndTheRsaKeyValue() {
        val text = signedText()
        val publicKey = TestIdentities.plain.certificate.publicKey as RSAPublicKey
        val modulus = java.util.Base64.getEncoder().encodeToString(unsigned(publicKey.modulus))
        assertTrue(text.contains("<ds:X509Certificate>"))
        assertTrue(text.contains("<ds:Modulus>$modulus</ds:Modulus>"))
        assertEquals(256, java.util.Base64.getDecoder().decode(modulus).size)
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0].toInt() == 0) bytes.copyOfRange(1, bytes.size) else bytes
    }

    // --- typed failures ---------------------------------------------------------------------

    @Test
    fun aKeyOfTheWrongSizeIsATypedFailure() {
        assertEquals(SigningError.KeyNotRsa2048, errorOf(sign(identity = TestIdentities.rsa1024)))
    }

    @Test
    fun aNonRsaCertificateIsATypedFailureNotAClassCastException() {
        assertEquals(SigningError.KeyNotRsa2048, errorOf(sign(identity = TestIdentities.ecP256)))
    }

    @Test
    fun aCertificateOutsideItsValidityWindowIsATypedFailureCarryingTheSigningTime() {
        assertEquals(
            SigningError.CertificateNotValidAtSigningTime(TestIdentities.signingTime),
            errorOf(sign(identity = TestIdentities.expired)),
        )
    }

    @Test
    fun aPrivateKeyFromAnotherIdentityIsATypedFailure() {
        val mismatched = SigningIdentity(TestIdentities.other.privateKey, TestIdentities.plain.certificate)
        assertEquals(SigningError.PrivateKeyDoesNotMatchCertificate, errorOf(sign(identity = mismatched)))
    }

    @Test
    fun aPrivateKeyOfAnotherAlgorithmIsATypedFailure() {
        val mismatched = SigningIdentity(TestIdentities.ecP256.privateKey, TestIdentities.plain.certificate)
        assertEquals(SigningError.PrivateKeyDoesNotMatchCertificate, errorOf(sign(identity = mismatched)))
    }

    @Test
    fun aPrivateKeyTheProviderCannotUseIsATypedSigningFailureNotAnException() {
        val unusable = object : PrivateKey {
            override fun getAlgorithm(): String = "RSA"
            override fun getFormat(): String? = null
            override fun getEncoded(): ByteArray? = null
        }
        val error = errorOf(sign(identity = SigningIdentity(unusable, TestIdentities.plain.certificate)))
        assertEquals(SigningError.SigningFailed("key-check"), error)
    }

    @Test
    fun aCarriageReturnInTheRootIsATypedFailure() {
        val error = errorOf(sign(documentOf(rootText().replace("<ruc>", "\r<ruc>"))))
        assertTrue(error is SigningError.InputNotCanonical)
        assertTrue((error as SigningError.InputNotCanonical).reason.contains("carriage return"))
    }

    @Test
    fun aSelfClosingTagInTheRootIsATypedFailure() {
        val error = errorOf(sign(documentOf(rootText().replace("</factura>", "<x/></factura>"))))
        assertTrue(error is SigningError.InputNotCanonical)
        assertTrue((error as SigningError.InputNotCanonical).reason.contains("self-closing"))
    }

    @Test
    fun aSlashAndBracketInsideAnAttributeValueIsNotMistakenForASelfClosingTag() {
        val root = rootText().replace("nombre=\"Email\"", "nombre=\"a/>b\"")
        val outcome = sign(documentOf(root))
        assertTrue("got $outcome", outcome is Outcome.Ok)
    }

    @Test
    fun aRootThatDoesNotStartOrEndWithTheFacturaElementIsATypedFailure() {
        assertTrue(errorOf(sign(documentOf(" " + rootText()))) is SigningError.InputNotCanonical)
        assertTrue(errorOf(sign(documentOf(rootText() + "\n"))) is SigningError.InputNotCanonical)
        assertTrue(errorOf(sign(documentOf("<notafactura></notafactura>"))) is SigningError.InputNotCanonical)
    }

    @Test
    fun rootBytesThatAreNotValidUtf8AreATypedFailure() {
        val root = rootText().toByteArray(Charsets.UTF_8)
        val broken = root.copyOf().also { it[20] = 0xC3.toByte(); it[21] = 0x28 }
        val error = errorOf(sign(CanonicalDocument(ByteArray(0), broken)))
        assertTrue(error is SigningError.InputNotCanonical)
    }

    // --- no exception crosses the Outcome boundary ------------------------------------------

    private fun opaqueIdentity() =
        SigningIdentity(OpaqueTestKey(TestIdentities.plain.privateKey), TestIdentities.plain.certificate)

    @Test
    fun aClockThatThrowsIsATypedFailureNamingTheClock() {
        val clock: () -> java.time.OffsetDateTime = { throw java.time.DateTimeException("Test Signing CA clock fault") }
        val outcome = XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, clock).sign(SampleFactura.document(), TestIdentities.plain)
        assertEquals(SigningError.SigningFailed("clock"), errorOf(outcome))
    }

    @Test
    fun anIssuerNameFormatThatThrowsIsATypedFailureNamingTheIssuerName() {
        val format = IssuerNameFormat { throw IllegalArgumentException("Test Signing CA could not be rendered") }
        val error = errorOf(signer(format = format).sign(SampleFactura.document(), TestIdentities.plain))
        assertEquals(SigningError.SigningFailed("issuer-name"), error)
    }

    @Test
    fun anIssuerNameWithACharacterXmlForbidsIsATypedFailureNamingTheIssuerName() {
        val format = IssuerNameFormat { "CN=a\u0001b" }
        val error = errorOf(signer(format = format).sign(SampleFactura.document(), TestIdentities.plain))
        assertEquals(SigningError.SigningFailed("issuer-name"), error)
    }

    @Test
    fun aProviderThatFailsDuringTheKeyCheckIsATypedFailureNamingTheKeyCheck() {
        val error = ThrowingSignatureProvider.use(allowedSignatures = 0) { errorOf(sign(identity = opaqueIdentity())) }
        assertEquals(SigningError.SigningFailed("key-check"), error)
    }

    @Test
    fun aProviderThatFailsWhileSigningSignedInfoIsATypedFailureNamingTheSignature() {
        val error = ThrowingSignatureProvider.use(allowedSignatures = 1) { errorOf(sign(identity = opaqueIdentity())) }
        assertEquals(SigningError.SigningFailed("signature"), error)
    }

    @Test
    fun theOpaqueKeySignsAndValidatesWhenTheProviderDoesNotFail() {
        val outcome = ThrowingSignatureProvider.use(allowedSignatures = 2) { sign(identity = opaqueIdentity()) }
        assertTrue("got $outcome", outcome is Outcome.Ok)
        assertTrue(Jsr105Verifier.validate((outcome as Outcome.Ok).value.octets()))
    }

    @Test
    fun theTestProviderIsGoneAfterEveryUseEvenWhenTheBlockThrows() {
        ThrowingSignatureProvider.use(allowedSignatures = 0) { errorOf(sign(identity = opaqueIdentity())) }
        assertNull(java.security.Security.getProvider(ThrowingSignatureProvider.NAME))
        try {
            ThrowingSignatureProvider.use(allowedSignatures = 0) { throw IllegalStateException("boom") }
        } catch (expected: IllegalStateException) {
            // the provider must still be removed
        }
        assertNull(java.security.Security.getProvider(ThrowingSignatureProvider.NAME))
    }

    @Test
    fun noContainedFailureRendersASubjectAnIssuerOrKeyMaterial() {
        val sensitive = listOf("Test Signing CA", "Test CA S.A.", "CN=", "BEGIN", "test provider failure", SyntheticCertificates.DEFAULT_PASSWORD)
        val clock: () -> java.time.OffsetDateTime = { throw java.time.DateTimeException("Test Signing CA clock fault") }
        val renderings = mutableListOf<String>()
        renderings += errorOf(XadesBesSigner(FixedIdSource(100000), Rfc2253Jdk, clock).sign(SampleFactura.document(), TestIdentities.plain)).toString()
        renderings += errorOf(signer(format = IssuerNameFormat { throw IllegalArgumentException("CN=Test CA S.A.") }).sign(SampleFactura.document(), TestIdentities.plain)).toString()
        renderings += ThrowingSignatureProvider.use(0) { errorOf(sign(identity = opaqueIdentity())).toString() }
        renderings += ThrowingSignatureProvider.use(1) { errorOf(sign(identity = opaqueIdentity())).toString() }
        for (rendering in renderings) {
            for (needle in sensitive) assertFalse("'$needle' leaked into: $rendering", rendering.contains(needle))
        }
    }

    // --- the document reference must resolve to the root ------------------------------------

    private val sampleRootTag = "<factura id=\"comprobante\" version=\"1.1.0\">"

    private fun assertRefusedForRootId(root: String) {
        val error = errorOf(sign(documentOf(root)))
        assertTrue("got $error", error is SigningError.InputNotCanonical)
        assertTrue(
            "reason names the id: $error",
            (error as SigningError.InputNotCanonical).reason.contains("id=\"comprobante\""),
        )
    }

    @Test
    fun aRootWithNoAttributesIsRefusedBecauseTheReferenceCannotResolve() {
        assertTrue(rootText().contains(sampleRootTag))
        assertRefusedForRootId(rootText().replace(sampleRootTag, "<factura>"))
    }

    @Test
    fun aRootWhoseIdIsAnotherValueIsRefused() {
        assertRefusedForRootId(rootText().replace(sampleRootTag, "<factura id=\"x\" version=\"1.1.0\">"))
    }

    @Test
    fun anUppercaseIdAttributeIsRefused() {
        assertRefusedForRootId(rootText().replace(sampleRootTag, "<factura ID=\"comprobante\" version=\"1.1.0\">"))
    }

    @Test
    fun anAttributeWhoseNameMerelyEndsInIdIsRefused() {
        assertRefusedForRootId(rootText().replace(sampleRootTag, "<factura version=\"1.1.0\" xid=\"comprobante\">"))
    }

    @Test
    fun theIdOnAChildElementDoesNotCountForTheRoot() {
        val root = rootText()
            .replace(sampleRootTag, "<factura version=\"1.1.0\">")
            .replace("<infoTributaria>", "<infoTributaria id=\"comprobante\">")
        assertTrue("fixture must carry the decoy", root.contains("<infoTributaria id=\"comprobante\">"))
        assertRefusedForRootId(root)
    }

    @Test
    fun theIdIsFoundWhateverItsPositionAmongTheRootAttributes() {
        val root = rootText().replace(sampleRootTag, "<factura a=\"1\" id=\"comprobante\" version=\"1.1.0\">")
        val outcome = sign(documentOf(root))
        assertTrue("got $outcome", outcome is Outcome.Ok)
    }

    @Test
    fun aDocumentBuiltLikeTheGoldenOneStillSignsAndValidates() {
        val signed = (sign(com.neoterux.easyfact.testing.GoldenAssetGenerator.unsignedDocument()) as Outcome.Ok).value
        assertTrue(Jsr105Verifier.validate(signed.octets()))
    }

    // --- nothing sensitive in an error or an identity ---------------------------------------

    @Test
    fun noErrorAndNoIdentityRendersKeyMaterialASubjectAnIssuerOrAPassword() {
        val sensitive = listOf("Test Signing CA", "Test CA S.A.", "CN=", "BEGIN", SyntheticCertificates.DEFAULT_PASSWORD)
        val renderings = mutableListOf<String>()
        renderings += TestIdentities.plain.toString()
        renderings += errorOf(sign(identity = TestIdentities.rsa1024)).toString()
        renderings += errorOf(sign(identity = TestIdentities.expired)).toString()
        renderings += errorOf(sign(identity = SigningIdentity(TestIdentities.other.privateKey, TestIdentities.plain.certificate))).toString()
        renderings += errorOf(sign(documentOf("<factura>\r</factura>"))).toString()
        renderings += SigningError.KeystoreUnlockFailed.toString()
        renderings += SigningError.SigningFailed("signature").toString()
        for (rendering in renderings) {
            for (needle in sensitive) assertFalse("'$needle' leaked into: $rendering", rendering.contains(needle))
        }
        assertNotNull(renderings.first())
    }

    @Test
    fun aSignedDocumentDoesNotRenderItsContent() {
        val rendered = (sign() as Outcome.Ok).value.toString()
        assertFalse(rendered.contains("factura"))
        assertTrue(rendered.startsWith("SignedDocument("))
    }

    @Test
    fun aSignedDocumentHandsOutCopies() {
        val signed = (sign() as Outcome.Ok).value
        val copy = signed.octets()
        copy[0] = 0
        assertEquals('<'.code.toByte(), signed.octets()[0])
    }
}
