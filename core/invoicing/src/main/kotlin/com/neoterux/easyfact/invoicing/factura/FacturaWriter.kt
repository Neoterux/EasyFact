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
 * The Spanish strings in [E] are the SRI's element and attribute names, kept verbatim because
 * they are protocol identifiers and renaming them would break the mapping to the specification.
 * They are gathered in one place so the mapping is auditable without reading the emission code
 * (AGENTS.md section 2.1), and they are the only Spanish in the package.
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
 * in [E] and the catalogues in [Catalogues] are read from the Ficha Técnica and from the schema
 * excerpt recorded in the phase research. Plan 02-09 vendors `factura_V1.1.0.xsd` and fails the
 * build on a mismatch; where the two disagree, the schema wins and this file is corrected.
 */
object FacturaWriter {

    /**
     * One element or attribute of the schema: the SRI's own name, and the `maxLength` facet that
     * bounds its content.
     *
     * The facet is a guard, not a claim of schema equivalence. A value within it may still be
     * rejected by a pattern facet the XSD gate in plan 02-09 checks; a value beyond it is
     * certainly rejected, and catching that here turns an opaque SRI refusal into a named field.
     * Numeric elements are bounded generously, because their real constraint is a decimal pattern
     * rather than a length, and [Money] already guarantees the form.
     */
    private data class E(val name: String, val maxLength: Int = AMOUNT_MAX)

    private const val AMOUNT_MAX = 32
    private const val TEXT_MAX = 300
    private const val FISCAL_MACHINE_MAX = 30
    private const val UNIT_OF_MEASURE_MAX = 50
    private const val MAX_ADDITIONAL_FIELDS = 15

    // --- factura -------------------------------------------------------------------------------

    private val FACTURA = E("factura")
    private val ID_ATTRIBUTE = "id"
    private val VERSION_ATTRIBUTE = "version"
    private const val ROOT_ID = "comprobante"
    private const val SCHEMA_VERSION = "1.1.0"

    // --- infoTributaria ------------------------------------------------------------------------

    private val INFO_TRIBUTARIA = E("infoTributaria")
    private val AMBIENTE = E("ambiente", 1)
    private val TIPO_EMISION = E("tipoEmision", 1)
    private val RAZON_SOCIAL = E("razonSocial", TEXT_MAX)
    private val NOMBRE_COMERCIAL = E("nombreComercial", TEXT_MAX)
    private val RUC = E("ruc", 13)
    private val CLAVE_ACCESO = E("claveAcceso", 49)
    private val COD_DOC = E("codDoc", 2)
    private val ESTAB = E("estab", 3)
    private val PTO_EMI = E("ptoEmi", 3)
    private val SECUENCIAL = E("secuencial", 9)
    private val DIR_MATRIZ = E("dirMatriz", TEXT_MAX)
    private val AGENTE_RETENCION = E("agenteRetencion", 5)
    private val CONTRIBUYENTE_RIMPE = E("contribuyenteRimpe", TEXT_MAX)

    // --- infoFactura ---------------------------------------------------------------------------

    private val INFO_FACTURA = E("infoFactura")
    private val FECHA_EMISION = E("fechaEmision", 10)
    private val DIR_ESTABLECIMIENTO = E("dirEstablecimiento", TEXT_MAX)
    private val CONTRIBUYENTE_ESPECIAL = E("contribuyenteEspecial", 13)
    private val OBLIGADO_CONTABILIDAD = E("obligadoContabilidad", 2)
    private val COMERCIO_EXTERIOR = E("comercioExterior", 20)
    private val INCO_TERM_FACTURA = E("incoTermFactura", 5)
    private val LUGAR_INCO_TERM = E("lugarIncoTerm", TEXT_MAX)
    private val PAIS_ORIGEN = E("paisOrigen", 3)
    private val PUERTO_EMBARQUE = E("puertoEmbarque", TEXT_MAX)
    private val PUERTO_DESTINO = E("puertoDestino", TEXT_MAX)
    private val PAIS_DESTINO = E("paisDestino", 3)
    private val PAIS_ADQUISICION = E("paisAdquisicion", 3)
    private val TIPO_IDENTIFICACION_COMPRADOR = E("tipoIdentificacionComprador", 2)
    private val GUIA_REMISION = E("guiaRemision", 17)
    private val RAZON_SOCIAL_COMPRADOR = E("razonSocialComprador", TEXT_MAX)
    private val IDENTIFICACION_COMPRADOR = E("identificacionComprador", 20)
    private val DIRECCION_COMPRADOR = E("direccionComprador", TEXT_MAX)
    private val TOTAL_SIN_IMPUESTOS = E("totalSinImpuestos")
    private val TOTAL_SUBSIDIO = E("totalSubsidio")
    private val INCO_TERM_TOTAL_SIN_IMPUESTOS = E("incoTermTotalSinImpuestos", 5)
    private val TOTAL_DESCUENTO = E("totalDescuento")
    private val COD_DOC_REEMBOLSO = E("codDocReembolso", 2)
    private val TOTAL_COMPROBANTES_REEMBOLSO = E("totalComprobantesReembolso")
    private val TOTAL_BASE_IMPONIBLE_REEMBOLSO = E("totalBaseImponibleReembolso")
    private val TOTAL_IMPUESTO_REEMBOLSO = E("totalImpuestoReembolso")
    private val PROPINA = E("propina")
    private val FLETE_INTERNACIONAL = E("fleteInternacional")
    private val SEGURO_INTERNACIONAL = E("seguroInternacional")
    private val GASTOS_ADUANEROS = E("gastosAduaneros")
    private val GASTOS_TRANSPORTE_OTROS = E("gastosTransporteOtros")
    private val IMPORTE_TOTAL = E("importeTotal")
    private val MONEDA = E("moneda", 15)
    private val PLACA = E("placa", 20)
    private val VALOR_RET_IVA = E("valorRetIva")
    private val VALOR_RET_RENTA = E("valorRetRenta")

    // --- tax blocks ------------------------------------------------------------------------------

    private val TOTAL_CON_IMPUESTOS = E("totalConImpuestos")
    private val TOTAL_IMPUESTO = E("totalImpuesto")
    private val CODIGO = E("codigo", 4)
    private val CODIGO_PORCENTAJE = E("codigoPorcentaje", 4)
    private val DESCUENTO_ADICIONAL = E("descuentoAdicional")
    private val BASE_IMPONIBLE = E("baseImponible")
    private val VALOR = E("valor")
    private val VALOR_DEVOLUCION_IVA = E("valorDevolucionIva")
    private val TARIFA = E("tarifa")
    private val COMPENSACIONES = E("compensaciones")
    private val COMPENSACION = E("compensacion")
    private val RETENCIONES = E("retenciones")
    private val RETENCION = E("retencion")

    // --- payment and refund blocks ---------------------------------------------------------------

    private val PAGOS = E("pagos")
    private val PAGO = E("pago")
    private val FORMA_PAGO = E("formaPago", 2)
    private val TOTAL = E("total")
    private val PLAZO = E("plazo")
    private val UNIDAD_TIEMPO = E("unidadTiempo", 10)
    private val REEMBOLSOS = E("reembolsos")
    private val REEMBOLSO_DETALLE = E("reembolsoDetalle")
    private val TIPO_IDENTIFICACION_PROVEEDOR_REEMBOLSO = E("tipoIdentificacionProveedorReembolso", 2)
    private val IDENTIFICACION_PROVEEDOR_REEMBOLSO = E("identificacionProveedorReembolso", 20)
    private val COD_PAIS_PAGO_PROVEEDOR_REEMBOLSO = E("codPaisPagoProveedorReembolso", 3)
    private val TIPO_PROVEEDOR_REEMBOLSO = E("tipoProveedorReembolso", 2)
    private val ESTAB_DOC_REEMBOLSO = E("estabDocReembolso", 3)
    private val PTO_EMI_DOC_REEMBOLSO = E("ptoEmiDocReembolso", 3)
    private val SECUENCIAL_DOC_REEMBOLSO = E("secuencialDocReembolso", 9)
    private val FECHA_EMISION_DOC_REEMBOLSO = E("fechaEmisionDocReembolso", 10)
    private val NUMERO_AUTORIZACION_DOC_REEMB = E("numeroautorizacionDocReemb", 49)
    private val DETALLE_IMPUESTOS = E("detalleImpuestos")
    private val DETALLE_IMPUESTO = E("detalleImpuesto")
    private val BASE_IMPONIBLE_REEMBOLSO = E("baseImponibleReembolso")
    private val IMPUESTO_REEMBOLSO = E("impuestoReembolso")
    private val COMPENSACIONES_REEMBOLSO = E("compensacionesReembolso")
    private val COMPENSACION_REEMBOLSO = E("compensacionReembolso")

    // --- detalles -----------------------------------------------------------------------------

    private val DETALLES = E("detalles")
    private val DETALLE = E("detalle")
    private val CODIGO_PRINCIPAL = E("codigoPrincipal", 25)
    private val CODIGO_AUXILIAR = E("codigoAuxiliar", 25)
    private val DESCRIPCION = E("descripcion", TEXT_MAX)
    private val UNIDAD_MEDIDA = E("unidadMedida", UNIT_OF_MEASURE_MAX)
    private val CANTIDAD = E("cantidad")
    private val PRECIO_UNITARIO = E("precioUnitario")
    private val PRECIO_SIN_SUBSIDIO = E("precioSinSubsidio")
    private val DESCUENTO = E("descuento")
    private val PRECIO_TOTAL_SIN_IMPUESTO = E("precioTotalSinImpuesto")
    private val DETALLES_ADICIONALES = E("detallesAdicionales")
    private val DET_ADICIONAL = E("detAdicional")
    private val IMPUESTOS = E("impuestos")
    private val IMPUESTO = E("impuesto")

    // --- negotiable, fiscal machine and infoAdicional -----------------------------

    private val TIPO_NEGOCIABLE = E("tipoNegociable")
    private val CORREO = E("correo", TEXT_MAX)
    private val MAQUINA_FISCAL = E("maquinaFiscal")
    private val MARCA = E("marca", FISCAL_MACHINE_MAX)
    private val MODELO = E("modelo", FISCAL_MACHINE_MAX)
    private val SERIE = E("serie", FISCAL_MACHINE_MAX)
    private val INFO_ADICIONAL = E("infoAdicional")
    private val CAMPO_ADICIONAL = E("campoAdicional")
    private val NOMBRE_ATTRIBUTE = E("nombre", TEXT_MAX)
    private val VALOR_ATTRIBUTE = E("valor", TEXT_MAX)

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
        out.open(FACTURA, mapOf(ID_ATTRIBUTE to ROOT_ID, VERSION_ATTRIBUTE to SCHEMA_VERSION))
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
        writeCompensations(out, COMPENSACIONES, COMPENSACION, info.compensations)
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
            out.code(CODIGO, total.taxCode, Catalogues.TAX)
            out.text(CODIGO_PORCENTAJE, total.rateCode)
            out.optionalAmount(DESCUENTO_ADICIONAL, total.additionalDiscount)
            out.amount(BASE_IMPONIBLE, total.taxableBase)
            out.optionalRate(TARIFA, total.rate)
            out.amount(VALOR, total.amount)
            out.optionalAmount(VALOR_DEVOLUCION_IVA, total.ivaRefundValue)
            out.close()
        }
        out.close()
    }

    /** One compensation block; `compensaciones` and `compensacionesReembolso` share the item type. */
    private fun writeCompensations(out: Emission, container: E, item: E, compensations: List<Compensation>) {
        if (compensations.isEmpty()) return
        out.open(container)
        for (compensation in compensations) {
            out.open(item)
            out.text(CODIGO, compensation.code)
            out.rate(TARIFA, compensation.rate)
            out.amount(VALOR, compensation.amount)
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
        out.open(DETALLES_ADICIONALES)
        for (detail in details) {
            out.openWithAttributes(
                DET_ADICIONAL,
                listOf(NOMBRE_ATTRIBUTE to detail.name, VALOR_ATTRIBUTE to detail.value),
            )
            out.close()
        }
        out.close()
    }

    private fun writeLineTaxes(out: Emission, taxes: List<LineTax>) {
        out.open(IMPUESTOS)
        for (tax in taxes) {
            out.open(IMPUESTO)
            out.code(CODIGO, tax.taxCode, Catalogues.TAX)
            out.text(CODIGO_PORCENTAJE, tax.rateCode)
            out.rate(TARIFA, tax.rate)
            out.amount(BASE_IMPONIBLE, tax.taxableBase)
            out.amount(VALOR, tax.amount)
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
            out.text(COD_DOC_REEMBOLSO, refund.documentTypeCode)
            out.text(ESTAB_DOC_REEMBOLSO, refund.establishmentCode)
            out.text(PTO_EMI_DOC_REEMBOLSO, refund.emissionPointCode)
            out.text(SECUENCIAL_DOC_REEMBOLSO, refund.sequential)
            out.text(FECHA_EMISION_DOC_REEMBOLSO, refund.issueDate.format(ISSUE_DATE))
            out.text(NUMERO_AUTORIZACION_DOC_REEMB, refund.authorizationNumber)
            writeRefundTaxes(out, refund.taxes)
            writeCompensations(out, COMPENSACIONES_REEMBOLSO, COMPENSACION_REEMBOLSO, refund.compensations)
            out.close()
        }
        out.close()
    }

    private fun writeRefundTaxes(out: Emission, taxes: List<RefundTax>) {
        out.open(DETALLE_IMPUESTOS)
        for (tax in taxes) {
            out.open(DETALLE_IMPUESTO)
            out.code(CODIGO, tax.taxCode, Catalogues.TAX)
            out.text(CODIGO_PORCENTAJE, tax.rateCode)
            out.rate(TARIFA, tax.rate)
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
            out.text(CODIGO, withholding.code)
            out.text(CODIGO_PORCENTAJE, withholding.rateCode)
            out.rate(TARIFA, withholding.rate)
            out.amount(VALOR, withholding.amount)
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
        if (fields.size > MAX_ADDITIONAL_FIELDS) {
            out.fail(FacturaError.TooManyEntries(INFO_ADICIONAL.name, MAX_ADDITIONAL_FIELDS, fields.size))
            return
        }
        out.open(INFO_ADICIONAL)
        for (field in fields) {
            out.openWithAttributes(CAMPO_ADICIONAL, listOf(NOMBRE_ATTRIBUTE to field.name))
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

        fun open(element: E, attributes: Map<String, String> = emptyMap()) {
            xml.open(element.name, attributes)
        }

        /** Opens [element] with validated attributes, each judged exactly as an element's text is. */
        fun openWithAttributes(element: E, attributes: List<Pair<E, String>>) {
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
        fun text(element: E, value: String) {
            if (!check(element, value)) return
            xml.open(element.name).text(value).close()
        }

        /** Text content judged against [element]'s facets but written into the already-open element. */
        fun text(value: String, element: E) {
            if (!check(element, value)) return
            xml.text(value)
        }

        /** An optional element: absent when `null`, and never written empty. */
        fun optional(element: E, value: String?) {
            if (value == null) return
            text(element, value)
        }

        fun code(element: E, value: String, catalogue: Set<String>) {
            if (!check(element, value)) return
            if (value !in catalogue) {
                fail(FacturaError.CodeOutsideCatalogue(element.name, value, catalogue))
                return
            }
            xml.open(element.name).text(value).close()
        }

        fun amount(element: E, value: BigDecimal) = text(element, Money.amount(value))

        fun optionalAmount(element: E, value: BigDecimal?) {
            if (value == null) return
            amount(element, value)
        }

        fun rate(element: E, value: BigDecimal) = text(element, Money.rate(value))

        fun optionalRate(element: E, value: BigDecimal?) {
            if (value == null) return
            rate(element, value)
        }

        fun quantity(element: E, value: BigDecimal) = text(element, Money.quantity(value))

        fun unitPrice(element: E, value: BigDecimal) = text(element, Money.unitPrice(value))

        fun optionalUnitPrice(element: E, value: BigDecimal?) {
            if (value == null) return
            unitPrice(element, value)
        }

        /** Whether [value] may be written for [element]; records the first reason it may not. */
        private fun check(element: E, value: String): Boolean {
            if (error != null) return false
            if (value.isBlank()) {
                fail(FacturaError.BlankField(element.name))
                return false
            }
            if (value.length > element.maxLength) {
                fail(FacturaError.FieldTooLong(element.name, element.maxLength, value.length))
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
