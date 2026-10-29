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

/**
 * The writer's facet table, looked up by schema path, so that the mapping's own guards use the limits
 * the writer enforces and never a second copy of them (D-34, D-13).
 *
 * [FacturaWriter.FACETS] is kept equal to the vendored schema by `FacturaFacetParityTest`, so a limit
 * read here is the schema's limit. No length is written in this file on purpose: a number typed here
 * could drift from the schema without any test noticing. Lengths are counted in UTF-16 code units
 * (D-13), which is how `TextSanitizer` counts them.
 *
 * The path constants are spelt exactly as the facets spell them (attributes as an at-sign segment).
 */
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

    /** Every facet by its schema path, computed once. */
    private val byPath: Map<String, FacturaWriter.Facet> by lazy { FacturaWriter.FACETS.associateBy { it.path } }

    /** The writer's facet at [path], or `null` when the writer has none there. */
    fun facet(path: String): FacturaWriter.Facet? = byPath[path]

    /** The `maxLength` of the facet at [path], or `null` when the path is unknown or the facet states none. */
    fun maxLength(path: String): Int? = byPath[path]?.maxLength
}
