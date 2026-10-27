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

package com.neoterux.easyfact.invoicing.xml

/**
 * Writes XML that is already in inclusive canonical form (`REC-xml-c14n-20010315`), so that
 * canonicalization is the identity transform on the bytes and a signature can be computed over
 * exactly what is later transmitted.
 *
 * That is why a general-purpose serializer cannot be used: Android's `XmlSerializer` emits
 * single-quoted attributes, self-closing empty elements, numeric character references and raw
 * CR, none of which are canonical. This writer instead
 *
 * - always writes an explicit end tag, never a self-closing form;
 * - writes attributes in a fixed order and quotes them with `"`;
 * - emits LF only and no whitespace between elements;
 * - escapes text as `&amp;` `&lt;` `&gt;` and `&#xD;`, and attribute values as `&amp;` `&lt;`
 *   `&quot;` `&#x9;` `&#xA;` and `&#xD;`;
 * - emits non-ASCII characters raw and encodes to UTF-8 exactly once, in [document].
 *
 * Only un-prefixed element and attribute names are accepted. Canonical order for prefixed
 * attributes depends on namespace URIs, and the factura vocabulary has none, so a colon is
 * rejected rather than silently written in an order that may not be canonical. The signature's
 * namespaced elements are written by the signer, which knows its own fixed layout.
 *
 * Whether the output really is canonical is proven by an independent oracle in the test source
 * set, not by this class.
 *
 * Misuse (text outside an element, an unbalanced close, a second root) throws
 * [IllegalStateException]; a name or character that XML 1.0 does not allow throws
 * [IllegalArgumentException]. Both are programming errors in the typed layer above. User-entered
 * text should be checked with [isLegalXmlText] before it reaches the emitter.
 */
class CanonicalEmitter {

    private val out = StringBuilder()
    private val open = ArrayList<String>()
    private var rootWritten = false
    private var rootClosed = false

    /** Starts an element. [attributes] are sorted by name, so the caller's order does not matter. */
    fun open(name: String, attributes: Map<String, String> = emptyMap()): CanonicalEmitter {
        check(!rootClosed) { "the document element is already closed" }
        requireName(name)
        out.append('<').append(name)
        for (key in attributes.keys.sorted()) {
            requireName(key)
            val value = attributes.getValue(key)
            requireLegal(value, "attribute value of '$key'")
            out.append(' ').append(key).append("=\"").append(escapeAttribute(value)).append('"')
        }
        out.append('>')
        open.add(name)
        rootWritten = true
        return this
    }

    /** Writes character data inside the current element. */
    fun text(value: String): CanonicalEmitter {
        check(open.isNotEmpty()) { "text outside an element" }
        requireLegal(value, "text")
        out.append(escapeText(value))
        return this
    }

    /** Ends the current element with an explicit end tag. */
    fun close(): CanonicalEmitter {
        check(open.isNotEmpty()) { "no open element to close" }
        out.append("</").append(open.removeAt(open.size - 1)).append('>')
        if (open.isEmpty()) rootClosed = true
        return this
    }

    /**
     * Finishes the document. With [prolog] the XML declaration and one LF come first; they are
     * kept apart from the root because the signature covers the root only.
     */
    fun document(prolog: Boolean = true): CanonicalDocument {
        check(rootWritten) { "no document element was written" }
        check(open.isEmpty()) { "unclosed element '${open.last()}'" }
        val declaration = if (prolog) DECLARATION.toByteArray(Charsets.UTF_8) else ByteArray(0)
        return CanonicalDocument(declaration, out.toString().toByteArray(Charsets.UTF_8))
    }

    private fun requireName(name: String) {
        require(name.isNotEmpty() && name[0] in NAME_START && name.all { it in NAME_START || it in '0'..'9' || it == '-' || it == '.' }) {
            "not an un-prefixed XML name: '$name'"
        }
    }

    private fun requireLegal(value: String, what: String) {
        require(isLegalXmlText(value)) { "$what contains a character that XML 1.0 does not allow" }
    }

    companion object {
        private const val DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        private val NAME_START = ('A'..'Z') + ('a'..'z') + '_'

        /**
         * Whether every character of [value] is legal in XML 1.0: TAB, LF, CR, and the ranges
         * `U+0020..U+D7FF`, `U+E000..U+FFFD` and `U+10000..U+10FFFF`. An unpaired surrogate is
         * not legal.
         */
        fun isLegalXmlText(value: String): Boolean {
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val legal = when {
                    c == '\t' || c == '\n' || c == '\r' -> true
                    c in ' '..'퟿' -> true
                    c in ''..'�' -> true
                    Character.isHighSurrogate(c) -> {
                        val paired = i + 1 < value.length && Character.isLowSurrogate(value[i + 1])
                        if (paired) i++
                        paired
                    }
                    else -> false
                }
                if (!legal) return false
                i++
            }
            return true
        }

        /** Escapes [value] for element content in inclusive C14N. */
        fun escapeText(value: String): String {
            val b = StringBuilder(value.length + 8)
            for (c in value) {
                when (c) {
                    '&' -> b.append("&amp;")
                    '<' -> b.append("&lt;")
                    '>' -> b.append("&gt;")
                    '\r' -> b.append("&#xD;")
                    else -> b.append(c)
                }
            }
            return b.toString()
        }

        private fun escapeAttribute(value: String): String {
            val b = StringBuilder(value.length + 8)
            for (c in value) {
                when (c) {
                    '&' -> b.append("&amp;")
                    '<' -> b.append("&lt;")
                    '"' -> b.append("&quot;")
                    '\t' -> b.append("&#x9;")
                    '\n' -> b.append("&#xA;")
                    '\r' -> b.append("&#xD;")
                    else -> b.append(c)
                }
            }
            return b.toString()
        }
    }
}
