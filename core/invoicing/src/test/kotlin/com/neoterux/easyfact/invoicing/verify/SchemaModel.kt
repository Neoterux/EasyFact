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

import java.io.ByteArrayInputStream
import java.math.BigDecimal
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException

/** What an element or attribute holds, reduced to the four cases the facet-parity tests need. */
enum class SchemaKind {
    /** An element with a complex type and no simple content. */
    COMPLEX,

    /** An `xsd:string`-based restriction, simple content, or a typed or inline-typed attribute. */
    STRING,

    /** An `xsd:decimal`-based restriction. */
    DECIMAL,

    /** An element or attribute declared with no type. */
    UNTYPED,
}

/**
 * One element or attribute of the schema, addressed by [path].
 *
 * [maxOccurs] is `null` for `unbounded`. Facets are `null` (or empty lists) when the schema states
 * none; [patterns] and [enumerations] keep the values exactly as written, in schema order.
 */
data class SchemaNode(
    val path: String,
    val kind: SchemaKind,
    val minOccurs: Int,
    val maxOccurs: Int?,
    val maxLength: Int? = null,
    val minLength: Int? = null,
    val patterns: List<String> = emptyList(),
    val enumerations: List<String> = emptyList(),
    val totalDigits: Int? = null,
    val fractionDigits: Int? = null,
    val minInclusive: BigDecimal? = null,
    val maxInclusive: BigDecimal? = null,
    val minExclusive: BigDecimal? = null,
    val maxExclusive: BigDecimal? = null,
)

/**
 * Reads the vendored `factura_V1.1.0.xsd` as data: a map from absolute slash path to [SchemaNode].
 * It is the oracle that the writer's hand-copied facet table is compared against, so it is built
 * from the schema file and never from the writer.
 *
 * **Paths** are rooted at `factura`, with attributes as an at-sign segment:
 * `factura/infoTributaria/ruc`, `factura/infoAdicional/campoAdicional/@nombre`, `factura/@id`.
 *
 * **What it understands.** The global element `factura`; named `xsd:simpleType` and `xsd:complexType`
 * resolved by name; inline types; `xsd:sequence` and `xsd:choice` (a choice does not make its
 * members optional here: occurrence is each element's own attributes); `xsd:simpleContent` with an
 * `xsd:extension` whose base is a named local simple type or `xsd:string`; `xsd:attribute` that is
 * typed, inline-typed or untyped; and restrictions of `xsd:string` or `xsd:decimal` carrying any of
 * the ten facets `maxLength`, `minLength`, `pattern`, `enumeration`, `totalDigits`, `fractionDigits`,
 * `minInclusive`, `maxInclusive`, `minExclusive`, `maxExclusive`.
 *
 * **What it ignores on purpose.** `xsd:annotation` (documentation only) and `xsd:import` (the
 * `ds:` namespace is imported but no `ds:` component is referenced from `factura`; [XsdGate] asserts
 * that the unresolved import is the only schema warning). Named types that `factura` never reaches
 * are not read either, so an unreachable construct cannot make the reader fail.
 *
 * **What it refuses.** Anything else throws [IllegalStateException] naming the path: `xsd:group`,
 * `xsd:attributeGroup`, `xsd:all`, `xsd:any`, an element or attribute `ref`, `xsd:union`, `xsd:list`,
 * a restriction base other than `xsd:string` or `xsd:decimal`, an extension base other than a named
 * local simple type or `xsd:string`, a facet outside the ten above, and a `sequence` or `choice`
 * carrying occurrence attributes. A future schema revision using one of these fails loudly here
 * instead of being half-read; extending the reader is then a deliberate change.
 *
 * **Parser posture** is [XsdGate]'s: namespace-aware, `DOCTYPE` refused, external entities,
 * XInclude and entity expansion off. A schema carrying a `DOCTYPE` throws [SAXException].
 */
object SchemaModel {

    private const val SCHEMA_RESOURCE = "/xsd/factura_V1.1.0.xsd"
    private const val XSD_NAMESPACE = XMLConstants.W3C_XML_SCHEMA_NS_URI
    private const val ROOT_ELEMENT = "factura"

    private val facetNames = setOf(
        "maxLength", "minLength", "pattern", "enumeration", "totalDigits", "fractionDigits",
        "minInclusive", "maxInclusive", "minExclusive", "maxExclusive",
    )

    private val factura: Map<String, SchemaNode> by lazy {
        val bytes = checkNotNull(SchemaModel::class.java.getResourceAsStream(SCHEMA_RESOURCE)) {
            "the vendored schema is missing from the test resources: $SCHEMA_RESOURCE"
        }.use { it.readBytes() }
        parse(bytes)
    }

    /** The vendored factura 1.1.0 schema, read once. */
    fun factura(): Map<String, SchemaNode> = factura

    /** Reads [xsd] with the same rules as [factura]; used for the inline fixtures in the tests. */
    fun parse(xsd: ByteArray): Map<String, SchemaNode> {
        val schema = parser().parse(ByteArrayInputStream(xsd)).documentElement
        return Reader(schema).read()
    }

    private fun parser() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isXIncludeAware = false
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }.newDocumentBuilder().apply {
        // The default handler prints to stderr; a malformed schema must surface as an exception.
        setErrorHandler(
            object : ErrorHandler {
                override fun warning(e: SAXParseException) = Unit
                override fun error(e: SAXParseException): Unit = throw e
                override fun fatalError(e: SAXParseException): Unit = throw e
            },
        )
    }

    /** The facets and base of a simple type, before it is attached to a path. */
    private data class SimpleShape(
        val kind: SchemaKind,
        val maxLength: Int? = null,
        val minLength: Int? = null,
        val patterns: List<String> = emptyList(),
        val enumerations: List<String> = emptyList(),
        val totalDigits: Int? = null,
        val fractionDigits: Int? = null,
        val minInclusive: BigDecimal? = null,
        val maxInclusive: BigDecimal? = null,
        val minExclusive: BigDecimal? = null,
        val maxExclusive: BigDecimal? = null,
    )

    private class Reader(private val schema: Element) {
        private val simpleTypes = mutableMapOf<String, Element>()
        private val complexTypes = mutableMapOf<String, Element>()
        private var rootElement: Element? = null
        private val nodes = linkedMapOf<String, SchemaNode>()
        private val typeStack = mutableListOf<String>()

        fun read(): Map<String, SchemaNode> {
            indexTopLevel()
            val root = checkNotNull(rootElement) { "$ROOT_ELEMENT: no global element named $ROOT_ELEMENT" }
            element(root, parentPath = null, occurrenceOverride = null)
            return nodes.toMap()
        }

        private fun indexTopLevel() {
            for (child in children(schema)) {
                when (child.localName) {
                    "annotation", "import" -> Unit
                    "simpleType" -> register(simpleTypes, child, "simpleType")
                    "complexType" -> register(complexTypes, child, "complexType")
                    "element" -> if (child.getAttribute("name") == ROOT_ELEMENT) {
                        check(rootElement == null) { "$ROOT_ELEMENT: declared more than once" }
                        rootElement = child
                    }
                    else -> unsupported("schema", "top-level xsd:${child.localName}")
                }
            }
        }

        private fun register(into: MutableMap<String, Element>, node: Element, what: String) {
            val name = node.getAttribute("name")
            check(name.isNotEmpty()) { "schema: a top-level $what without a name" }
            check(into.put(name, node) == null) { "$name: $what declared more than once" }
        }

        // --- elements ---------------------------------------------------------------------------

        private fun element(node: Element, parentPath: String?, occurrenceOverride: Int?) {
            val name = node.getAttribute("name")
            val here = if (parentPath == null) name else "$parentPath/$name"
            if (node.hasAttribute("ref")) unsupported(here.ifEmpty { "schema" }, "an element ref")
            check(name.isNotEmpty()) { "${parentPath ?: "schema"}: an element without a name" }
            val minOccurs = occurrenceOverride ?: occurs(node, "minOccurs", here) ?: 1
            val maxOccurs = if (node.getAttribute("maxOccurs") == "unbounded") null else occurs(node, "maxOccurs", here) ?: 1

            val inlineComplex = child(node, "complexType")
            val inlineSimple = child(node, "simpleType")
            val typeAttribute = node.getAttribute("type").takeIf { it.isNotEmpty() }
            val declarations = listOfNotNull(inlineComplex, inlineSimple).size + (if (typeAttribute != null) 1 else 0)
            check(declarations <= 1) { "$here: more than one type declaration" }
            for (other in children(node)) {
                if (other.localName !in setOf("annotation", "complexType", "simpleType")) {
                    unsupported(here, "xsd:${other.localName} inside an element")
                }
            }

            when {
                inlineComplex != null -> complex(inlineComplex, here, minOccurs, maxOccurs, label = "inline type at $here")
                inlineSimple != null -> put(here, simple(inlineSimple, here), minOccurs, maxOccurs)
                typeAttribute != null -> {
                    val ref = resolveType(node, typeAttribute, here)
                    when (ref) {
                        is TypeRef.Builtin -> put(here, SimpleShape(ref.kind), minOccurs, maxOccurs)
                        is TypeRef.Simple -> put(here, simple(ref.node, here), minOccurs, maxOccurs)
                        is TypeRef.Complex -> complex(ref.node, here, minOccurs, maxOccurs, label = ref.name)
                    }
                }
                else -> put(here, SimpleShape(SchemaKind.UNTYPED), minOccurs, maxOccurs)
            }
        }

        private fun complex(node: Element, path: String, minOccurs: Int, maxOccurs: Int?, label: String) {
            check(label !in typeStack) { "$path: complex type $label contains itself" }
            typeStack += label
            try {
                val content = children(node).filter { it.localName != "annotation" }
                val simpleContent = content.firstOrNull { it.localName == "simpleContent" }
                if (simpleContent != null) {
                    check(content.size == 1) { "$path: simpleContent beside other content" }
                    val extension = children(simpleContent).singleOrNull { it.localName == "extension" }
                        ?: unsupported(path, "simpleContent other than a single xsd:extension")
                    val shape = extensionShape(extension, path)
                    put(path, shape, minOccurs, maxOccurs)
                    attributes(extension, path)
                    return
                }
                put(path, SimpleShape(SchemaKind.COMPLEX), minOccurs, maxOccurs)
                for (part in content) {
                    when (part.localName) {
                        "sequence", "choice" -> group(part, path)
                        "attribute" -> attribute(part, path)
                        else -> unsupported(path, "xsd:${part.localName}")
                    }
                }
            } finally {
                typeStack.removeAt(typeStack.lastIndex)
            }
        }

        private fun group(node: Element, path: String) {
            if (node.hasAttribute("minOccurs") || node.hasAttribute("maxOccurs")) {
                unsupported(path, "a xsd:${node.localName} with occurrence attributes")
            }
            for (part in children(node)) {
                when (part.localName) {
                    "annotation" -> Unit
                    "element" -> element(part, parentPath = path, occurrenceOverride = null)
                    "sequence", "choice" -> group(part, path)
                    else -> unsupported(path, "xsd:${part.localName} inside a xsd:${node.localName}")
                }
            }
        }

        private fun extensionShape(extension: Element, path: String): SimpleShape {
            val base = extension.getAttribute("base")
            check(base.isNotEmpty()) { "$path: an extension without a base" }
            return when (val ref = resolveType(extension, base, path)) {
                is TypeRef.Builtin ->
                    if (ref.kind == SchemaKind.STRING) SimpleShape(SchemaKind.STRING)
                    else unsupported(path, "an extension base other than a named local simple type or xsd:string")
                is TypeRef.Simple -> simple(ref.node, path)
                is TypeRef.Complex -> unsupported(path, "an extension base other than a named local simple type or xsd:string")
            }
        }

        // --- attributes -------------------------------------------------------------------------

        private fun attributes(node: Element, path: String) {
            for (part in children(node)) {
                when (part.localName) {
                    "annotation" -> Unit
                    "attribute" -> attribute(part, path)
                    else -> unsupported(path, "xsd:${part.localName} inside an extension")
                }
            }
        }

        private fun attribute(node: Element, parentPath: String) {
            val name = node.getAttribute("name")
            if (node.hasAttribute("ref")) unsupported(parentPath, "an attribute ref")
            check(name.isNotEmpty()) { "$parentPath: an attribute without a name" }
            val here = "$parentPath/@$name"
            val required = node.getAttribute("use") == "required"
            val inline = child(node, "simpleType")
            val typeAttribute = node.getAttribute("type").takeIf { it.isNotEmpty() }
            check(inline == null || typeAttribute == null) { "$here: more than one type declaration" }
            val shape = when {
                inline != null -> simple(inline, here)
                typeAttribute != null -> when (val ref = resolveType(node, typeAttribute, here)) {
                    is TypeRef.Builtin -> SimpleShape(ref.kind)
                    is TypeRef.Simple -> simple(ref.node, here)
                    is TypeRef.Complex -> unsupported(here, "an attribute of a complex type")
                }
                else -> SimpleShape(SchemaKind.UNTYPED)
            }
            put(here, shape, if (required) 1 else 0, 1)
        }

        // --- simple types -----------------------------------------------------------------------

        private fun simple(node: Element, path: String): SimpleShape {
            val content = children(node).filter { it.localName != "annotation" }
            val restriction = content.singleOrNull() ?: unsupported(path, "a simpleType without exactly one child")
            when (restriction.localName) {
                "restriction" -> Unit
                "union" -> unsupported(path, "xsd:union")
                "list" -> unsupported(path, "xsd:list")
                else -> unsupported(path, "xsd:${restriction.localName} inside a simpleType")
            }
            val base = restriction.getAttribute("base")
            val kind = when (base.substringAfter(':', base).takeIf { builtinNamespace(restriction, base) }) {
                "string" -> SchemaKind.STRING
                "decimal" -> SchemaKind.DECIMAL
                else -> unsupported(path, "a restriction base other than xsd:string or xsd:decimal ($base)")
            }
            var shape = SimpleShape(kind)
            for (facet in children(restriction)) {
                val facetName = facet.localName
                if (facetName == "annotation") continue
                if (facetName !in facetNames) unsupported(path, "the facet xsd:$facetName")
                val value = facet.getAttribute("value")
                shape = when (facetName) {
                    "maxLength" -> shape.copy(maxLength = integer(value, path, facetName))
                    "minLength" -> shape.copy(minLength = integer(value, path, facetName))
                    "pattern" -> shape.copy(patterns = shape.patterns + value)
                    "enumeration" -> shape.copy(enumerations = shape.enumerations + value)
                    "totalDigits" -> shape.copy(totalDigits = integer(value, path, facetName))
                    "fractionDigits" -> shape.copy(fractionDigits = integer(value, path, facetName))
                    "minInclusive" -> shape.copy(minInclusive = number(value, path, facetName))
                    "maxInclusive" -> shape.copy(maxInclusive = number(value, path, facetName))
                    "minExclusive" -> shape.copy(minExclusive = number(value, path, facetName))
                    else -> shape.copy(maxExclusive = number(value, path, facetName))
                }
            }
            return shape
        }

        private fun integer(value: String, path: String, facet: String): Int =
            value.toIntOrNull() ?: error("$path: $facet is not an integer: '$value'")

        private fun number(value: String, path: String, facet: String): BigDecimal =
            runCatching { BigDecimal(value) }.getOrElse { error("$path: $facet is not a number: '$value'") }

        // --- type references --------------------------------------------------------------------

        private sealed interface TypeRef {
            data class Builtin(val kind: SchemaKind) : TypeRef
            data class Simple(val node: Element) : TypeRef
            data class Complex(val name: String, val node: Element) : TypeRef
        }

        private fun resolveType(context: Element, qname: String, path: String): TypeRef {
            if (':' in qname) {
                if (!builtinNamespace(context, qname)) unsupported(path, "a type in a namespace other than the XML Schema one ($qname)")
                return when (qname.substringAfter(':')) {
                    "string" -> TypeRef.Builtin(SchemaKind.STRING)
                    "decimal" -> TypeRef.Builtin(SchemaKind.DECIMAL)
                    else -> unsupported(path, "the built-in type $qname")
                }
            }
            simpleTypes[qname]?.let { return TypeRef.Simple(it) }
            complexTypes[qname]?.let { return TypeRef.Complex(qname, it) }
            error("$path: the type $qname is not declared")
        }

        /** True when [qname] carries a prefix bound to the XML Schema namespace. */
        private fun builtinNamespace(context: Element, qname: String): Boolean {
            if (':' !in qname) return false
            return context.lookupNamespaceURI(qname.substringBefore(':')) == XSD_NAMESPACE
        }

        // --- helpers ----------------------------------------------------------------------------

        private fun put(path: String, shape: SimpleShape, minOccurs: Int, maxOccurs: Int?) {
            val node = SchemaNode(
                path = path,
                kind = shape.kind,
                minOccurs = minOccurs,
                maxOccurs = maxOccurs,
                maxLength = shape.maxLength,
                minLength = shape.minLength,
                patterns = shape.patterns,
                enumerations = shape.enumerations,
                totalDigits = shape.totalDigits,
                fractionDigits = shape.fractionDigits,
                minInclusive = shape.minInclusive,
                maxInclusive = shape.maxInclusive,
                minExclusive = shape.minExclusive,
                maxExclusive = shape.maxExclusive,
            )
            check(nodes.put(path, node) == null) { "$path: declared more than once" }
        }

        private fun occurs(node: Element, attribute: String, path: String): Int? {
            if (!node.hasAttribute(attribute)) return null
            val value = node.getAttribute(attribute)
            return value.toIntOrNull() ?: error("$path: $attribute is not an integer: '$value'")
        }

        private fun children(node: Element): List<Element> {
            val found = mutableListOf<Element>()
            var current: Node? = node.firstChild
            while (current != null) {
                if (current is Element) {
                    check(current.namespaceURI == XSD_NAMESPACE) {
                        "${node.getAttribute("name").ifEmpty { node.localName }}: an element outside the XML Schema namespace: ${current.tagName}"
                    }
                    found += current
                }
                current = current.nextSibling
            }
            return found
        }

        private fun child(node: Element, name: String): Element? = children(node).firstOrNull { it.localName == name }

        private fun unsupported(path: String, what: String): Nothing =
            throw IllegalStateException("$path: SchemaModel does not understand $what")
    }
}
