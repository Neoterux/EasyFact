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
 * The octets of one XML document, written already in inclusive-C14N form, split into the part
 * that is signed and the part that is not.
 *
 * - [prolog] is the XML declaration followed by one LF, or empty. It is not part of the signed
 *   bytes: canonicalization drops the declaration, so the signer digests [root] alone.
 * - [root] is the document element, from `<` of the start tag to `>` of the end tag.
 *
 * The split is structural, not parsed: the emitter knows where it wrote the declaration, so no
 * parser is needed and none exists in the portable subset. Every accessor returns a copy, so a
 * caller cannot change bytes that a signature will later depend on.
 */
class CanonicalDocument internal constructor(prolog: ByteArray, root: ByteArray) {

    private val prologBytes: ByteArray = prolog.copyOf()
    private val rootBytes: ByteArray = root.copyOf()

    /** The declaration and its trailing LF, or an empty array. */
    val prolog: ByteArray get() = prologBytes.copyOf()

    /** The document element. */
    val root: ByteArray get() = rootBytes.copyOf()

    /** The prolog followed by the root: the octets as they would be transmitted unsigned. */
    fun octets(): ByteArray = prologBytes + rootBytes

    /** Never renders the content, which may carry customer data. */
    override fun toString(): String = "CanonicalDocument(prolog=${prologBytes.size} bytes, root=${rootBytes.size} bytes)"
}
