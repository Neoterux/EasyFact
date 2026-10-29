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

package com.neoterux.easyfact.invoicing.mapping

import com.neoterux.easyfact.invoicing.factura.FacturaWriter

/** RED-phase stub with the final signatures; the lookup arrives in the GREEN commit. */
internal object FieldLimits {

    const val ISSUER_LEGAL_NAME = "factura/infoTributaria/razonSocial"
    const val ISSUER_TRADE_NAME = "factura/infoTributaria/nombreComercial"
    const val HEAD_OFFICE_ADDRESS = "factura/infoTributaria/dirMatriz"
    const val ESTABLISHMENT_ADDRESS = "factura/infoFactura/dirEstablecimiento"
    const val SPECIAL_TAXPAYER = "factura/infoFactura/contribuyenteEspecial"
    const val BUYER_LEGAL_NAME = "factura/infoFactura/razonSocialComprador"
    const val BUYER_IDENTIFICATION = "factura/infoFactura/identificacionComprador"
    const val LINE_CODE = "factura/detalles/detalle/codigoPrincipal"
    const val LINE_DESCRIPTION = "factura/detalles/detalle/descripcion"
    const val ADDITIONAL_FIELD = "factura/infoAdicional/campoAdicional"
    const val ADDITIONAL_FIELD_NAME = "factura/infoAdicional/campoAdicional/@nombre"
    const val CREDIT_TERM_UNIT = "factura/infoFactura/pagos/pago/unidadTiempo"
    const val RIMPE_LEGEND = "factura/infoTributaria/contribuyenteRimpe"

    fun facet(path: String): FacturaWriter.Facet? = null

    fun maxLength(path: String): Int? = null
}
