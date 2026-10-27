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

import java.nio.charset.Charset
import java.security.cert.X509Certificate
import javax.security.auth.x500.X500Principal

/**
 * How the issuer's distinguished name is written into `X509IssuerName` inside `SigningCertificate`.
 *
 * **This is the first item on the diagnostic checklist for SRI error 39.** For an issuer that
 * carries `organizationIdentifier` (OID 2.5.4.97), the JDK's RFC 2253 rendering writes the value
 * as a hex DER string (`2.5.4.97=#130f...`), while RFC 1779 and the deprecated `getIssuerDN()`
 * write decoded text. Which of these the SRI expects is not documented and the single sample in
 * the technical sheet cannot discriminate, because its issuer uses only well-known attribute
 * types. A research probe on the JDK measured all three renderings for a synthetic issuer;
 * [IssuerNameFormats.ALL] lets a probe print every one for a given certificate before the first
 * submission, so a rejection can be answered by changing one constructor argument.
 *
 * The rendering is escaped for XML text by the signer; implementations return the raw name.
 */
fun interface IssuerNameFormat {
    fun render(certificate: X509Certificate): String
}

/**
 * The JDK's RFC 2253 rendering: the behavior the reference signer prototype had, and the default
 * because it is what the first probe submission tries.
 */
object Rfc2253Jdk : IssuerNameFormat {
    override fun render(certificate: X509Certificate): String = certificate.issuerX500Principal.name
}

/** The JDK's RFC 1779 rendering: comma plus space separators, unknown OIDs as `OID.x.y.z=decoded`. */
object Rfc1779Jdk : IssuerNameFormat {
    override fun render(certificate: X509Certificate): String =
        certificate.issuerX500Principal.getName(X500Principal.RFC1779)
}

/**
 * RFC 2253 ordering and punctuation (most specific attribute first, comma separated, no spaces),
 * but a value the JDK would write as a hex DER string (`#13...`, `#0c...`) is written as the text
 * it encodes. Every other value is passed through unchanged, so for an issuer with only
 * well-known attribute types this equals [Rfc2253Jdk].
 */
object DecodedOidRfc2253 : IssuerNameFormat {

    override fun render(certificate: X509Certificate): String =
        decodeHexValues(certificate.issuerX500Principal.name)

    /** Rewrites each `#<hex DER string>` attribute value in the RFC 2253 name [name] as escaped text. */
    internal fun decodeHexValues(name: String): String {
        val out = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            // The attribute type runs to the first '='.
            val equals = name.indexOf('=', i)
            if (equals < 0) {
                out.append(name, i, name.length)
                break
            }
            out.append(name, i, equals + 1)
            i = equals + 1
            // The value runs to the next unescaped ',' or '+'.
            val start = i
            while (i < name.length && name[i] != ',' && name[i] != '+') {
                i += if (name[i] == '\\' && i + 1 < name.length) 2 else 1
            }
            val value = name.substring(start, i)
            out.append(decodeValue(value) ?: value)
            if (i < name.length) {
                out.append(name[i])
                i++
            }
        }
        return out.toString()
    }

    /** The escaped text a `#<hex>` value encodes, or `null` when [value] is not one this can decode. */
    private fun decodeValue(value: String): String? {
        if (!value.startsWith("#")) return null
        val der = fromHex(value.substring(1)) ?: return null
        val content = derStringContent(der) ?: return null
        return escapeRfc2253(String(content.bytes, content.charset))
    }

    private class Content(val bytes: ByteArray, val charset: Charset)

    /** The content octets and character set of a DER-encoded string, or `null` for anything else. */
    private fun derStringContent(der: ByteArray): Content? {
        if (der.size < 2) return null
        val charset = when (der[0].toInt() and 0xFF) {
            0x0C -> Charsets.UTF_8 // UTF8String
            0x12, 0x13, 0x16, 0x1A -> Charsets.US_ASCII // Numeric, Printable, IA5 and Visible strings
            0x14 -> Charsets.ISO_8859_1 // T61String
            0x1E -> Charsets.UTF_16BE // BMPString
            else -> return null
        }
        val first = der[1].toInt() and 0xFF
        var length: Int
        var offset = 2
        if (first < 0x80) {
            length = first
        } else {
            val lengthBytes = first and 0x7F
            if (lengthBytes == 0 || lengthBytes > 3 || der.size < 2 + lengthBytes) return null
            length = 0
            for (k in 0 until lengthBytes) length = (length shl 8) or (der[2 + k].toInt() and 0xFF)
            offset += lengthBytes
        }
        if (offset + length != der.size) return null
        return Content(der.copyOfRange(offset, der.size), charset)
    }

    private fun fromHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val bytes = ByteArray(hex.length / 2)
        for (k in bytes.indices) {
            val high = Character.digit(hex[2 * k], 16)
            val low = Character.digit(hex[2 * k + 1], 16)
            if (high < 0 || low < 0) return null
            bytes[k] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    /** RFC 2253 section 2.4 escaping of an attribute value. */
    private fun escapeRfc2253(text: String): String {
        val out = StringBuilder(text.length + 4)
        for ((index, c) in text.withIndex()) {
            val special = c in ",+\"\\<>;" ||
                (index == 0 && (c == '#' || c == ' ')) ||
                (index == text.length - 1 && c == ' ')
            if (special) out.append('\\')
            out.append(c)
        }
        return out.toString()
    }
}

/** Every rendering a probe can try, by name, in the order they should be tried. */
object IssuerNameFormats {
    val ALL: List<Pair<String, IssuerNameFormat>> = listOf(
        "Rfc2253Jdk" to Rfc2253Jdk,
        "DecodedOidRfc2253" to DecodedOidRfc2253,
        "Rfc1779Jdk" to Rfc1779Jdk,
    )
}
