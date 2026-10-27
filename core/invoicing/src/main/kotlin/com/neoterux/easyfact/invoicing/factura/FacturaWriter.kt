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

import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import com.neoterux.easyfact.invoicing.xml.CanonicalEmitter
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Maps a [Factura] onto canonical XML, in the strict element order of the 1.1.0 schema (which is
 * an `xsd:sequence`: a different order is a different, invalid document).
 *
 * The Spanish strings below are the SRI's element names, kept verbatim because they are protocol
 * identifiers and renaming them would break the mapping to the specification.
 *
 * Scale is applied here, at the serialization boundary, and nowhere earlier: money has two
 * decimals, quantities and unit prices six, always rounded half-up. `toPlainString` never writes
 * an exponent, and it does not depend on the default locale (the date pattern below is pinned to
 * [Locale.ROOT] for the same reason).
 */
object FacturaWriter {

    private const val MONEY_SCALE = 2
    private const val QUANTITY_SCALE = 6
    private const val UNIT_PRICE_SCALE = 6

    private val ISSUE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)

    /** Writes [factura] with the XML declaration. The declaration is kept out of the signed root. */
    fun write(factura: Factura): CanonicalDocument {
        val xml = CanonicalEmitter()
        xml.open("factura", mapOf("id" to "comprobante", "version" to "1.1.0"))
        writeTaxInfo(xml, factura.taxInfo)
        writeInvoiceInfo(xml, factura.invoiceInfo)
        writeLines(xml, factura.lines)
        writeAdditionalInfo(xml, factura.additionalFields)
        xml.close()
        return xml.document(prolog = true)
    }

    private fun writeTaxInfo(xml: CanonicalEmitter, info: TaxInfo) {
        xml.open("infoTributaria")
        element(xml, "ambiente", info.environmentCode)
        element(xml, "tipoEmision", info.emissionTypeCode)
        element(xml, "razonSocial", info.legalName)
        info.tradeName?.let { element(xml, "nombreComercial", it) }
        element(xml, "ruc", info.ruc)
        element(xml, "claveAcceso", info.accessKey)
        element(xml, "codDoc", info.documentTypeCode)
        element(xml, "estab", info.establishmentCode)
        element(xml, "ptoEmi", info.emissionPointCode)
        element(xml, "secuencial", info.sequential)
        element(xml, "dirMatriz", info.headOfficeAddress)
        xml.close()
    }

    private fun writeInvoiceInfo(xml: CanonicalEmitter, info: InvoiceInfo) {
        xml.open("infoFactura")
        element(xml, "fechaEmision", info.issueDate.format(ISSUE_DATE))
        element(xml, "dirEstablecimiento", info.establishmentAddress)
        element(xml, "obligadoContabilidad", if (info.accountingRequired) "SI" else "NO")
        element(xml, "tipoIdentificacionComprador", info.buyerIdentificationTypeCode)
        element(xml, "razonSocialComprador", info.buyerLegalName)
        element(xml, "identificacionComprador", info.buyerIdentification)
        element(xml, "totalSinImpuestos", money(info.totalWithoutTaxes))
        element(xml, "totalDescuento", money(info.totalDiscount))
        xml.open("totalConImpuestos")
        for (total in info.taxTotals) {
            xml.open("totalImpuesto")
            element(xml, "codigo", total.taxCode)
            element(xml, "codigoPorcentaje", total.rateCode)
            element(xml, "baseImponible", money(total.taxableBase))
            element(xml, "valor", money(total.amount))
            xml.close()
        }
        xml.close()
        element(xml, "propina", money(info.tip))
        element(xml, "importeTotal", money(info.grandTotal))
        element(xml, "moneda", info.currency)
        xml.open("pagos")
        for (payment in info.payments) {
            xml.open("pago")
            element(xml, "formaPago", payment.methodCode)
            element(xml, "total", money(payment.total))
            xml.close()
        }
        xml.close()
        xml.close()
    }

    private fun writeLines(xml: CanonicalEmitter, lines: List<InvoiceLine>) {
        xml.open("detalles")
        for (line in lines) {
            xml.open("detalle")
            element(xml, "codigoPrincipal", line.mainCode)
            element(xml, "descripcion", line.description)
            element(xml, "cantidad", scaled(line.quantity, QUANTITY_SCALE))
            element(xml, "precioUnitario", scaled(line.unitPrice, UNIT_PRICE_SCALE))
            element(xml, "descuento", money(line.discount))
            element(xml, "precioTotalSinImpuesto", money(line.totalWithoutTaxes))
            xml.open("impuestos")
            for (tax in line.taxes) {
                xml.open("impuesto")
                element(xml, "codigo", tax.taxCode)
                element(xml, "codigoPorcentaje", tax.rateCode)
                element(xml, "tarifa", money(tax.rate))
                element(xml, "baseImponible", money(tax.taxableBase))
                element(xml, "valor", money(tax.amount))
                xml.close()
            }
            xml.close()
            xml.close()
        }
        xml.close()
    }

    private fun writeAdditionalInfo(xml: CanonicalEmitter, fields: List<AdditionalField>) {
        if (fields.isEmpty()) return
        xml.open("infoAdicional")
        for (field in fields) {
            xml.open("campoAdicional", mapOf("nombre" to field.name)).text(field.value).close()
        }
        xml.close()
    }

    private fun element(xml: CanonicalEmitter, name: String, value: String) {
        xml.open(name).text(value).close()
    }

    private fun money(value: BigDecimal): String = scaled(value, MONEY_SCALE)

    private fun scaled(value: BigDecimal, scale: Int): String = value.setScale(scale, RoundingMode.HALF_UP).toPlainString()
}
