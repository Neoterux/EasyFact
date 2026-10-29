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

package com.neoterux.easyfact.invoicing.soap

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Why response bytes were refused. [ResponseGuard] raises the first six; the parser raises
 * [NOT_WELL_FORMED] and [RAW_SLICE_MISMATCH] (plan 03-07), and they live here so that every
 * refusal a caller can see is one type.
 */
enum class MalformedReason {
    /** No bytes at all. */
    EMPTY,

    /** More than [ResponseGuard.MAX_RESPONSE_BYTES]. */
    TOO_LARGE,

    /** A zero byte, which XML forbids and which betrays UTF-16 or UTF-32 without a mark. */
    NUL_BYTE,

    /** A UTF-16 or UTF-32 byte-order mark, invalid UTF-8, or a declared encoding other than UTF-8. */
    NOT_UTF8,

    /** The ASCII sequence `<!DOCTYPE`: this codec never accepts a document type declaration. */
    DOCTYPE,

    /** The ASCII sequence `<!ENTITY`: this codec never accepts an entity declaration. */
    ENTITY_DECLARATION,

    /** Raised by the parser: the bytes passed the guard but are not well-formed XML. */
    NOT_WELL_FORMED,

    /** Raised by the parser: the raw-slice count disagrees with the parsed element count. */
    RAW_SLICE_MISMATCH,
}

/**
 * The byte-level gate every response passes before any parser sees it.
 *
 * This guard is the XXE and entity-expansion control, and it deliberately depends on no parser
 * feature. Which JAXP `setFeature` names Android's implementations accept is unobserved
 * (research assumption A4), so a control that relied on `disallow-doctype-decl` could throw on
 * a device or silently not apply. Refusing the ASCII sequences `<!DOCTYPE` and `<!ENTITY` on
 * the bytes behaves identically on the JVM and on ART. The parser hardening in plan 03-07 is
 * defence in depth, not the control.
 *
 * The whole-body search is safe because the canonical writer escapes `<` in text and never emits
 * a document type declaration, so a legitimate signed comprobante inside a CDATA section cannot
 * contain either sequence; a rejection message that quotes one arrives escaped as `&lt;`.
 *
 * It is not a well-formedness check and does not claim to be.
 */
object ResponseGuard {

    /** Largest single comprobante, in bytes: 320 Kb per the SRI's technical sheet v2.34, page 20. */
    const val COMPROBANTE_MAX_BYTES = 320 * 1024

    /**
     * An escaped comprobante is larger than its CDATA form (`<` becomes `&lt;`, and so on). Two is
     * a stated allowance, not a measurement.
     */
    const val ESCAPING_ALLOWANCE = 2

    /**
     * Resubmitting a rejected document adds `autorizacion` entries for the same key. Twelve is a
     * stated allowance, not an observed count.
     */
    const val MAX_AUTHORIZATION_ENTRIES = 12

    /**
     * The largest response accepted: 8 MiB (8,388,608 bytes).
     *
     * The product it must clear is [COMPROBANTE_MAX_BYTES] x [ESCAPING_ALLOWANCE] x
     * [MAX_AUTHORIZATION_ENTRIES] = 327,680 x 2 x 12 = 7,864,320 bytes. 8 MiB sits above that
     * product with room for the envelope and the message lists, and is nowhere near the 64 KiB a
     * single-document cap would suggest, which would refuse a legitimate response carrying
     * several large entries.
     */
    const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024

    private const val DECLARATION_SCAN_LIMIT = 1024
    private const val DECODE_CHUNK_CHARS = 8192
    private const val UTF8_NAME = "UTF-8"

    private val DOCTYPE_SEQUENCE: ByteArray = "<!DOCTYPE".toByteArray(Charsets.US_ASCII)
    private val ENTITY_SEQUENCE: ByteArray = "<!ENTITY".toByteArray(Charsets.US_ASCII)
    private val XML_DECLARATION_START: ByteArray = "<?xml".toByteArray(Charsets.US_ASCII)
    private val DECLARATION_END: ByteArray = "?>".toByteArray(Charsets.US_ASCII)
    private val UTF8_BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val ENCODING_PSEUDO_ATTRIBUTE = Regex("""\bencoding\s*=\s*(["'])(.*?)\1""")

    private val REFUSED_MARKS: List<ByteArray> = listOf(
        byteArrayOf(0x00, 0x00, 0xFE.toByte(), 0xFF.toByte()),
        byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x00),
        byteArrayOf(0xFE.toByte(), 0xFF.toByte()),
        byteArrayOf(0xFF.toByte(), 0xFE.toByte()),
    )

    /** The reason [bytes] must not reach a parser, or `null` when they may. */
    fun check(bytes: ByteArray): MalformedReason? {
        if (bytes.isEmpty()) return MalformedReason.EMPTY
        if (bytes.size > MAX_RESPONSE_BYTES) return MalformedReason.TOO_LARGE
        if (REFUSED_MARKS.any { startsWith(bytes, 0, it) }) return MalformedReason.NOT_UTF8
        if (bytes.contains(0.toByte())) return MalformedReason.NUL_BYTE
        if (!isStrictUtf8(bytes)) return MalformedReason.NOT_UTF8
        if (declaresAnotherEncoding(bytes)) return MalformedReason.NOT_UTF8
        if (indexOf(bytes, DOCTYPE_SEQUENCE, 0, bytes.size) >= 0) return MalformedReason.DOCTYPE
        if (indexOf(bytes, ENTITY_SEQUENCE, 0, bytes.size) >= 0) return MalformedReason.ENTITY_DECLARATION
        return null
    }

    /** Decodes in fixed-size chunks with errors reported, so a bad sequence never becomes U+FFFD. */
    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(DECODE_CHUNK_CHARS)
        while (true) {
            val result = decoder.decode(input, output, true)
            if (result.isError) return false
            if (result.isUnderflow) break
            output.clear()
        }
        output.clear()
        return !decoder.flush(output).isError
    }

    /**
     * Whether the XML declaration at the very start (after an optional UTF-8 mark) names an
     * encoding other than UTF-8. Only that declaration counts: one inside a CDATA comprobante
     * describes the comprobante, not this response.
     */
    private fun declaresAnotherEncoding(bytes: ByteArray): Boolean {
        val start = if (startsWith(bytes, 0, UTF8_BOM)) UTF8_BOM.size else 0
        if (!startsWith(bytes, start, XML_DECLARATION_START)) return false
        val afterName = start + XML_DECLARATION_START.size
        if (afterName >= bytes.size || !isXmlWhitespace(bytes[afterName])) return false
        val limit = minOf(bytes.size, start + DECLARATION_SCAN_LIMIT)
        val end = indexOf(bytes, DECLARATION_END, afterName, limit)
        // An unterminated declaration is the parser's to refuse as not well-formed.
        if (end < 0) return false
        val declaration = String(bytes, afterName, end - afterName, Charsets.ISO_8859_1)
        val declared = ENCODING_PSEUDO_ATTRIBUTE.find(declaration)?.groupValues?.get(2) ?: return false
        return declared.uppercase(Locale.ROOT) != UTF8_NAME
    }

    private fun isXmlWhitespace(byte: Byte): Boolean =
        byte == 0x20.toByte() || byte == 0x09.toByte() || byte == 0x0A.toByte() || byte == 0x0D.toByte()

    private fun startsWith(bytes: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > bytes.size) return false
        for (index in prefix.indices) {
            if (bytes[offset + index] != prefix[index]) return false
        }
        return true
    }

    /** First index in `[from, to)` where [needle] starts and fits entirely before [to], or -1. */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int, to: Int): Int {
        val last = to - needle.size
        var index = from
        while (index <= last) {
            if (haystack[index] == needle[0] && startsWith(haystack, index, needle)) return index
            index++
        }
        return -1
    }
}
