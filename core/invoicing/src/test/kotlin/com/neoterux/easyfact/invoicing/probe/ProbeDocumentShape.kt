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

package com.neoterux.easyfact.invoicing.probe

import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The record of what shape a verdict from the test environment is about.
 *
 * The probe document is built by the writer, which today covers only the mandatory skeleton of the
 * 1.1.0 schema. A later widening adds blocks, so the single real submission the probe makes carries
 * a subset, and nobody reading the result later should have to guess which subset. [elementPaths]
 * lists the element names of a document as slash-joined paths, in document order, each path once.
 * It records names only, never a value, so its output is safe to commit and to print.
 *
 * Order matters. The schema is an `xsd:sequence`, so a reordered block is a different document;
 * the comparison therefore fails on a reorder as well as on an added or removed element. A
 * repeated path is listed once, at its first occurrence, which means a second `detalle` is not
 * visible here: that is stated in the write-up as an unexercised variation.
 *
 * The XML-DSig `Signature` element is skipped together with its subtree. It is the envelope's
 * signature, not a block of the factura, and skipping it lets the same list describe the unsigned
 * emitter output (what CI pins) and the signed octets (what the submit path asserts).
 */
object ProbeDocumentShape {

    private const val RECORDED_SHAPE_RESOURCE = "/fixtures/sri/probe-document-element-set.txt"

    /** The ordered, de-duplicated element paths of [octets], with the `Signature` subtree left out. */
    fun elementPaths(octets: ByteArray): List<String> {
        val root = ProbeEnvironmentGuard.parseHardened(octets).documentElement
        val paths = LinkedHashSet<String>()

        fun walk(element: Element, prefix: String) {
            val path = if (prefix.isEmpty()) element.localName else "$prefix/${element.localName}"
            paths += path
            var next = element.firstChild
            while (next != null) {
                if (next.nodeType == Node.ELEMENT_NODE) {
                    val child = next as Element
                    val isSignature = child.localName == "Signature" && child.namespaceURI == SIGNATURE_NAMESPACE
                    if (!isSignature) walk(child, path)
                }
                next = next.nextSibling
            }
        }
        walk(root, "")
        return paths.toList()
    }

    /** The paths recorded in the committed element-set file, comments and blank lines dropped. */
    fun recordedPaths(): List<String> {
        val stream = ProbeDocumentShape::class.java.getResourceAsStream(RECORDED_SHAPE_RESOURCE)
            ?: error("the recorded element set $RECORDED_SHAPE_RESOURCE is not on the test classpath")
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }
    }

    /**
     * @throws IllegalStateException when the document's element paths differ from the recorded
     * set, naming the elements added, the elements missing, or the fact that only the order differs
     */
    fun assertMatchesRecordedShape(octets: ByteArray) {
        val actual = elementPaths(octets)
        val recorded = recordedPaths()
        if (actual == recorded) return
        val added = actual.filter { it !in recorded }
        val missing = recorded.filter { it !in actual }
        val detail = when {
            added.isNotEmpty() || missing.isNotEmpty() -> "added: $added; missing: $missing"
            else -> "the same elements in a different order"
        }
        throw IllegalStateException("the document does not match the recorded element set ($detail)")
    }

    private const val SIGNATURE_NAMESPACE = "http://www.w3.org/2000/09/xmldsig#"
}
