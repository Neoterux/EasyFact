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

package com.neoterux.easyfact.invoicing.factura

import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import com.neoterux.easyfact.invoicing.xml.CanonicalEmitter
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Maps a [Factura] onto canonical XML, in the strict element order of the 1.1.0 schema (which is
 * an `xsd:sequence`: a different order is a different, invalid document).
 *
 * The Spanish strings in the [Facet] paths are the SRI's element and attribute names, kept verbatim
 * because they are protocol identifiers and renaming them would break the mapping to the
 * specification. They are gathered in one place so the mapping is auditable without reading the
 * emission code (AGENTS.md section 2.1), and they are the only Spanish in the package.
 *
 * Three rules hold throughout.
 *
 * - **Everything goes through [CanonicalEmitter].** This object never concatenates markup. One
 *   implementation of the canonical guarantee is the whole point of the two-layer writer, and a
 *   test asserts that no markup literal appears in this file.
 * - **A conditional element is emitted with content or omitted entirely**, never emitted empty.
 *   The schema's `minLength` and pattern facets reject empty content, and an empty element is
 *   also a silent data loss at the domain boundary. Lists satisfy the rule in one of two ways: an
 *   optional container is omitted when its list is empty, and a container the schema requires, or
 *   that this writer always emits (`detalles`, `totalConImpuestos`, `pagos`, `impuestos`,
 *   `detalleImpuestos`), is refused with [FacturaError.EmptyCollection] before it is opened.
 * - **Scale is applied at the serialization boundary** by [Money], and nowhere earlier: money has
 *   two decimals, quantities and unit prices six, always half-up and never with an exponent. The
 *   date pattern is pinned to [Locale.ROOT] for the same reason.
 *
 * The six decimals on `cantidad` and `precioUnitario` are deliberate and differ from the two the
 * phase plan named. Two is the SRI's rule for monetary totals, which [Money.amount] applies; the
 * factura schema allows a unit price six. Rounding a unit price to two before multiplying is what
 * makes `cantidad * precioUnitario` disagree with `precioTotalSinImpuesto`, and that disagreement
 * is itself a rejection, so the wider scale is the safer of the two.
 *
 * **The vendored XSD is the authority, not this file.** The element order and the facets in
 * [Facet] are copied by hand from `factura_V1.1.0.xsd`; the writer never reads the schema, at run
 * time or otherwise. Every string it writes is checked against the schema's pattern and
 * enumeration facets, a failure being [FacturaError.InvalidFormat], alongside the length,
 * occurrence and decimal facets, and all of them are parity-tested. `FacturaFacetParityTest` reads the vendored schema in the test source set and
 * fails the build when any length, occurrence, decimal, pattern or enumeration facet differs from
 * the schema, when a schema path has no facet, or when a facet names a path the schema lacks.
 * Decimal facets judge the value as written, which is the value after [Money]'s half-up rounding,
 * so a carry from rounding can push a value over `totalDigits`. Text facets judge the value as
 * given. Where the table and the schema disagree, the schema wins and this file is corrected.
 */
object FacturaWriter {

    /** The kind of content a [Facet] describes. */
    internal enum class Kind {
        /** An element that holds other elements, or an element or attribute that holds nothing the writer checks. */
        CONTAINER,

        /** An element or attribute that holds text. */
        TEXT,

        /** An element that holds a decimal number, always produced by [Money]. */
        DECIMAL,
    }

    /**
     * One element or attribute of the schema: where it sits, the SRI's own name for it, and the
     * bound that is applied to its content.
     *
     * [path] is the absolute schema path with attributes as an at-sign segment, written exactly as
     * the test-side schema reader writes it (`factura/infoAdicional/campoAdicional/@nombre`), so
     * that a test can set this table beside the vendored schema and compare the two. [name] is the
     * last segment without the at-sign: the element name that is emitted and the element name every
     * [FacturaError] reports.
     *
     * [maxLength] and [minLength] are the schema's own `maxLength` and `minLength` facets, [patterns]
     * and [enumerations] its `pattern` and `enumeration` facets (the pattern strings byte for byte,
     * in schema order), and [maxOccurs] its repeat cap where that is greater than one; each is
     * `null` or empty where the schema states none. A decimal facet carries [totalDigits],
     * [fractionDigits] and the four bounds [minInclusive], [maxInclusive], [minExclusive] and
     * [maxExclusive], each `null` where the schema states none. All of them are copied by hand and
     * compared with the schema by a test on every build.
     *
     * [catalogue] is the one bound that belongs to the writer alone and is never compared with the
     * schema: the codes the writer knows by name, so that a well-formed code outside them is
     * reported as [FacturaError.CodeOutsideCatalogue] and not as a malformed one. A test proves
     * every catalogue value satisfies the facet's [patterns], so the catalogue is always inside what
     * the schema allows. `codigoPorcentaje` has none: the rate catalogue is read from the SRI
     * specification rather than hardcoded, historical codes stay valid for past periods, and a
     * stale set here would reject a correct document.
     *
     * A text value is judged against [maxLength], [minLength], XML legality, [catalogue], then
     * [patterns] and [enumerations]; a decimal value is judged against its decimal facets by
     * [decimalViolation]. A value satisfies [patterns] when it matches at least one of them in full,
     * as the schema reads several patterns on one restriction.
     */
    internal data class Facet(
        val path: String,
        val kind: Kind,
        val maxLength: Int? = null,
        val minLength: Int? = null,
        val maxOccurs: Int? = null,
        val patterns: List<String> = emptyList(),
        val enumerations: List<String> = emptyList(),
        val catalogue: Set<String>? = null,
        val totalDigits: Int? = null,
        val fractionDigits: Int? = null,
        val minInclusive: BigDecimal? = null,
        val maxInclusive: BigDecimal? = null,
        val minExclusive: BigDecimal? = null,
        val maxExclusive: BigDecimal? = null,
    ) {
        val name: String get() = path.substringAfterLast('/').removePrefix("@")

        /** [patterns] compiled once, on first use. */
        private val regexes: List<Regex> by lazy { patterns.map { Regex(it) } }

        /**
         * Whether [value] satisfies this facet's `pattern` and `enumeration` facets; a facet that
         * states neither accepts every value. `Regex.matches` anchors at both ends, as the schema does.
         */
        fun acceptsFormat(value: String): Boolean {
            if (enumerations.isNotEmpty() && value !in enumerations) return false
            return regexes.isEmpty() || regexes.any { it.matches(value) }
        }
    }

    /** Every facet declared below, in declaration order; filled by the factories, so complete by construction. */
    private val registry = mutableListOf<Facet>()

    private fun register(facet: Facet): Facet = facet.also { registry += it }

    /** A container; [maxOccurs] is the schema's repeat cap where it is greater than one. */
    private fun container(path: String, maxOccurs: Int? = null): Facet =
        register(Facet(path, Kind.CONTAINER, maxOccurs = maxOccurs))

    /**
     * A text facet whose schema type states [maxLength]. [minLength] is the schema's, which is 1
     * for nearly every such type; `null` says the schema states none.
     */
    private fun bounded(
        path: String,
        maxLength: Int,
        minLength: Int? = 1,
        maxOccurs: Int? = null,
        patterns: List<String> = emptyList(),
        catalogue: Set<String>? = null,
    ): Facet = register(
        Facet(
            path,
            Kind.TEXT,
            maxLength = maxLength,
            minLength = minLength,
            maxOccurs = maxOccurs,
            patterns = patterns,
            catalogue = catalogue,
        ),
    )

    /**
     * A text facet whose schema type states no `maxLength` and is bounded by [patterns] or
     * [enumerations] instead.
     */
    private fun patterned(
        path: String,
        vararg patterns: String,
        enumerations: List<String> = emptyList(),
        catalogue: Set<String>? = null,
    ): Facet {
        require(patterns.isNotEmpty() || enumerations.isNotEmpty()) { "$path is bounded by neither a pattern nor an enumeration" }
        return register(Facet(path, Kind.TEXT, patterns = patterns.toList(), enumerations = enumerations, catalogue = catalogue))
    }

    /** A text facet the schema leaves untyped, so that nothing bounds it: `factura/@version` only. */
    private fun untyped(path: String): Facet = register(Facet(path, Kind.TEXT))

    /** The decimal facets of one named schema type, copied by hand and compared by a test. */
    private class DecimalShape(
        val totalDigits: Int,
        val fractionDigits: Int,
        val minInclusive: BigDecimal? = null,
    )

    /** `totalSinImpuestos`, `valor`, `importeTotal` and most other amounts. */
    private val AMOUNT = DecimalShape(totalDigits = 14, fractionDigits = 2, minInclusive = BigDecimal("0"))

    /** `totalDescuentos` and `descuentoAdicional`: the schema states no lower bound. */
    private val SIGNED_AMOUNT = DecimalShape(totalDigits = 14, fractionDigits = 2)

    /** `tarifa`. */
    private val RATE = DecimalShape(totalDigits = 4, fractionDigits = 2, minInclusive = BigDecimal("0"))

    /** `tarifaRetencion`. */
    private val WITHHOLDING_RATE = DecimalShape(totalDigits = 5, fractionDigits = 2, minInclusive = BigDecimal("0"))

    /** `cantidad`, `precioUnitario` and `precioSinSubsidio`. */
    private val QUANTITY = DecimalShape(totalDigits = 18, fractionDigits = 6, minInclusive = BigDecimal("0"))

    private fun decimal(path: String, shape: DecimalShape = AMOUNT): Facet =
        register(
            Facet(
                path,
                Kind.DECIMAL,
                totalDigits = shape.totalDigits,
                fractionDigits = shape.fractionDigits,
                minInclusive = shape.minInclusive,
            ),
        )

    private const val ROOT_ID = "comprobante"
    private const val SCHEMA_VERSION = "1.1.0"

    // --- schema patterns, byte for byte from factura_V1.1.0.xsd ---------------------------------------

    /** `[^\n]*`: any text without a line feed. Most free-text types state it beside their `maxLength`. */
    private const val NO_LINE_FEED = "[^\\n]*"
    private const val THREE_DIGITS = "[0-9]{3}"
    private const val NINE_DIGITS = "[0-9]{9}"
    private const val DIGITS = "[0-9]+"
    private const val DOCUMENT_CODE = "[0-9]{2,3}"
    private const val DATE = "(0[1-9]|[12][0-9]|3[01])/(0[1-9]|1[012])/20[0-9][0-9]"
    private const val TAX_CODE = "[235]"
    private const val IDENTIFICATION_TYPE = "[0][4-8]"
    private const val UPPER_CASE_TERM = "([A-Z])*"

    /** `compensacion` and `compensacionReembolso` share one code type, whose only value is `1`. */
    private const val COMPENSATION_CODE = "1"

    // --- catalogues the writer knows by name -------------------------------------------------------

    private val ENVIRONMENTS = setOf("1", "2")
    private val EMISSION_TYPES = setOf("1")
    private val DOCUMENT_TYPES = setOf("01")
    private val BUYER_IDENTIFICATION_TYPES = setOf("04", "05", "06", "07", "08")

    /** `2` IVA, `3` ICE, `5` IRBPNR. */
    private val TAXES = setOf("2", "3", "5")

    // --- schema paths ------------------------------------------------------------------------------

    private const val ROOT = "factura"
    private const val TAX_INFO = "$ROOT/infoTributaria"
    private const val INVOICE_INFO = "$ROOT/infoFactura"
    private const val TAX_TOTAL = "$INVOICE_INFO/totalConImpuestos/totalImpuesto"
    private const val COMPENSATION = "$INVOICE_INFO/compensaciones/compensacion"
    private const val PAYMENT = "$INVOICE_INFO/pagos/pago"
    private const val LINE = "$ROOT/detalles/detalle"
    private const val LINE_DETAIL = "$LINE/detallesAdicionales/detAdicional"
    private const val LINE_TAX = "$LINE/impuestos/impuesto"
    private const val REFUND = "$ROOT/reembolsos/reembolsoDetalle"
    private const val REFUND_TAX = "$REFUND/detalleImpuestos/detalleImpuesto"
    private const val REFUND_COMPENSATION = "$REFUND/compensacionesReembolso/compensacionReembolso"
    private const val WITHHOLDING = "$ROOT/retenciones/retencion"
    private const val FISCAL_MACHINE = "$ROOT/maquinaFiscal"
    private const val ADDITIONAL_FIELD = "$ROOT/infoAdicional/campoAdicional"

    // --- factura -------------------------------------------------------------------------------

    private val FACTURA = container(ROOT)
    private val ID_ATTRIBUTE = patterned("$ROOT/@id", enumerations = listOf(ROOT_ID))
    private val VERSION_ATTRIBUTE = untyped("$ROOT/@version")

    // --- infoTributaria ------------------------------------------------------------------------

    private val INFO_TRIBUTARIA = container(TAX_INFO)
    private val AMBIENTE = patterned("$TAX_INFO/ambiente", "[1-2]{1}", catalogue = ENVIRONMENTS)
    private val TIPO_EMISION = patterned("$TAX_INFO/tipoEmision", "[12]{1}", catalogue = EMISSION_TYPES)
    private val RAZON_SOCIAL = bounded("$TAX_INFO/razonSocial", 300, patterns = listOf(NO_LINE_FEED))
    private val NOMBRE_COMERCIAL = bounded("$TAX_INFO/nombreComercial", 300, patterns = listOf(NO_LINE_FEED))
    private val RUC = patterned("$TAX_INFO/ruc", "[0-9]{10}001")
    private val CLAVE_ACCESO = patterned("$TAX_INFO/claveAcceso", "[0-9]{49}")
    private val COD_DOC = patterned("$TAX_INFO/codDoc", "[0-9]{2}", catalogue = DOCUMENT_TYPES)
    private val ESTAB = patterned("$TAX_INFO/estab", THREE_DIGITS)
    private val PTO_EMI = patterned("$TAX_INFO/ptoEmi", THREE_DIGITS)
    private val SECUENCIAL = patterned("$TAX_INFO/secuencial", NINE_DIGITS)
    private val DIR_MATRIZ = bounded("$TAX_INFO/dirMatriz", 300, patterns = listOf(NO_LINE_FEED))
    private val AGENTE_RETENCION = bounded("$TAX_INFO/agenteRetencion", 8, minLength = null, patterns = listOf(DIGITS))
    private val CONTRIBUYENTE_RIMPE = patterned("$TAX_INFO/contribuyenteRimpe", "CONTRIBUYENTE (NEGOCIO POPULAR - )?RÉGIMEN RIMPE")

    // --- infoFactura ---------------------------------------------------------------------------

    private val INFO_FACTURA = container(INVOICE_INFO)
    private val FECHA_EMISION = patterned("$INVOICE_INFO/fechaEmision", DATE)
    private val DIR_ESTABLECIMIENTO = bounded("$INVOICE_INFO/dirEstablecimiento", 300, patterns = listOf(NO_LINE_FEED))
    private val CONTRIBUYENTE_ESPECIAL = bounded("$INVOICE_INFO/contribuyenteEspecial", 13, minLength = 3, patterns = listOf("([A-Za-z0-9])*"))
    private val OBLIGADO_CONTABILIDAD = patterned("$INVOICE_INFO/obligadoContabilidad", enumerations = listOf("SI", "NO"))
    private val COMERCIO_EXTERIOR = patterned("$INVOICE_INFO/comercioExterior", "EXPORTADOR")
    private val INCO_TERM_FACTURA = bounded("$INVOICE_INFO/incoTermFactura", 10, patterns = listOf(UPPER_CASE_TERM))
    private val LUGAR_INCO_TERM = bounded("$INVOICE_INFO/lugarIncoTerm", 300, patterns = listOf(NO_LINE_FEED))
    private val PAIS_ORIGEN = patterned("$INVOICE_INFO/paisOrigen", THREE_DIGITS)
    private val PUERTO_EMBARQUE = bounded("$INVOICE_INFO/puertoEmbarque", 300, patterns = listOf(NO_LINE_FEED))
    private val PUERTO_DESTINO = bounded("$INVOICE_INFO/puertoDestino", 300, patterns = listOf(NO_LINE_FEED))
    private val PAIS_DESTINO = patterned("$INVOICE_INFO/paisDestino", THREE_DIGITS)
    private val PAIS_ADQUISICION = patterned("$INVOICE_INFO/paisAdquisicion", THREE_DIGITS)
    private val TIPO_IDENTIFICACION_COMPRADOR = patterned(
        "$INVOICE_INFO/tipoIdentificacionComprador",
        IDENTIFICATION_TYPE,
        catalogue = BUYER_IDENTIFICATION_TYPES,
    )
    private val GUIA_REMISION = patterned("$INVOICE_INFO/guiaRemision", "[0-9]{3}-[0-9]{3}-[0-9]{9}")
    private val RAZON_SOCIAL_COMPRADOR = bounded("$INVOICE_INFO/razonSocialComprador", 300, patterns = listOf(NO_LINE_FEED))
    private val IDENTIFICACION_COMPRADOR = bounded("$INVOICE_INFO/identificacionComprador", 20, patterns = listOf(NO_LINE_FEED))
    private val DIRECCION_COMPRADOR = bounded("$INVOICE_INFO/direccionComprador", 300, patterns = listOf(NO_LINE_FEED))
    private val TOTAL_SIN_IMPUESTOS = decimal("$INVOICE_INFO/totalSinImpuestos")
    private val TOTAL_SUBSIDIO = decimal("$INVOICE_INFO/totalSubsidio")
    private val INCO_TERM_TOTAL_SIN_IMPUESTOS = bounded("$INVOICE_INFO/incoTermTotalSinImpuestos", 10, patterns = listOf(UPPER_CASE_TERM))
    private val TOTAL_DESCUENTO = decimal("$INVOICE_INFO/totalDescuento", SIGNED_AMOUNT)
    private val COD_DOC_REEMBOLSO = patterned("$INVOICE_INFO/codDocReembolso", DOCUMENT_CODE)
    private val TOTAL_COMPROBANTES_REEMBOLSO = decimal("$INVOICE_INFO/totalComprobantesReembolso")
    private val TOTAL_BASE_IMPONIBLE_REEMBOLSO = decimal("$INVOICE_INFO/totalBaseImponibleReembolso")
    private val TOTAL_IMPUESTO_REEMBOLSO = decimal("$INVOICE_INFO/totalImpuestoReembolso")
    private val PROPINA = decimal("$INVOICE_INFO/propina")
    private val FLETE_INTERNACIONAL = decimal("$INVOICE_INFO/fleteInternacional")
    private val SEGURO_INTERNACIONAL = decimal("$INVOICE_INFO/seguroInternacional")
    private val GASTOS_ADUANEROS = decimal("$INVOICE_INFO/gastosAduaneros")
    private val GASTOS_TRANSPORTE_OTROS = decimal("$INVOICE_INFO/gastosTransporteOtros")
    private val IMPORTE_TOTAL = decimal("$INVOICE_INFO/importeTotal")
    private val MONEDA = bounded("$INVOICE_INFO/moneda", 15, patterns = listOf(NO_LINE_FEED))
    private val PLACA = bounded("$INVOICE_INFO/placa", 20, patterns = listOf(NO_LINE_FEED))
    private val VALOR_RET_IVA = decimal("$INVOICE_INFO/valorRetIva")
    private val VALOR_RET_RENTA = decimal("$INVOICE_INFO/valorRetRenta")

    // --- tax totals --------------------------------------------------------------------------------

    private val TOTAL_CON_IMPUESTOS = container("$INVOICE_INFO/totalConImpuestos")
    private val TOTAL_IMPUESTO = container(TAX_TOTAL)
    private val TOTAL_IMPUESTO_CODIGO = bounded("$TAX_TOTAL/codigo", 1, patterns = listOf(TAX_CODE), catalogue = TAXES)
    private val TOTAL_IMPUESTO_CODIGO_PORCENTAJE = bounded("$TAX_TOTAL/codigoPorcentaje", 4, patterns = listOf(DIGITS))
    private val DESCUENTO_ADICIONAL = decimal("$TAX_TOTAL/descuentoAdicional", SIGNED_AMOUNT)
    private val TOTAL_IMPUESTO_BASE_IMPONIBLE = decimal("$TAX_TOTAL/baseImponible")
    private val TOTAL_IMPUESTO_TARIFA = decimal("$TAX_TOTAL/tarifa", RATE)
    private val TOTAL_IMPUESTO_VALOR = decimal("$TAX_TOTAL/valor")
    private val VALOR_DEVOLUCION_IVA = decimal("$TAX_TOTAL/valorDevolucionIva")

    // --- compensations (the infoFactura block and the refund block share one item type) -------------

    private val COMPENSACIONES = container("$INVOICE_INFO/compensaciones")
    private val COMPENSACION = container(COMPENSATION)
    private val COMPENSACION_CODIGO = patterned("$COMPENSATION/codigo", COMPENSATION_CODE)
    private val COMPENSACION_TARIFA = decimal("$COMPENSATION/tarifa", RATE)
    private val COMPENSACION_VALOR = decimal("$COMPENSATION/valor")

    private val COMPENSACIONES_REEMBOLSO = container("$REFUND/compensacionesReembolso")
    private val COMPENSACION_REEMBOLSO = container(REFUND_COMPENSATION)
    private val COMPENSACION_REEMBOLSO_CODIGO = patterned("$REFUND_COMPENSATION/codigo", COMPENSATION_CODE)
    private val COMPENSACION_REEMBOLSO_TARIFA = decimal("$REFUND_COMPENSATION/tarifa", RATE)
    private val COMPENSACION_REEMBOLSO_VALOR = decimal("$REFUND_COMPENSATION/valor")

    /** The facets one compensation block is written with, so one writer serves both blocks. */
    private class CompensationFacets(
        val container: Facet,
        val item: Facet,
        val code: Facet,
        val rate: Facet,
        val amount: Facet,
    )

    private val INVOICE_COMPENSATIONS = CompensationFacets(
        COMPENSACIONES, COMPENSACION, COMPENSACION_CODIGO, COMPENSACION_TARIFA, COMPENSACION_VALOR,
    )
    private val REFUND_COMPENSATIONS = CompensationFacets(
        COMPENSACIONES_REEMBOLSO,
        COMPENSACION_REEMBOLSO,
        COMPENSACION_REEMBOLSO_CODIGO,
        COMPENSACION_REEMBOLSO_TARIFA,
        COMPENSACION_REEMBOLSO_VALOR,
    )

    // --- payments ----------------------------------------------------------------------------------

    private val PAGOS = container("$INVOICE_INFO/pagos")
    private val PAGO = container(PAYMENT)
    private val FORMA_PAGO = patterned("$PAYMENT/formaPago", "[0][1-9]", "[1][0-9]", "[2][0-1]")
    private val TOTAL = decimal("$PAYMENT/total")
    private val PLAZO = decimal("$PAYMENT/plazo")
    private val UNIDAD_TIEMPO = bounded("$PAYMENT/unidadTiempo", 10, patterns = listOf(NO_LINE_FEED))

    // --- detalles -----------------------------------------------------------------------------

    private val DETALLES = container("$ROOT/detalles")
    private val DETALLE = container(LINE)
    private val CODIGO_PRINCIPAL = bounded("$LINE/codigoPrincipal", 25, patterns = listOf(NO_LINE_FEED))
    private val CODIGO_AUXILIAR = bounded("$LINE/codigoAuxiliar", 25, patterns = listOf(NO_LINE_FEED))
    private val DESCRIPCION = bounded("$LINE/descripcion", 300, patterns = listOf(NO_LINE_FEED))
    private val UNIDAD_MEDIDA = bounded("$LINE/unidadMedida", 50, patterns = listOf(NO_LINE_FEED))
    private val CANTIDAD = decimal("$LINE/cantidad", QUANTITY)
    private val PRECIO_UNITARIO = decimal("$LINE/precioUnitario", QUANTITY)
    private val PRECIO_SIN_SUBSIDIO = decimal("$LINE/precioSinSubsidio", QUANTITY)
    private val DESCUENTO = decimal("$LINE/descuento")
    private val PRECIO_TOTAL_SIN_IMPUESTO = decimal("$LINE/precioTotalSinImpuesto")
    private val DETALLES_ADICIONALES = container("$LINE/detallesAdicionales")
    private val DET_ADICIONAL = container(LINE_DETAIL, maxOccurs = 3)
    private val DET_ADICIONAL_NOMBRE = bounded("$LINE_DETAIL/@nombre", 300)
    private val DET_ADICIONAL_VALOR = bounded("$LINE_DETAIL/@valor", 300)
    private val IMPUESTOS = container("$LINE/impuestos")
    private val IMPUESTO = container(LINE_TAX)
    private val IMPUESTO_CODIGO = bounded("$LINE_TAX/codigo", 1, patterns = listOf(TAX_CODE), catalogue = TAXES)
    private val IMPUESTO_CODIGO_PORCENTAJE = bounded("$LINE_TAX/codigoPorcentaje", 4, patterns = listOf(DIGITS))
    private val IMPUESTO_TARIFA = decimal("$LINE_TAX/tarifa", RATE)
    private val IMPUESTO_BASE_IMPONIBLE = decimal("$LINE_TAX/baseImponible")
    private val IMPUESTO_VALOR = decimal("$LINE_TAX/valor")

    // --- reembolsos ------------------------------------------------------------------------------

    private val REEMBOLSOS = container("$ROOT/reembolsos")
    private val REEMBOLSO_DETALLE = container(REFUND)
    private val TIPO_IDENTIFICACION_PROVEEDOR_REEMBOLSO = patterned("$REFUND/tipoIdentificacionProveedorReembolso", IDENTIFICATION_TYPE)
    private val IDENTIFICACION_PROVEEDOR_REEMBOLSO = bounded("$REFUND/identificacionProveedorReembolso", 20, patterns = listOf(NO_LINE_FEED))
    private val COD_PAIS_PAGO_PROVEEDOR_REEMBOLSO = patterned("$REFUND/codPaisPagoProveedorReembolso", THREE_DIGITS)
    private val TIPO_PROVEEDOR_REEMBOLSO = patterned("$REFUND/tipoProveedorReembolso", "[0][12]")
    private val REEMBOLSO_COD_DOC = patterned("$REFUND/codDocReembolso", DOCUMENT_CODE)
    private val ESTAB_DOC_REEMBOLSO = patterned("$REFUND/estabDocReembolso", THREE_DIGITS)
    private val PTO_EMI_DOC_REEMBOLSO = patterned("$REFUND/ptoEmiDocReembolso", THREE_DIGITS)
    private val SECUENCIAL_DOC_REEMBOLSO = patterned("$REFUND/secuencialDocReembolso", NINE_DIGITS)
    private val FECHA_EMISION_DOC_REEMBOLSO = patterned("$REFUND/fechaEmisionDocReembolso", DATE)
    private val NUMERO_AUTORIZACION_DOC_REEMB = bounded("$REFUND/numeroautorizacionDocReemb", 49, minLength = 10, patterns = listOf("[0-9]{10,49}"))
    private val DETALLE_IMPUESTOS = container("$REFUND/detalleImpuestos")
    private val DETALLE_IMPUESTO = container(REFUND_TAX)
    private val DETALLE_IMPUESTO_CODIGO = patterned("$REFUND_TAX/codigo", TAX_CODE, catalogue = TAXES)
    private val DETALLE_IMPUESTO_CODIGO_PORCENTAJE = patterned("$REFUND_TAX/codigoPorcentaje", "[0-9]{1,4}")
    private val DETALLE_IMPUESTO_TARIFA = decimal("$REFUND_TAX/tarifa", RATE)
    private val BASE_IMPONIBLE_REEMBOLSO = decimal("$REFUND_TAX/baseImponibleReembolso")
    private val IMPUESTO_REEMBOLSO = decimal("$REFUND_TAX/impuestoReembolso")

    // --- retenciones -------------------------------------------------------------------------------

    private val RETENCIONES = container("$ROOT/retenciones")
    private val RETENCION = container(WITHHOLDING)
    private val RETENCION_CODIGO = patterned("$WITHHOLDING/codigo", "[4]{1}")
    private val RETENCION_CODIGO_PORCENTAJE = patterned("$WITHHOLDING/codigoPorcentaje", "[0-9]{1,3}")
    private val RETENCION_TARIFA = decimal("$WITHHOLDING/tarifa", WITHHOLDING_RATE)
    private val RETENCION_VALOR = decimal("$WITHHOLDING/valor")

    // --- negotiable, fiscal machine and infoAdicional -----------------------------

    private val TIPO_NEGOCIABLE = container("$ROOT/tipoNegociable")
    private val CORREO = bounded("$ROOT/tipoNegociable/correo", 100, patterns = listOf(NO_LINE_FEED))
    private val MAQUINA_FISCAL = container(FISCAL_MACHINE)
    private val MARCA = bounded("$FISCAL_MACHINE/marca", 30, patterns = listOf(NO_LINE_FEED))
    private val MODELO = bounded("$FISCAL_MACHINE/modelo", 30, patterns = listOf(NO_LINE_FEED))
    private val SERIE = bounded("$FISCAL_MACHINE/serie", 30, patterns = listOf(NO_LINE_FEED))
    private val INFO_ADICIONAL = container("$ROOT/infoAdicional")
    private val CAMPO_ADICIONAL = bounded(ADDITIONAL_FIELD, 300, maxOccurs = 15)
    private val CAMPO_ADICIONAL_NOMBRE = bounded("$ADDITIONAL_FIELD/@nombre", 300)

    /**
     * Every element and attribute the writer emits, with the bound it applies. The writer never
     * reads the schema; a test sets this list beside the vendored schema and fails on any drift.
     */
    internal val FACETS: List<Facet> = registry.toList()

    /** The repeat cap the schema states for [facet]; every facet asked here carries one. */
    private fun cap(facet: Facet): Int = checkNotNull(facet.maxOccurs) { "${facet.path} has no occurrence cap" }

    private val ISSUE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    /** The issue date as the access key embeds it: `ddMMyyyy`, with no separators. */
    private val KEY_ISSUE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT)

    /**
     * Writes [factura] with the XML declaration, or reports the first reason it cannot be
     * written. The declaration is kept out of the signed root.
     *
     * Emission stops at the first failure and the half-built document is discarded, so a rejected
     * factura never yields octets that a caller could mistake for a comprobante.
     */
    fun write(factura: Factura): Outcome<CanonicalDocument, FacturaError> {
        val out = Emission()
        out.open(FACTURA, mapOf(ID_ATTRIBUTE.name to ROOT_ID, VERSION_ATTRIBUTE.name to SCHEMA_VERSION))
        writeTaxInfo(out, factura.taxInfo)
        checkAccessKeyAgreement(out, factura.taxInfo, factura.invoiceInfo.issueDate)
        writeInvoiceInfo(out, factura.invoiceInfo)
        writeLines(out, factura.lines)
        writeRefunds(out, factura.refunds)
        writeWithholdings(out, factura.withholdings)
        writeNegotiable(out, factura.negotiableEmail)
        writeFiscalMachine(out, factura.fiscalMachine)
        writeAdditionalInfo(out, factura.additionalFields)
        out.close()
        out.error?.let { return Outcome.Err(it) }
        return Outcome.Ok(out.document())
    }

    // --- sections ---------------------------------------------------------------------------------

    private fun writeTaxInfo(out: Emission, info: TaxInfo) {
        out.open(INFO_TRIBUTARIA)
        out.code(AMBIENTE, info.environmentCode)
        out.code(TIPO_EMISION, info.emissionTypeCode)
        out.text(RAZON_SOCIAL, info.legalName)
        out.optional(NOMBRE_COMERCIAL, info.tradeName)
        out.text(RUC, info.ruc)
        out.text(CLAVE_ACCESO, info.accessKey.digits)
        out.code(COD_DOC, info.documentTypeCode)
        out.text(ESTAB, info.establishmentCode)
        out.text(PTO_EMI, info.emissionPointCode)
        out.text(SECUENCIAL, info.sequential)
        out.text(DIR_MATRIZ, info.headOfficeAddress)
        out.optional(AGENTE_RETENCION, info.withholdingAgentResolution)
        out.optional(CONTRIBUYENTE_RIMPE, info.rimpeRegimeLegend)
        out.close()
    }

    /**
     * Refuses a `claveAcceso` that was cut for another document. The key shares eight segments with
     * the document: the issue date, `codDoc`, `ruc`, `ambiente`, `estab`, `ptoEmi`, `secuencial` and
     * `tipoEmision`. They are compared in key order and the first disagreement is reported, by element
     * name only. The eight-digit numeric code is the issuer's free choice and nothing in the document
     * carries it, so it is not checked.
     *
     * This runs after [writeTaxInfo], so a field's own format error, which is more specific, is
     * reported first, and before `infoFactura`, so nothing is signed on the strength of a wrong key.
     */
    private fun checkAccessKeyAgreement(out: Emission, info: TaxInfo, issueDate: LocalDate) {
        if (out.error != null) return
        val key = info.accessKey
        val siblings = listOf(
            FECHA_EMISION to (issueDate.format(KEY_ISSUE_DATE) == key.issueDateSegment),
            COD_DOC to (info.documentTypeCode == key.documentTypeSegment),
            RUC to (info.ruc == key.issuerRucSegment),
            AMBIENTE to (info.environmentCode == key.environmentDigit.toString()),
            ESTAB to (info.establishmentCode == key.establishmentSegment),
            PTO_EMI to (info.emissionPointCode == key.emissionPointSegment),
            SECUENCIAL to (info.sequential == key.sequentialSegment),
            TIPO_EMISION to (info.emissionTypeCode == key.emissionTypeDigit.toString()),
        )
        val disagreeing = siblings.firstOrNull { !it.second } ?: return
        out.fail(FacturaError.AccessKeyMismatch(CLAVE_ACCESO.name, disagreeing.first.name))
    }

    private fun writeInvoiceInfo(out: Emission, info: InvoiceInfo) {
        out.open(INFO_FACTURA)
        out.text(FECHA_EMISION, info.issueDate.format(ISSUE_DATE))
        out.text(DIR_ESTABLECIMIENTO, info.establishmentAddress)
        out.optional(CONTRIBUYENTE_ESPECIAL, info.specialTaxpayerNumber)
        out.text(OBLIGADO_CONTABILIDAD, if (info.accountingRequired) "SI" else "NO")
        writeExportInfo(out, info.exportInfo)
        out.code(TIPO_IDENTIFICACION_COMPRADOR, info.buyerIdentificationTypeCode)
        out.optional(GUIA_REMISION, info.dispatchNote)
        out.text(RAZON_SOCIAL_COMPRADOR, info.buyerLegalName)
        out.text(IDENTIFICACION_COMPRADOR, info.buyerIdentification)
        out.optional(DIRECCION_COMPRADOR, info.buyerAddress)
        out.amount(TOTAL_SIN_IMPUESTOS, info.totalWithoutTaxes)
        out.optionalAmount(TOTAL_SUBSIDIO, info.totalSubsidy)
        out.optional(INCO_TERM_TOTAL_SIN_IMPUESTOS, info.incoTermTotalWithoutTaxes)
        out.amount(TOTAL_DESCUENTO, info.totalDiscount)
        writeRefundTotals(out, info.refundTotals)
        writeTaxTotals(out, info.taxTotals)
        writeCompensations(out, INVOICE_COMPENSATIONS, info.compensations)
        out.amount(PROPINA, info.tip)
        out.optionalAmount(FLETE_INTERNACIONAL, info.internationalFreight)
        out.optionalAmount(SEGURO_INTERNACIONAL, info.internationalInsurance)
        out.optionalAmount(GASTOS_ADUANEROS, info.customsCharges)
        out.optionalAmount(GASTOS_TRANSPORTE_OTROS, info.otherTransportCharges)
        out.amount(IMPORTE_TOTAL, info.grandTotal)
        out.text(MONEDA, info.currency)
        out.optional(PLACA, info.licensePlate)
        writePayments(out, info.payments)
        out.optionalAmount(VALOR_RET_IVA, info.ivaWithheldValue)
        out.optionalAmount(VALOR_RET_RENTA, info.incomeTaxWithheldValue)
        out.close()
    }

    private fun writeExportInfo(out: Emission, export: ExportInfo?) {
        if (export == null) return
        out.text(COMERCIO_EXTERIOR, export.code)
        out.optional(INCO_TERM_FACTURA, export.incoTerm)
        out.optional(LUGAR_INCO_TERM, export.incoTermPlace)
        out.optional(PAIS_ORIGEN, export.countryOfOrigin)
        out.optional(PUERTO_EMBARQUE, export.portOfLoading)
        out.optional(PUERTO_DESTINO, export.portOfDestination)
        out.optional(PAIS_DESTINO, export.countryOfDestination)
        out.optional(PAIS_ADQUISICION, export.countryOfAcquisition)
    }

    private fun writeRefundTotals(out: Emission, totals: RefundTotals?) {
        if (totals == null) return
        out.text(COD_DOC_REEMBOLSO, totals.documentTypeCode)
        out.amount(TOTAL_COMPROBANTES_REEMBOLSO, totals.documentCount)
        out.amount(TOTAL_BASE_IMPONIBLE_REEMBOLSO, totals.taxableBase)
        out.amount(TOTAL_IMPUESTO_REEMBOLSO, totals.taxAmount)
    }

    private fun writeTaxTotals(out: Emission, totals: List<TaxTotal>) {
        if (!out.nonEmpty(TOTAL_CON_IMPUESTOS, totals)) return
        out.open(TOTAL_CON_IMPUESTOS)
        for (total in totals) {
            out.open(TOTAL_IMPUESTO)
            out.code(TOTAL_IMPUESTO_CODIGO, total.taxCode)
            out.text(TOTAL_IMPUESTO_CODIGO_PORCENTAJE, total.rateCode)
            out.optionalAmount(DESCUENTO_ADICIONAL, total.additionalDiscount)
            out.amount(TOTAL_IMPUESTO_BASE_IMPONIBLE, total.taxableBase)
            out.optionalRate(TOTAL_IMPUESTO_TARIFA, total.rate)
            out.amount(TOTAL_IMPUESTO_VALOR, total.amount)
            out.optionalAmount(VALOR_DEVOLUCION_IVA, total.ivaRefundValue)
            out.close()
        }
        out.close()
    }

    /** One compensation block; `compensaciones` and `compensacionesReembolso` share the item type. */
    private fun writeCompensations(out: Emission, facets: CompensationFacets, compensations: List<Compensation>) {
        if (compensations.isEmpty()) return
        out.open(facets.container)
        for (compensation in compensations) {
            out.open(facets.item)
            out.text(facets.code, compensation.code)
            out.rate(facets.rate, compensation.rate)
            out.amount(facets.amount, compensation.amount)
            out.close()
        }
        out.close()
    }

    private fun writePayments(out: Emission, payments: List<Payment>) {
        if (!out.nonEmpty(PAGOS, payments)) return
        out.open(PAGOS)
        for (payment in payments) {
            out.open(PAGO)
            out.text(FORMA_PAGO, payment.methodCode)
            out.amount(TOTAL, payment.total)
            out.optionalAmount(PLAZO, payment.term)
            out.optional(UNIDAD_TIEMPO, payment.termUnit)
            out.close()
        }
        out.close()
    }

    private fun writeLines(out: Emission, lines: List<InvoiceLine>) {
        if (!out.nonEmpty(DETALLES, lines)) return
        out.open(DETALLES)
        for (line in lines) {
            out.open(DETALLE)
            out.optional(CODIGO_PRINCIPAL, line.mainCode)
            out.optional(CODIGO_AUXILIAR, line.auxiliaryCode)
            out.text(DESCRIPCION, line.description)
            out.optional(UNIDAD_MEDIDA, line.unitOfMeasure)
            out.quantity(CANTIDAD, line.quantity)
            out.unitPrice(PRECIO_UNITARIO, line.unitPrice)
            out.optionalUnitPrice(PRECIO_SIN_SUBSIDIO, line.priceWithoutSubsidy)
            out.amount(DESCUENTO, line.discount)
            out.amount(PRECIO_TOTAL_SIN_IMPUESTO, line.totalWithoutTaxes)
            writeLineDetails(out, line.additionalDetails)
            writeLineTaxes(out, line.taxes)
            out.close()
        }
        out.close()
    }

    private fun writeLineDetails(out: Emission, details: List<LineDetailField>) {
        if (details.isEmpty()) return
        val cap = cap(DET_ADICIONAL)
        if (details.size > cap) {
            out.fail(FacturaError.TooManyEntries(DETALLES_ADICIONALES.name, cap, details.size))
            return
        }
        out.open(DETALLES_ADICIONALES)
        for (detail in details) {
            out.openWithAttributes(
                DET_ADICIONAL,
                listOf(DET_ADICIONAL_NOMBRE to detail.name, DET_ADICIONAL_VALOR to detail.value),
            )
            out.close()
        }
        out.close()
    }

    private fun writeLineTaxes(out: Emission, taxes: List<LineTax>) {
        if (!out.nonEmpty(IMPUESTOS, taxes)) return
        out.open(IMPUESTOS)
        for (tax in taxes) {
            out.open(IMPUESTO)
            out.code(IMPUESTO_CODIGO, tax.taxCode)
            out.text(IMPUESTO_CODIGO_PORCENTAJE, tax.rateCode)
            out.rate(IMPUESTO_TARIFA, tax.rate)
            out.amount(IMPUESTO_BASE_IMPONIBLE, tax.taxableBase)
            out.amount(IMPUESTO_VALOR, tax.amount)
            out.close()
        }
        out.close()
    }

    private fun writeRefunds(out: Emission, refunds: List<Refund>) {
        if (refunds.isEmpty()) return
        out.open(REEMBOLSOS)
        for (refund in refunds) {
            out.open(REEMBOLSO_DETALLE)
            out.text(TIPO_IDENTIFICACION_PROVEEDOR_REEMBOLSO, refund.providerIdentificationTypeCode)
            out.text(IDENTIFICACION_PROVEEDOR_REEMBOLSO, refund.providerIdentification)
            out.text(COD_PAIS_PAGO_PROVEEDOR_REEMBOLSO, refund.providerPaymentCountryCode)
            out.text(TIPO_PROVEEDOR_REEMBOLSO, refund.providerTypeCode)
            out.text(REEMBOLSO_COD_DOC, refund.documentTypeCode)
            out.text(ESTAB_DOC_REEMBOLSO, refund.establishmentCode)
            out.text(PTO_EMI_DOC_REEMBOLSO, refund.emissionPointCode)
            out.text(SECUENCIAL_DOC_REEMBOLSO, refund.sequential)
            out.text(FECHA_EMISION_DOC_REEMBOLSO, refund.issueDate.format(ISSUE_DATE))
            out.text(NUMERO_AUTORIZACION_DOC_REEMB, refund.authorizationNumber)
            writeRefundTaxes(out, refund.taxes)
            writeCompensations(out, REFUND_COMPENSATIONS, refund.compensations)
            out.close()
        }
        out.close()
    }

    private fun writeRefundTaxes(out: Emission, taxes: List<RefundTax>) {
        if (!out.nonEmpty(DETALLE_IMPUESTOS, taxes)) return
        out.open(DETALLE_IMPUESTOS)
        for (tax in taxes) {
            out.open(DETALLE_IMPUESTO)
            out.code(DETALLE_IMPUESTO_CODIGO, tax.taxCode)
            out.text(DETALLE_IMPUESTO_CODIGO_PORCENTAJE, tax.rateCode)
            out.rate(DETALLE_IMPUESTO_TARIFA, tax.rate)
            out.amount(BASE_IMPONIBLE_REEMBOLSO, tax.taxableBase)
            out.amount(IMPUESTO_REEMBOLSO, tax.amount)
            out.close()
        }
        out.close()
    }

    private fun writeWithholdings(out: Emission, withholdings: List<Withholding>) {
        if (withholdings.isEmpty()) return
        out.open(RETENCIONES)
        for (withholding in withholdings) {
            out.open(RETENCION)
            out.text(RETENCION_CODIGO, withholding.code)
            out.text(RETENCION_CODIGO_PORCENTAJE, withholding.rateCode)
            out.rate(RETENCION_TARIFA, withholding.rate)
            out.amount(RETENCION_VALOR, withholding.amount)
            out.close()
        }
        out.close()
    }

    private fun writeNegotiable(out: Emission, email: String?) {
        if (email == null) return
        out.open(TIPO_NEGOCIABLE)
        out.text(CORREO, email)
        out.close()
    }

    private fun writeFiscalMachine(out: Emission, machine: FiscalMachine?) {
        if (machine == null) return
        out.open(MAQUINA_FISCAL)
        out.text(MARCA, machine.brand)
        out.text(MODELO, machine.model)
        out.text(SERIE, machine.serial)
        out.close()
    }

    private fun writeAdditionalInfo(out: Emission, fields: List<AdditionalField>) {
        if (fields.isEmpty()) return
        val cap = cap(CAMPO_ADICIONAL)
        if (fields.size > cap) {
            out.fail(FacturaError.TooManyEntries(INFO_ADICIONAL.name, cap, fields.size))
            return
        }
        out.open(INFO_ADICIONAL)
        for (field in fields) {
            out.openWithAttributes(CAMPO_ADICIONAL, listOf(CAMPO_ADICIONAL_NOMBRE to field.name))
            out.text(field.value, CAMPO_ADICIONAL)
            out.close()
        }
        out.close()
    }

    /**
     * The first decimal facet of [facet] that [value], as written, breaks; `null` when it breaks none.
     * The order is `minInclusive`, `minExclusive`, `maxInclusive`, `maxExclusive`, `totalDigits`,
     * `fractionDigits`.
     *
     * Digits are counted the way the schema counts them: integer digits without leading zeros plus
     * fraction digits without trailing zeros, so `99999999999999.00` has fourteen. A facet the
     * schema does not state is `null` and is not judged. The failure names the element, the facet
     * and the schema's limit, never [value].
     */
    internal fun decimalViolation(facet: Facet, value: BigDecimal): FacturaError.DecimalOutOfRange? {
        fun refuse(name: String, limit: String) = FacturaError.DecimalOutOfRange(facet.name, name, limit)
        facet.minInclusive?.let { if (value < it) return refuse("minInclusive", it.toPlainString()) }
        facet.minExclusive?.let { if (value <= it) return refuse("minExclusive", it.toPlainString()) }
        facet.maxInclusive?.let { if (value > it) return refuse("maxInclusive", it.toPlainString()) }
        facet.maxExclusive?.let { if (value >= it) return refuse("maxExclusive", it.toPlainString()) }
        val digits = schemaDigits(value)
        facet.totalDigits?.let { if (digits.integer + digits.fraction > it) return refuse("totalDigits", it.toString()) }
        facet.fractionDigits?.let { if (digits.fraction > it) return refuse("fractionDigits", it.toString()) }
        return null
    }

    private class Digits(val integer: Int, val fraction: Int)

    /** The digit counts of [value] in the schema's value space, where trailing fraction zeros do not exist. */
    private fun schemaDigits(value: BigDecimal): Digits {
        if (value.signum() == 0) return Digits(integer = 1, fraction = 0)
        val stripped = value.stripTrailingZeros()
        val scale = stripped.scale()
        return if (scale > 0) {
            Digits(integer = maxOf(stripped.precision() - scale, 0), fraction = scale)
        } else {
            Digits(integer = stripped.precision() - scale, fraction = 0)
        }
    }

    /**
     * One emission in progress: the emitter, and the first failure found.
     *
     * Once [error] is set nothing further is written, and [FacturaWriter.write] discards the
     * emitter instead of asking it for a document. The structural `open`/`close` pairs still run
     * so the emitter stays balanced; only content is suppressed.
     *
     * Validation lives here rather than in a separate pass so that the schema's field list is
     * written down exactly once. A second pass would be a second place to forget a field.
     */
    private class Emission {

        private val xml = CanonicalEmitter()
        var error: FacturaError? = null
            private set

        fun fail(reason: FacturaError) {
            if (error == null) error = reason
        }

        /**
         * Guards a container that the schema requires to carry at least one child: when [list] is
         * empty it records [FacturaError.EmptyCollection] for [container] and returns false, and the
         * caller returns before opening the container, so nothing is left half-open.
         */
        fun nonEmpty(container: Facet, list: List<*>): Boolean {
            if (list.isNotEmpty()) return true
            fail(FacturaError.EmptyCollection(container.name))
            return false
        }

        fun open(element: Facet, attributes: Map<String, String> = emptyMap()) {
            xml.open(element.name, attributes)
        }

        /** Opens [element] with validated attributes, each judged exactly as an element's text is. */
        fun openWithAttributes(element: Facet, attributes: List<Pair<Facet, String>>) {
            val checked = LinkedHashMap<String, String>()
            for ((attribute, value) in attributes) {
                if (check(attribute, value)) checked[attribute.name] = value
            }
            if (checked.size != attributes.size) {
                // A rejected attribute still needs a balanced element for the close that follows.
                xml.open(element.name)
                return
            }
            xml.open(element.name, checked)
        }

        fun close() {
            xml.close()
        }

        fun document(): CanonicalDocument = xml.document(prolog = true)

        /** A required element: it must be present and must carry content. */
        fun text(element: Facet, value: String) {
            if (!check(element, value)) return
            xml.open(element.name).text(value).close()
        }

        /** Text content judged against [element]'s facets but written into the already-open element. */
        fun text(value: String, element: Facet) {
            if (!check(element, value)) return
            xml.text(value)
        }

        /** An optional element: absent when `null`, and never written empty. */
        fun optional(element: Facet, value: String?) {
            if (value == null) return
            text(element, value)
        }

        /** A required code: [element] carries the catalogue its value is judged against. */
        fun code(element: Facet, value: String) {
            checkNotNull(element.catalogue) { "${element.path} has no catalogue" }
            text(element, value)
        }

        fun amount(element: Facet, value: BigDecimal) = decimal(element, Money.amount(value))

        fun optionalAmount(element: Facet, value: BigDecimal?) {
            if (value == null) return
            amount(element, value)
        }

        fun rate(element: Facet, value: BigDecimal) = decimal(element, Money.rate(value))

        fun optionalRate(element: Facet, value: BigDecimal?) {
            if (value == null) return
            rate(element, value)
        }

        fun quantity(element: Facet, value: BigDecimal) = decimal(element, Money.quantity(value))

        fun unitPrice(element: Facet, value: BigDecimal) = decimal(element, Money.unitPrice(value))

        fun optionalUnitPrice(element: Facet, value: BigDecimal?) {
            if (value == null) return
            unitPrice(element, value)
        }

        /**
         * A decimal element: [rendered] is [Money]'s plain text, which is judged against the schema's
         * decimal facets exactly as it will be read, before anything is opened.
         */
        private fun decimal(element: Facet, rendered: String) {
            if (error != null) return
            val violation = decimalViolation(element, BigDecimal(rendered))
            if (violation != null) {
                fail(violation)
                return
            }
            text(element, rendered)
        }

        /** Whether [value] may be written for [element]; records the first reason it may not. */
        private fun check(element: Facet, value: String): Boolean {
            if (error != null) return false
            if (value.isBlank()) {
                fail(FacturaError.BlankField(element.name))
                return false
            }
            // Lengths are counted in UTF-16 code units, which is how the JDK schema validator measures
            // a string facet; the owner settled this in Phase 3 (D-13). With the sanitiser refusing
            // non-BMP characters (D-34) the unit and code-point counts no longer diverge for accepted
            // text, but the rule is recorded anyway.
            val length = value.length
            val maximum = element.maxLength
            if (maximum != null && length > maximum) {
                fail(FacturaError.FieldTooLong(element.name, maximum, length))
                return false
            }
            val minimum = element.minLength
            if (minimum != null && length < minimum) {
                fail(FacturaError.FieldTooShort(element.name, minimum, length))
                return false
            }
            if (!CanonicalEmitter.isLegalXmlText(value)) {
                fail(FacturaError.IllegalCharacter(element.name))
                return false
            }
            // A catalogue is inside the pattern language, so a code outside it keeps its own name
            // and a malformed one is only ever reported as malformed.
            val catalogue = element.catalogue
            if (catalogue != null && value !in catalogue) {
                fail(FacturaError.CodeOutsideCatalogue(element.name, catalogue))
                return false
            }
            if (!element.acceptsFormat(value)) {
                fail(FacturaError.InvalidFormat(element.name))
                return false
            }
            return true
        }
    }
}
