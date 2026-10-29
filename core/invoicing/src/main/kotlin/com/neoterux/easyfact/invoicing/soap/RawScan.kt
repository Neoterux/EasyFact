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

/** A half-open range `[start, endExclusive)` of byte indices into the array that was scanned. */
data class ByteSpan(val start: Int, val endExclusive: Int)

/**
 * One top-level `autorizacion` element of a response.
 *
 * @property element the element from the `<` of its start tag to just after the `>` of its end tag
 * @property comprobanteContent what lies between its direct `comprobante` child's start and end
 *   tags, exactly as received (a CDATA section with its delimiters, or escaped text); an empty
 *   span for a self-closing `<comprobante/>`; `null` when there is no such child
 * @property inheritsNamespace whether anything inside [element], the element itself, a descendant or
 *   an attribute, depends on a namespace declaration that lies outside the slice; when `false` the
 *   slice is a standalone XML element
 */
data class AuthorizationSlice(
    val element: ByteSpan,
    val comprobanteContent: ByteSpan?,
    val inheritsNamespace: Boolean,
)

/**
 * Finds, in the raw bytes of a response, each top-level `autorizacion` element and the content of
 * its `comprobante`, without parsing and without copying.
 *
 * D-41: the authorized document must reach the deliverable and the audit trail exactly as the SRI
 * sent it. A parser cannot provide that. CDATA and escaped text decode to the same string, so
 * which form the SRI used is lost, and XML line-end normalisation rewrites CR and CR LF, so the
 * bytes of a signed document that contains them cannot be recovered. Re-serializing a parsed tree
 * is not a way back either (Phase 2 measured that a round trip changes bytes inside the digested
 * region). The scanner therefore returns spans into the caller's bytes and never builds a copy.
 *
 * Scanning is byte-level and exact. Every markup delimiter is ASCII, and UTF-8 never encodes a
 * non-ASCII character with a byte below 0x80, so an ASCII delimiter byte is always a delimiter.
 * `<?` ... `?>`, `<!--` ... `-->` and `<![CDATA[` ... `]]>` are skipped as opaque, so markup that
 * merely appears inside them (the signed comprobante in CDATA may itself contain an
 * `<autorizacion>` literal) is never seen. Quoted attribute values are skipped, so a `>` inside
 * one does not end a tag.
 *
 * A slice is a standalone XML element only when [AuthorizationSlice.inheritsNamespace] is `false`.
 * That holds for every sample in the SRI's technical sheet (v2.34, pages 18-19): `autorizacion`
 * is unprefixed and no ancestor declares a default namespace. If the SRI ever changes that, the
 * flag says so and the caller must fail loudly rather than hand on a slice that cannot stand
 * alone. The flag is set when any element or attribute name inside the slice uses a prefix that is
 * not declared inside the slice, or when an unprefixed element falls under a non-empty default
 * namespace declared on an ancestor of the slice. A declaration on the element itself or on one of
 * its descendants is carried by the slice, so it does not set the flag; `xmlns=""` on an ancestor
 * declares no namespace and does not set it either. The prefix `xml` is bound by the XML
 * specification and never counts.
 *
 * This is not a validator. It returns `null` for the constructs it cannot scan past (an
 * unterminated CDATA section, comment, processing instruction or tag, an end tag that does not
 * match, an element still open at the end, a document type declaration, nesting deeper than
 * [MAX_ELEMENT_DEPTH]); the parser (03-07) turns that into `MalformedReason.NOT_WELL_FORMED` and
 * turns a disagreement between this scan's count and its own into `RAW_SLICE_MISMATCH`.
 */
object RawScan {

    /**
     * The deepest element nesting accepted. A real response nests about ten levels; the cap keeps
     * a hostile body of nothing but start tags from growing the element stack without bound
     * inside the size the guard allows.
     */
    const val MAX_ELEMENT_DEPTH = 64

    private const val AUTHORIZATION = "autorizacion"
    private const val COMPROBANTE = "comprobante"
    private const val XML_PREFIX = "xml"

    private val LT = '<'.code.toByte()
    private val GT = '>'.code.toByte()
    private val SLASH = '/'.code.toByte()
    private val EQUALS = '='.code.toByte()
    private val SINGLE_QUOTE = '\''.code.toByte()
    private val DOUBLE_QUOTE = '"'.code.toByte()

    private val PI_START = ascii("<?")
    private val PI_END = ascii("?>")
    private val COMMENT_START = ascii("<!--")
    private val COMMENT_END = ascii("-->")
    private val CDATA_START = ascii("<![CDATA[")
    private val CDATA_END = ascii("]]>")
    private val DECLARATION_START = ascii("<!")
    private val END_TAG_START = ascii("</")

    /**
     * An open element. [defaultNamespace] is `null` when the element declares no default namespace,
     * `true` when it declares a non-empty one and `false` for `xmlns=""`, which undeclares it.
     * [prefixes] are the prefixes the element declares with `xmlns:prefix`.
     */
    private class Frame(val qualifiedName: String, val defaultNamespace: Boolean?, val prefixes: Set<String>)

    /**
     * The `autorizacion` elements of [bytes] in document order, or `null` when the bytes cannot be
     * scanned. An empty list means the bytes scanned cleanly and contain none.
     */
    fun authorizations(bytes: ByteArray): List<AuthorizationSlice>? {
        val slices = ArrayList<AuthorizationSlice>()
        val stack = ArrayList<Frame>()

        // The authorization currently open, as the stack index of its frame (-1 when none).
        var openIndex = -1
        var openStart = 0
        var openInherits = false
        // Its comprobante child: the stack index being captured (-1 when none) and the first result.
        var capturingIndex = -1
        var contentStart = 0
        var content: ByteSpan? = null

        var i = 0
        while (i < bytes.size) {
            if (bytes[i] != LT) {
                i++
                continue
            }
            when {
                startsWith(bytes, i, PI_START) -> {
                    val end = indexOf(bytes, PI_END, i + PI_START.size)
                    if (end < 0) return null
                    i = end + PI_END.size
                }
                startsWith(bytes, i, COMMENT_START) -> {
                    val end = indexOf(bytes, COMMENT_END, i + COMMENT_START.size)
                    if (end < 0) return null
                    i = end + COMMENT_END.size
                }
                startsWith(bytes, i, CDATA_START) -> {
                    val end = indexOf(bytes, CDATA_END, i + CDATA_START.size)
                    if (end < 0) return null
                    i = end + CDATA_END.size
                }
                startsWith(bytes, i, DECLARATION_START) -> return null
                startsWith(bytes, i, END_TAG_START) -> {
                    var j = i + END_TAG_START.size
                    val nameStart = j
                    while (j < bytes.size && !isNameEnd(bytes[j])) j++
                    if (j == nameStart) return null
                    val name = latin1(bytes, nameStart, j)
                    while (j < bytes.size && isWhitespace(bytes[j])) j++
                    if (j >= bytes.size || bytes[j] != GT) return null
                    val tagEnd = j + 1

                    if (stack.isEmpty() || stack.last().qualifiedName != name) return null
                    stack.removeAt(stack.size - 1)
                    val closedIndex = stack.size

                    if (closedIndex == capturingIndex) {
                        content = ByteSpan(contentStart, i)
                        capturingIndex = -1
                    }
                    if (closedIndex == openIndex) {
                        slices.add(AuthorizationSlice(ByteSpan(openStart, tagEnd), content, openInherits))
                        openIndex = -1
                        content = null
                        capturingIndex = -1
                    }
                    i = tagEnd
                }
                else -> {
                    var j = i + 1
                    val nameStart = j
                    while (j < bytes.size && !isNameEnd(bytes[j])) j++
                    if (j == nameStart) return null
                    val name = latin1(bytes, nameStart, j)

                    var defaultNamespace: Boolean? = null
                    val declaredPrefixes = HashSet<String>()
                    val attributeNames = ArrayList<String>()
                    var selfClosing = false
                    while (true) {
                        while (j < bytes.size && isWhitespace(bytes[j])) j++
                        if (j >= bytes.size) return null
                        val c = bytes[j]
                        if (c == GT) {
                            j++
                            break
                        }
                        if (c == SLASH) {
                            if (j + 1 >= bytes.size || bytes[j + 1] != GT) return null
                            selfClosing = true
                            j += 2
                            break
                        }
                        val attributeStart = j
                        while (j < bytes.size && !isNameEnd(bytes[j]) && bytes[j] != EQUALS) j++
                        if (j == attributeStart) return null
                        val attribute = latin1(bytes, attributeStart, j)
                        while (j < bytes.size && isWhitespace(bytes[j])) j++
                        if (j >= bytes.size || bytes[j] != EQUALS) return null
                        j++
                        while (j < bytes.size && isWhitespace(bytes[j])) j++
                        if (j >= bytes.size) return null
                        val quote = bytes[j]
                        if (quote != SINGLE_QUOTE && quote != DOUBLE_QUOTE) return null
                        val close = indexOf(bytes, quote, j + 1)
                        if (close < 0) return null
                        val valueIsEmpty = close == j + 1
                        j = close + 1
                        when {
                            attribute == "xmlns" -> defaultNamespace = !valueIsEmpty
                            attribute.startsWith("xmlns:") -> declaredPrefixes.add(attribute.substring("xmlns:".length))
                            else -> attributeNames.add(attribute)
                        }
                    }
                    val tagEnd = j

                    val localName = name.substringAfterLast(':')
                    val frameIndex = stack.size

                    // Whether this element or one of its attributes needs a declaration outside the slice it is in.
                    // A new slice starts at this element; an element inside an open one belongs to that slice.
                    val startsSlice = openIndex < 0 && localName == AUTHORIZATION
                    if (startsSlice || openIndex >= 0) {
                        val needsOutside = needsDeclarationOutsideSlice(
                            name, attributeNames, defaultNamespace, declaredPrefixes, stack, if (startsSlice) frameIndex else openIndex,
                        )
                        if (startsSlice) openInherits = needsOutside else if (needsOutside) openInherits = true
                    }

                    if (startsSlice) {
                        val inherits = openInherits
                        if (selfClosing) {
                            slices.add(AuthorizationSlice(ByteSpan(i, tagEnd), null, inherits))
                        } else {
                            openIndex = frameIndex
                            openStart = i
                            content = null
                            capturingIndex = -1
                        }
                    } else if (openIndex >= 0 && frameIndex == openIndex + 1 && localName == COMPROBANTE && content == null && capturingIndex < 0) {
                        if (selfClosing) {
                            content = ByteSpan(tagEnd, tagEnd)
                        } else {
                            capturingIndex = frameIndex
                            contentStart = tagEnd
                        }
                    }

                    if (!selfClosing) {
                        if (stack.size >= MAX_ELEMENT_DEPTH) return null
                        stack.add(Frame(name, defaultNamespace, declaredPrefixes))
                    }
                    i = tagEnd
                }
            }
        }
        return if (stack.isEmpty()) slices else null
    }

    /**
     * Whether an element called [name] with the given non-declaration [attributes] and its own declarations
     * ([defaultNamespace], [declaredPrefixes]) depends on a namespace declaration outside the slice whose
     * root is the frame at [sliceIndex]. [stack] holds the open ancestors; those at [sliceIndex] and above
     * belong to the slice, so what they declare travels with it.
     */
    private fun needsDeclarationOutsideSlice(
        name: String,
        attributes: List<String>,
        defaultNamespace: Boolean?,
        declaredPrefixes: Set<String>,
        stack: List<Frame>,
        sliceIndex: Int,
    ): Boolean {
        fun prefixDeclaredInSlice(prefix: String): Boolean =
            prefix == XML_PREFIX || prefix in declaredPrefixes ||
                (sliceIndex until stack.size).any { prefix in stack[it].prefixes }

        val colon = name.indexOf(':')
        if (colon >= 0) {
            if (!prefixDeclaredInSlice(name.substring(0, colon))) return true
        } else if (defaultNamespace == null) {
            // Unprefixed and no declaration of its own: the nearest ancestor default decides.
            for (index in stack.indices.reversed()) {
                val declared = stack[index].defaultNamespace ?: continue
                if (index < sliceIndex && declared) return true
                break
            }
        }
        return attributes.any { attribute ->
            val attributeColon = attribute.indexOf(':')
            attributeColon >= 0 && !prefixDeclaredInSlice(attribute.substring(0, attributeColon))
        }
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun latin1(bytes: ByteArray, from: Int, to: Int): String = String(bytes, from, to - from, Charsets.ISO_8859_1)

    private fun isWhitespace(byte: Byte): Boolean =
        byte == 0x20.toByte() || byte == 0x09.toByte() || byte == 0x0A.toByte() || byte == 0x0D.toByte()

    /** Ends a tag or attribute name: whitespace, `/` or `>`. */
    private fun isNameEnd(byte: Byte): Boolean = isWhitespace(byte) || byte == SLASH || byte == GT

    private fun startsWith(bytes: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset + prefix.size > bytes.size) return false
        for (index in prefix.indices) {
            if (bytes[offset + index] != prefix[index]) return false
        }
        return true
    }

    /** First index at or after [from] where [needle] starts, or -1. */
    private fun indexOf(bytes: ByteArray, needle: ByteArray, from: Int): Int {
        val last = bytes.size - needle.size
        var index = from
        while (index <= last) {
            if (bytes[index] == needle[0] && startsWith(bytes, index, needle)) return index
            index++
        }
        return -1
    }

    /** First index at or after [from] holding [value], or -1. */
    private fun indexOf(bytes: ByteArray, value: Byte, from: Int): Int {
        var index = from
        while (index < bytes.size) {
            if (bytes[index] == value) return index
            index++
        }
        return -1
    }
}
