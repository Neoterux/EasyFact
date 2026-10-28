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
 *   also a silent data loss at the domain boundary.
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
 * **The vendored XSD is the authority, not this file.** The element order, the `maxLength` facets
 * in [Facet] and the catalogues in [Catalogues] are read from the Ficha Técnica and from the schema
 * excerpt recorded in the phase research. Plan 02-09 vendors `factura_V1.1.0.xsd` and fails the
 * build on a mismatch; where the two disagree, the schema wins and this file is corrected.
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
     * [maxLength] is the schema's own `maxLength` facet, copied by hand and compared with the
     * schema by a test on every build; it is `null` where the schema states none. [guardLength] is
     * a bound that belongs to the writer alone and is never compared with the schema. It exists
     * only where the schema states no `maxLength`, so that an element governed by a pattern or an
     * enumeration, or by a decimal form [Money] already guarantees, is still bounded. A decimal
     * element carries a guard that stands in until the schema's decimal facets are enforced, and a
     * value within a guard may still be rejected by a pattern the schema applies.
     *
     * A value is bounded by [maxLength] when there is one, otherwise by [guardLength].
     */
    internal data class Facet(
        val path: String,
        val kind: Kind,
        val maxLength: Int? = null,
        val minLength: Int? = null,
        val maxOccurs: Int? = null,
        val guardLength: Int? = null,
    ) {
        val name: String get() = path.substringAfterLast('/').removePrefix("@")
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
    private fun bounded(path: String, maxLength: Int, minLength: Int? = 1, maxOccurs: Int? = null): Facet =
        register(Facet(path, Kind.TEXT, maxLength = maxLength, minLength = minLength, maxOccurs = maxOccurs))

    /** A text facet whose schema type states no `maxLength`; the writer keeps its own bound. */
    private fun guarded(path: String, guardLength: Int): Facet =
        register(Facet(path, Kind.TEXT, guardLength = guardLength))

    private fun decimal(path: String): Facet =
        register(Facet(path, Kind.DECIMAL, guardLength = AMOUNT_MAX))

    private const val AMOUNT_MAX = 32
    private const val TEXT_MAX = 300

    private const val ROOT_ID = "comprobante"
    private const val SCHEMA_VERSION = "1.1.0"

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
    private val ID_ATTRIBUTE = guarded("$ROOT/@id", ROOT_ID.length)
    private val VERSION_ATTRIBUTE = guarded("$ROOT/@version", SCHEMA_VERSION.length)

    // --- infoTributaria ------------------------------------------------------------------------

    private val INFO_TRIBUTARIA = container(TAX_INFO)
    private val AMBIENTE = guarded("$TAX_INFO/ambiente", 1)
    private val TIPO_EMISION = guarded("$TAX_INFO/tipoEmision", 1)
    private val RAZON_SOCIAL = bounded("$TAX_INFO/razonSocial", 300)
    private val NOMBRE_COMERCIAL = bounded("$TAX_INFO/nombreComercial", 300)
    private val RUC = guarded("$TAX_INFO/ruc", 13)
    private val CLAVE_ACCESO = guarded("$TAX_INFO/claveAcceso", 49)
    private val COD_DOC = guarded("$TAX_INFO/codDoc", 2)
    private val ESTAB = guarded("$TAX_INFO/estab", 3)
    private val PTO_EMI = guarded("$TAX_INFO/ptoEmi", 3)
    private val SECUENCIAL = guarded("$TAX_INFO/secuencial", 9)
    private val DIR_MATRIZ = bounded("$TAX_INFO/dirMatriz", 300)
    private val AGENTE_RETENCION = bounded("$TAX_INFO/agenteRetencion", 8, minLength = null)
    private val CONTRIBUYENTE_RIMPE = guarded("$TAX_INFO/contribuyenteRimpe", TEXT_MAX)

    // --- infoFactura ---------------------------------------------------------------------------

    private val INFO_FACTURA = container(INVOICE_INFO)
    private val FECHA_EMISION = guarded("$INVOICE_INFO/fechaEmision", 10)
    private val DIR_ESTABLECIMIENTO = bounded("$INVOICE_INFO/dirEstablecimiento", 300)
    private val CONTRIBUYENTE_ESPECIAL = bounded("$INVOICE_INFO/contribuyenteEspecial", 13, minLength = 3)
    private val OBLIGADO_CONTABILIDAD = guarded("$INVOICE_INFO/obligadoContabilidad", 2)
    private val COMERCIO_EXTERIOR = guarded("$INVOICE_INFO/comercioExterior", 20)
    private val INCO_TERM_FACTURA = bounded("$INVOICE_INFO/incoTermFactura", 10)
    private val LUGAR_INCO_TERM = bounded("$INVOICE_INFO/lugarIncoTerm", 300)
    private val PAIS_ORIGEN = guarded("$INVOICE_INFO/paisOrigen", 3)
    private val PUERTO_EMBARQUE = bounded("$INVOICE_INFO/puertoEmbarque", 300)
    private val PUERTO_DESTINO = bounded("$INVOICE_INFO/puertoDestino", 300)
    private val PAIS_DESTINO = guarded("$INVOICE_INFO/paisDestino", 3)
    private val PAIS_ADQUISICION = guarded("$INVOICE_INFO/paisAdquisicion", 3)
    private val TIPO_IDENTIFICACION_COMPRADOR = guarded("$INVOICE_INFO/tipoIdentificacionComprador", 2)
    private val GUIA_REMISION = guarded("$INVOICE_INFO/guiaRemision", 17)
    private val RAZON_SOCIAL_COMPRADOR = bounded("$INVOICE_INFO/razonSocialComprador", 300)
    private val IDENTIFICACION_COMPRADOR = bounded("$INVOICE_INFO/identificacionComprador", 20)
    private val DIRECCION_COMPRADOR = bounded("$INVOICE_INFO/direccionComprador", 300)
    private val TOTAL_SIN_IMPUESTOS = decimal("$INVOICE_INFO/totalSinImpuestos")
    private val TOTAL_SUBSIDIO = decimal("$INVOICE_INFO/totalSubsidio")
    private val INCO_TERM_TOTAL_SIN_IMPUESTOS = bounded("$INVOICE_INFO/incoTermTotalSinImpuestos", 10)
    private val TOTAL_DESCUENTO = decimal("$INVOICE_INFO/totalDescuento")
    private val COD_DOC_REEMBOLSO = guarded("$INVOICE_INFO/codDocReembolso", 3)
    private val TOTAL_COMPROBANTES_REEMBOLSO = decimal("$INVOICE_INFO/totalComprobantesReembolso")
    private val TOTAL_BASE_IMPONIBLE_REEMBOLSO = decimal("$INVOICE_INFO/totalBaseImponibleReembolso")
    private val TOTAL_IMPUESTO_REEMBOLSO = decimal("$INVOICE_INFO/totalImpuestoReembolso")
    private val PROPINA = decimal("$INVOICE_INFO/propina")
    private val FLETE_INTERNACIONAL = decimal("$INVOICE_INFO/fleteInternacional")
    private val SEGURO_INTERNACIONAL = decimal("$INVOICE_INFO/seguroInternacional")
    private val GASTOS_ADUANEROS = decimal("$INVOICE_INFO/gastosAduaneros")
    private val GASTOS_TRANSPORTE_OTROS = decimal("$INVOICE_INFO/gastosTransporteOtros")
    private val IMPORTE_TOTAL = decimal("$INVOICE_INFO/importeTotal")
    private val MONEDA = bounded("$INVOICE_INFO/moneda", 15)
    private val PLACA = bounded("$INVOICE_INFO/placa", 20)
    private val VALOR_RET_IVA = decimal("$INVOICE_INFO/valorRetIva")
    private val VALOR_RET_RENTA = decimal("$INVOICE_INFO/valorRetRenta")

    // --- tax totals --------------------------------------------------------------------------------

    private val TOTAL_CON_IMPUESTOS = container("$INVOICE_INFO/totalConImpuestos")
    private val TOTAL_IMPUESTO = container(TAX_TOTAL)
    private val TOTAL_IMPUESTO_CODIGO = bounded("$TAX_TOTAL/codigo", 1)
    private val TOTAL_IMPUESTO_CODIGO_PORCENTAJE = bounded("$TAX_TOTAL/codigoPorcentaje", 4)
    private val DESCUENTO_ADICIONAL = decimal("$TAX_TOTAL/descuentoAdicional")
    private val TOTAL_IMPUESTO_BASE_IMPONIBLE = decimal("$TAX_TOTAL/baseImponible")
    // Spelt out in full: an optional element that used to be left out of the document.
    private val TOTAL_IMPUESTO_TARIFA = decimal("factura/infoFactura/totalConImpuestos/totalImpuesto/tarifa")
    private val TOTAL_IMPUESTO_VALOR = decimal("$TAX_TOTAL/valor")
    private val VALOR_DEVOLUCION_IVA = decimal("$TAX_TOTAL/valorDevolucionIva")

    // --- compensations (the infoFactura block and the refund block share one item type) -------------

    private val COMPENSACIONES = container("$INVOICE_INFO/compensaciones")
    private val COMPENSACION = container(COMPENSATION)
    private val COMPENSACION_CODIGO = guarded("$COMPENSATION/codigo", 4)
    private val COMPENSACION_TARIFA = decimal("$COMPENSATION/tarifa")
    private val COMPENSACION_VALOR = decimal("$COMPENSATION/valor")

    private val COMPENSACIONES_REEMBOLSO = container("$REFUND/compensacionesReembolso")
    private val COMPENSACION_REEMBOLSO = container(REFUND_COMPENSATION)
    private val COMPENSACION_REEMBOLSO_CODIGO = guarded("$REFUND_COMPENSATION/codigo", 4)
    private val COMPENSACION_REEMBOLSO_TARIFA = decimal("$REFUND_COMPENSATION/tarifa")
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
    private val FORMA_PAGO = guarded("$PAYMENT/formaPago", 2)
    private val TOTAL = decimal("$PAYMENT/total")
    private val PLAZO = decimal("$PAYMENT/plazo")
    private val UNIDAD_TIEMPO = bounded("$PAYMENT/unidadTiempo", 10)

    // --- detalles -----------------------------------------------------------------------------

    private val DETALLES = container("$ROOT/detalles")
    private val DETALLE = container(LINE)
    private val CODIGO_PRINCIPAL = bounded("$LINE/codigoPrincipal", 25)
    private val CODIGO_AUXILIAR = bounded("$LINE/codigoAuxiliar", 25)
    private val DESCRIPCION = bounded("$LINE/descripcion", 300)
    private val UNIDAD_MEDIDA = bounded("$LINE/unidadMedida", 50)
    private val CANTIDAD = decimal("$LINE/cantidad")
    private val PRECIO_UNITARIO = decimal("$LINE/precioUnitario")
    private val PRECIO_SIN_SUBSIDIO = decimal("$LINE/precioSinSubsidio")
    private val DESCUENTO = decimal("$LINE/descuento")
    private val PRECIO_TOTAL_SIN_IMPUESTO = decimal("$LINE/precioTotalSinImpuesto")
    private val DETALLES_ADICIONALES = container("$LINE/detallesAdicionales")
    private val DET_ADICIONAL = container(LINE_DETAIL, maxOccurs = 3)
    private val DET_ADICIONAL_NOMBRE = bounded("$LINE_DETAIL/@nombre", 300)
    private val DET_ADICIONAL_VALOR = bounded("$LINE_DETAIL/@valor", 300)
    private val IMPUESTOS = container("$LINE/impuestos")
    private val IMPUESTO = container(LINE_TAX)
    private val IMPUESTO_CODIGO = bounded("$LINE_TAX/codigo", 1)
    private val IMPUESTO_CODIGO_PORCENTAJE = bounded("$LINE_TAX/codigoPorcentaje", 4)
    private val IMPUESTO_TARIFA = decimal("$LINE_TAX/tarifa")
    private val IMPUESTO_BASE_IMPONIBLE = decimal("$LINE_TAX/baseImponible")
    private val IMPUESTO_VALOR = decimal("$LINE_TAX/valor")

    // --- reembolsos ------------------------------------------------------------------------------

    private val REEMBOLSOS = container("$ROOT/reembolsos")
    private val REEMBOLSO_DETALLE = container(REFUND)
    private val TIPO_IDENTIFICACION_PROVEEDOR_REEMBOLSO = guarded("$REFUND/tipoIdentificacionProveedorReembolso", 2)
    private val IDENTIFICACION_PROVEEDOR_REEMBOLSO = bounded("$REFUND/identificacionProveedorReembolso", 20)
    private val COD_PAIS_PAGO_PROVEEDOR_REEMBOLSO = guarded("$REFUND/codPaisPagoProveedorReembolso", 3)
    private val TIPO_PROVEEDOR_REEMBOLSO = guarded("$REFUND/tipoProveedorReembolso", 2)
    private val REEMBOLSO_COD_DOC = guarded("$REFUND/codDocReembolso", 3)
    private val ESTAB_DOC_REEMBOLSO = guarded("$REFUND/estabDocReembolso", 3)
    private val PTO_EMI_DOC_REEMBOLSO = guarded("$REFUND/ptoEmiDocReembolso", 3)
    private val SECUENCIAL_DOC_REEMBOLSO = guarded("$REFUND/secuencialDocReembolso", 9)
    private val FECHA_EMISION_DOC_REEMBOLSO = guarded("$REFUND/fechaEmisionDocReembolso", 10)
    private val NUMERO_AUTORIZACION_DOC_REEMB = bounded("$REFUND/numeroautorizacionDocReemb", 49, minLength = 10)
    private val DETALLE_IMPUESTOS = container("$REFUND/detalleImpuestos")
    private val DETALLE_IMPUESTO = container(REFUND_TAX)
    private val DETALLE_IMPUESTO_CODIGO = guarded("$REFUND_TAX/codigo", 4)
    private val DETALLE_IMPUESTO_CODIGO_PORCENTAJE = guarded("$REFUND_TAX/codigoPorcentaje", 4)
    private val DETALLE_IMPUESTO_TARIFA = decimal("$REFUND_TAX/tarifa")
    private val BASE_IMPONIBLE_REEMBOLSO = decimal("$REFUND_TAX/baseImponibleReembolso")
    private val IMPUESTO_REEMBOLSO = decimal("$REFUND_TAX/impuestoReembolso")

    // --- retenciones -------------------------------------------------------------------------------

    private val RETENCIONES = container("$ROOT/retenciones")
    private val RETENCION = container(WITHHOLDING)
    private val RETENCION_CODIGO = guarded("$WITHHOLDING/codigo", 4)
    private val RETENCION_CODIGO_PORCENTAJE = guarded("$WITHHOLDING/codigoPorcentaje", 4)
    private val RETENCION_TARIFA = decimal("$WITHHOLDING/tarifa")
    private val RETENCION_VALOR = decimal("$WITHHOLDING/valor")

    // --- negotiable, fiscal machine and infoAdicional -----------------------------

    private val TIPO_NEGOCIABLE = container("$ROOT/tipoNegociable")
    private val CORREO = bounded("$ROOT/tipoNegociable/correo", 100)
    private val MAQUINA_FISCAL = container(FISCAL_MACHINE)
    private val MARCA = bounded("$FISCAL_MACHINE/marca", 30)
    private val MODELO = bounded("$FISCAL_MACHINE/modelo", 30)
    private val SERIE = bounded("$FISCAL_MACHINE/serie", 30)
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

    /**
     * The catalogues small and stable enough to check here.
     *
     * `codigoPorcentaje` is deliberately absent: the Ficha says to read the rate catalogue from
     * the specification rather than hardcode it, historical codes stay valid for past periods, and
     * a stale set here would reject a correct document. The XSD gate in plan 02-09 is where a
     * catalogue belongs once it is vendored.
     */
    private object Catalogues {
        val ENVIRONMENT = setOf("1", "2")
        val EMISSION_TYPE = setOf("1")
        val DOCUMENT_TYPE = setOf("01")
        val BUYER_IDENTIFICATION_TYPE = setOf("04", "05", "06", "07", "08")

        /** `2` IVA, `3` ICE, `5` IRBPNR. */
        val TAX = setOf("2", "3", "5")
    }

    private val ISSUE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

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
        out.code(AMBIENTE, info.environmentCode, Catalogues.ENVIRONMENT)
        out.code(TIPO_EMISION, info.emissionTypeCode, Catalogues.EMISSION_TYPE)
        out.text(RAZON_SOCIAL, info.legalName)
        out.optional(NOMBRE_COMERCIAL, info.tradeName)
        out.text(RUC, info.ruc)
        out.text(CLAVE_ACCESO, info.accessKey)
        out.code(COD_DOC, info.documentTypeCode, Catalogues.DOCUMENT_TYPE)
        out.text(ESTAB, info.establishmentCode)
        out.text(PTO_EMI, info.emissionPointCode)
        out.text(SECUENCIAL, info.sequential)
        out.text(DIR_MATRIZ, info.headOfficeAddress)
        out.optional(AGENTE_RETENCION, info.withholdingAgentResolution)
        out.optional(CONTRIBUYENTE_RIMPE, info.rimpeRegimeLegend)
        out.close()
    }

    private fun writeInvoiceInfo(out: Emission, info: InvoiceInfo) {
        out.open(INFO_FACTURA)
        out.text(FECHA_EMISION, info.issueDate.format(ISSUE_DATE))
        out.text(DIR_ESTABLECIMIENTO, info.establishmentAddress)
        out.optional(CONTRIBUYENTE_ESPECIAL, info.specialTaxpayerNumber)
        out.text(OBLIGADO_CONTABILIDAD, if (info.accountingRequired) "SI" else "NO")
        writeExportInfo(out, info.exportInfo)
        out.code(TIPO_IDENTIFICACION_COMPRADOR, info.buyerIdentificationTypeCode, Catalogues.BUYER_IDENTIFICATION_TYPE)
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
        out.open(TOTAL_CON_IMPUESTOS)
        for (total in totals) {
            out.open(TOTAL_IMPUESTO)
            out.code(TOTAL_IMPUESTO_CODIGO, total.taxCode, Catalogues.TAX)
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
        out.open(IMPUESTOS)
        for (tax in taxes) {
            out.open(IMPUESTO)
            out.code(IMPUESTO_CODIGO, tax.taxCode, Catalogues.TAX)
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
        out.open(DETALLE_IMPUESTOS)
        for (tax in taxes) {
            out.open(DETALLE_IMPUESTO)
            out.code(DETALLE_IMPUESTO_CODIGO, tax.taxCode, Catalogues.TAX)
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

        fun code(element: Facet, value: String, catalogue: Set<String>) {
            if (!check(element, value)) return
            if (value !in catalogue) {
                fail(FacturaError.CodeOutsideCatalogue(element.name, value, catalogue))
                return
            }
            xml.open(element.name).text(value).close()
        }

        fun amount(element: Facet, value: BigDecimal) = text(element, Money.amount(value))

        fun optionalAmount(element: Facet, value: BigDecimal?) {
            if (value == null) return
            amount(element, value)
        }

        fun rate(element: Facet, value: BigDecimal) = text(element, Money.rate(value))

        fun optionalRate(element: Facet, value: BigDecimal?) {
            if (value == null) return
            rate(element, value)
        }

        fun quantity(element: Facet, value: BigDecimal) = text(element, Money.quantity(value))

        fun unitPrice(element: Facet, value: BigDecimal) = text(element, Money.unitPrice(value))

        fun optionalUnitPrice(element: Facet, value: BigDecimal?) {
            if (value == null) return
            unitPrice(element, value)
        }

        /** Whether [value] may be written for [element]; records the first reason it may not. */
        private fun check(element: Facet, value: String): Boolean {
            if (error != null) return false
            if (value.isBlank()) {
                fail(FacturaError.BlankField(element.name))
                return false
            }
            // The schema counts characters, and a supplementary character is two UTF-16 units.
            val length = value.codePointCount(0, value.length)
            val bound = element.maxLength ?: element.guardLength
            if (bound != null && length > bound) {
                fail(FacturaError.FieldTooLong(element.name, bound, length))
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
            return true
        }
    }
}
