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

import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.result.SigningError
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import com.neoterux.easyfact.invoicing.xml.CanonicalEmitter
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAKey
import java.security.interfaces.RSAPublicKey
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Date
import java.util.Locale

/**
 * XAdES-BES as the SRI requires it: enveloped, inclusive C14N 1.0, RSA-SHA1 with SHA-1 digests,
 * a 2048-bit key, three references, and the signature as the last child of the root.
 *
 * **Do not modernise.** SHA-256 and exclusive C14N are the natural defaults and both are wrong
 * here; the SRI validates with its own library and rejects them. The SHA-1 helpers are private
 * to this class on purpose: nothing outside the signing package gets a general-purpose weak
 * digest or signature.
 *
 * The method is "write it already canonical, then sign what was written". The input is a
 * [CanonicalDocument] whose root is already in inclusive-C14N form, so each digest is taken over
 * the bytes themselves and the signature is spliced in just before the closing tag. Nothing is
 * parsed and re-serialized between signing and sending, and the transmitted octets are the
 * prolog followed by the signed root. Inclusive C14N makes in-scope namespace declarations part
 * of the octets of every digested element, so each element that is digested separately is written
 * twice: once with the declarations on its apex (the form that is digested) and once without (the
 * form that sits inside the already-declaring `ds:Signature`).
 *
 * Failure is a value: every foreseeable problem is a [SigningError], and no exception leaves
 * [sign]. The places that call the JCA, the injected clock or the issuer-name format catch
 * [GeneralSecurityException] and [RuntimeException] alike and report the stage: a Keystore-backed
 * key surfaces provider trouble as `java.security.ProviderException`, which is a
 * [RuntimeException]. The cause is dropped, since a provider message can quote key or certificate
 * details.
 *
 * @param idSource supplies the eight numbers the element ids are built from; a signer instance
 * consumes eight per signature, so build a new instance for a repeatable result
 * @param issuerNameFormat how the issuer name is rendered into `X509IssuerName`; the first suspect
 * if the SRI answers error 39
 * @param clock the signing time, at the SRI's fixed UTC-05:00 offset for America/Guayaquil
 */
class XadesBesSigner(
    private val idSource: IdSource = RandomIdSource(),
    private val issuerNameFormat: IssuerNameFormat = Rfc2253Jdk,
    private val clock: () -> OffsetDateTime = { OffsetDateTime.now(ZoneOffset.ofHours(-5)) },
) : Signer {

    override fun sign(document: CanonicalDocument, identity: SigningIdentity): Outcome<SignedDocument, SigningError> {
        val signingTime = try {
            clock()
        } catch (e: RuntimeException) {
            return Outcome.Err(SigningError.SigningFailed("clock"))
        }
        val certificate = identity.certificate

        val publicKey = certificate.publicKey as? RSAPublicKey
        if (publicKey == null || publicKey.modulus.bitLength() != REQUIRED_KEY_BITS) {
            return Outcome.Err(SigningError.KeyNotRsa2048)
        }
        try {
            certificate.checkValidity(Date.from(signingTime.toInstant()))
        } catch (e: CertificateExpiredException) {
            return Outcome.Err(SigningError.CertificateNotValidAtSigningTime(signingTime))
        } catch (e: CertificateNotYetValidException) {
            return Outcome.Err(SigningError.CertificateNotValidAtSigningTime(signingTime))
        }
        if (identity.privateKey.algorithm != "RSA") {
            return Outcome.Err(SigningError.PrivateKeyDoesNotMatchCertificate)
        }
        val matches = try {
            privateKeyMatches(identity.privateKey, publicKey)
        } catch (e: GeneralSecurityException) {
            return Outcome.Err(SigningError.SigningFailed("key-check"))
        } catch (e: RuntimeException) {
            return Outcome.Err(SigningError.SigningFailed("key-check"))
        }
        if (!matches) return Outcome.Err(SigningError.PrivateKeyDoesNotMatchCertificate)

        val root = document.root
        canonicalProblem(root)?.let { return Outcome.Err(SigningError.InputNotCanonical(it)) }

        var stage = "ids"
        try {
            val ids = IntArray(ID_COUNT) { idSource.next() }
            val sigId = "Signature${ids[0]}"
            val signedInfoId = "Signature-SignedInfo${ids[1]}"
            val signatureValueId = "SignatureValue${ids[2]}"
            val keyInfoId = "Certificate${ids[3]}"
            val signedPropertiesId = "$sigId-SignedProperties${ids[4]}"
            val objectId = "$sigId-Object${ids[5]}"
            val documentReferenceId = "Reference-ID-${ids[6]}"
            val signedPropertiesReferenceId = "SignedPropertiesID${ids[7]}"

            stage = "certificate-encoding"
            val certificateB64 = BASE64.encodeToString(certificate.encoded)
            val modulusB64 = BASE64.encodeToString(unsigned(publicKey.modulus))
            val exponentB64 = BASE64.encodeToString(unsigned(publicKey.publicExponent))

            // The issuer name is rendered by an injected format and goes into the document as text,
            // so it is checked here: a character XML 1.0 forbids would make the signed output
            // not well-formed.
            stage = "issuer-name"
            val renderedIssuer = issuerNameFormat.render(certificate)
            if (!CanonicalEmitter.isLegalXmlText(renderedIssuer)) return Outcome.Err(SigningError.SigningFailed(stage))
            val issuer = CanonicalEmitter.escapeText(renderedIssuer)

            // 1. The document digest is the digest of the root as written: the enveloped-signature
            //    transform removes the signature, which is the last child, leaving exactly these octets.
            stage = "digest"
            val documentDigest = sha1(root)

            // 2. SignedProperties. The digested form carries the in-scope namespace declarations.
            val signedPropertiesBody = signedPropertiesBody(certificate, signingTime, documentReferenceId, issuer)
            val signedPropertiesDigested =
                "<etsi:SignedProperties$NAMESPACES Id=\"$signedPropertiesId\">$signedPropertiesBody</etsi:SignedProperties>"
            val signedPropertiesInDocument =
                "<etsi:SignedProperties Id=\"$signedPropertiesId\">$signedPropertiesBody</etsi:SignedProperties>"
            val signedPropertiesDigest = sha1(signedPropertiesDigested.toByteArray(Charsets.UTF_8))

            // 3. KeyInfo: the leaf certificate and its RSA key value.
            val keyInfoBody = "<ds:X509Data><ds:X509Certificate>$certificateB64</ds:X509Certificate></ds:X509Data>" +
                "<ds:KeyValue><ds:RSAKeyValue><ds:Modulus>$modulusB64</ds:Modulus><ds:Exponent>$exponentB64</ds:Exponent></ds:RSAKeyValue></ds:KeyValue>"
            val keyInfoDigested = "<ds:KeyInfo$NAMESPACES Id=\"$keyInfoId\">$keyInfoBody</ds:KeyInfo>"
            val keyInfoInDocument = "<ds:KeyInfo Id=\"$keyInfoId\">$keyInfoBody</ds:KeyInfo>"
            val keyInfoDigest = sha1(keyInfoDigested.toByteArray(Charsets.UTF_8))

            // 4. SignedInfo: the canonicalization and signature methods and the three references.
            val signedInfoBody = "<ds:CanonicalizationMethod Algorithm=\"$C14N\"></ds:CanonicalizationMethod>" +
                "<ds:SignatureMethod Algorithm=\"${DS}rsa-sha1\"></ds:SignatureMethod>" +
                reference(
                    " Id=\"$signedPropertiesReferenceId\" Type=\"http://uri.etsi.org/01903#SignedProperties\" URI=\"#$signedPropertiesId\"",
                    "",
                    signedPropertiesDigest,
                ) +
                reference(" URI=\"#$keyInfoId\"", "", keyInfoDigest) +
                reference(
                    " Id=\"$documentReferenceId\" URI=\"#comprobante\"",
                    "<ds:Transforms><ds:Transform Algorithm=\"${DS}enveloped-signature\"></ds:Transform></ds:Transforms>",
                    documentDigest,
                )
            val signedInfoDigested = "<ds:SignedInfo$NAMESPACES Id=\"$signedInfoId\">$signedInfoBody</ds:SignedInfo>"
            val signedInfoInDocument = "<ds:SignedInfo Id=\"$signedInfoId\">$signedInfoBody</ds:SignedInfo>"

            stage = "signature"
            val signatureValue = Signature.getInstance("SHA1withRSA").run {
                initSign(identity.privateKey)
                update(signedInfoDigested.toByteArray(Charsets.UTF_8))
                BASE64.encodeToString(sign())
            }

            val signature = "<ds:Signature$NAMESPACES Id=\"$sigId\">" + signedInfoInDocument +
                "<ds:SignatureValue Id=\"$signatureValueId\">$signatureValue</ds:SignatureValue>" + keyInfoInDocument +
                "<ds:Object Id=\"$objectId\"><etsi:QualifyingProperties Target=\"#$sigId\">$signedPropertiesInDocument</etsi:QualifyingProperties></ds:Object>" +
                "</ds:Signature>"

            // The signed root is the input root with the signature spliced in before the closing tag.
            // Assembled from the original bytes, so the digested octets are the transmitted octets.
            val closeBytes = CLOSE.toByteArray(Charsets.UTF_8)
            val body = root.copyOfRange(0, root.size - closeBytes.size)
            val octets = document.prolog + body + signature.toByteArray(Charsets.UTF_8) + closeBytes
            return Outcome.Ok(SignedDocument.wrap(octets))
        } catch (e: GeneralSecurityException) {
            // The cause is deliberately dropped: a provider message can quote key or certificate details.
            return Outcome.Err(SigningError.SigningFailed(stage))
        } catch (e: RuntimeException) {
            // Same for a RuntimeException, which is how Keystore-backed providers report failure.
            return Outcome.Err(SigningError.SigningFailed(stage))
        }
    }

    /**
     * Whether [key] is the private half of [publicKey]. A key that exposes its modulus is compared
     * directly. An opaque key (a hardware-backed or Keystore key) cannot be inspected, so it signs
     * a throwaway value that the certificate's key must then verify.
     */
    private fun privateKeyMatches(key: PrivateKey, publicKey: RSAPublicKey): Boolean {
        if (key is RSAKey) return key.modulus == publicKey.modulus
        val probe = byteArrayOf(0x45, 0x61, 0x73, 0x79)
        val signature = Signature.getInstance("SHA1withRSA").run {
            initSign(key)
            update(probe)
            sign()
        }
        return Signature.getInstance("SHA1withRSA").run {
            initVerify(publicKey)
            update(probe)
            verify(signature)
        }
    }

    private fun signedPropertiesBody(
        certificate: X509Certificate,
        time: OffsetDateTime,
        documentReferenceId: String,
        issuer: String,
    ): String {
        val certificateDigest = sha1(certificate.encoded)
        val serial = certificate.serialNumber.toString()
        return "<etsi:SignedSignatureProperties><etsi:SigningTime>${time.format(SIGNING_TIME)}</etsi:SigningTime>" +
            "<etsi:SigningCertificate><etsi:Cert><etsi:CertDigest><ds:DigestMethod Algorithm=\"${DS}sha1\"></ds:DigestMethod>" +
            "<ds:DigestValue>$certificateDigest</ds:DigestValue></etsi:CertDigest>" +
            "<etsi:IssuerSerial><ds:X509IssuerName>$issuer</ds:X509IssuerName><ds:X509SerialNumber>$serial</ds:X509SerialNumber></etsi:IssuerSerial>" +
            "</etsi:Cert></etsi:SigningCertificate></etsi:SignedSignatureProperties>" +
            "<etsi:SignedDataObjectProperties><etsi:DataObjectFormat ObjectReference=\"#$documentReferenceId\">" +
            "<etsi:Description>contenido comprobante</etsi:Description><etsi:MimeType>text/xml</etsi:MimeType>" +
            "</etsi:DataObjectFormat></etsi:SignedDataObjectProperties>"
    }

    private fun reference(attributes: String, transforms: String, digest: String): String =
        "<ds:Reference$attributes>$transforms<ds:DigestMethod Algorithm=\"${DS}sha1\"></ds:DigestMethod>" +
            "<ds:DigestValue>$digest</ds:DigestValue></ds:Reference>"

    private fun sha1(bytes: ByteArray): String = BASE64.encodeToString(MessageDigest.getInstance("SHA-1").digest(bytes))

    /** A big-endian magnitude without the leading sign byte `BigInteger.toByteArray` may add. */
    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0].toInt() == 0) bytes.copyOfRange(1, bytes.size) else bytes
    }

    /**
     * A cheap structural check on [root], or `null` when it passes. It cannot prove canonical
     * form, since no parser exists in the portable subset; the proof is the identity test against
     * the JDK canonicalizer. It catches the mistakes that would silently produce a signature the
     * SRI rejects: invalid UTF-8, a root that is not `<factura ...>...</factura>`, a carriage
     * return, a self-closing tag, and a root start tag without the attribute `id="comprobante"`
     * that the document reference `URI="#comprobante"` resolves to.
     */
    private fun canonicalProblem(root: ByteArray): String? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(root))
                .toString()
        } catch (e: CharacterCodingException) {
            return "root is not valid UTF-8"
        }
        val opening = "<factura"
        if (!text.startsWith(opening) || text.length <= opening.length || (text[opening.length] != ' ' && text[opening.length] != '>')) {
            return "root does not start with <factura"
        }
        if (!text.endsWith(CLOSE)) return "root does not end with $CLOSE"
        if (text.indexOf('\r') >= 0) return "root contains a carriage return"
        if (hasSelfClosingTag(text)) return "root contains a self-closing tag"
        if (!rootCarriesComprobanteId(text)) return "root does not carry id=\"comprobante\", the target of the document reference"
        return null
    }

    /**
     * Whether the start tag of the root has an attribute named exactly `id` with the value exactly
     * `comprobante`. The tag is read attribute by attribute, honouring double-quoted values, so the
     * text `id="comprobante"` inside another attribute's value, an attribute whose name merely ends
     * in `id`, `ID` in another case, and an `id` on a child element are all refused. The document
     * reference is `URI="#comprobante"`: a root without this attribute leaves it unresolvable.
     */
    private fun rootCarriesComprobanteId(text: String): Boolean {
        var i = "<factura".length
        while (i < text.length) {
            while (i < text.length && text[i] == ' ') i++
            if (i >= text.length || text[i] == '>') return false
            val nameStart = i
            while (i < text.length && text[i] != '=' && text[i] != ' ' && text[i] != '>') i++
            if (i + 1 >= text.length || text[i] != '=' || text[i + 1] != '"') return false
            val name = text.substring(nameStart, i)
            val valueStart = i + 2
            val valueEnd = text.indexOf('"', valueStart)
            if (valueEnd < 0) return false
            if (name == "id" && text.substring(valueStart, valueEnd) == "comprobante") return true
            i = valueEnd + 1
        }
        return false
    }

    /** Scans tags, skipping quoted attribute values so a `/>` inside a value is not mistaken for a tag end. */
    private fun hasSelfClosingTag(text: String): Boolean {
        var inTag = false
        var inQuote = false
        for (i in text.indices) {
            val c = text[i]
            when {
                !inTag -> if (c == '<') inTag = true
                inQuote -> if (c == '"') inQuote = false
                c == '"' -> inQuote = true
                c == '>' -> {
                    if (text[i - 1] == '/') return true
                    inTag = false
                }
            }
        }
        return false
    }

    private companion object {
        const val REQUIRED_KEY_BITS = 2048
        const val ID_COUNT = 8
        const val CLOSE = "</factura>"
        const val DS = "http://www.w3.org/2000/09/xmldsig#"
        const val ETSI = "http://uri.etsi.org/01903/v1.3.2#"
        const val C14N = "http://www.w3.org/TR/2001/REC-xml-c14n-20010315"

        /** Namespace declarations in canonical order: sorted by prefix, `ds` before `etsi`. */
        const val NAMESPACES = " xmlns:ds=\"$DS\" xmlns:etsi=\"$ETSI\""

        val BASE64: Base64.Encoder = Base64.getEncoder()
        val SIGNING_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
    }
}
