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

/**
 * The free-form blocks of a `factura`: `infoAdicional` / `campoAdicional`, a line's
 * `detallesAdicionales` / `detAdicional` and `tipoNegociable`.
 *
 * Type and property names are English (AGENTS.md section 2.1); the Spanish element and attribute
 * names live in [FacturaWriter] as string constants and nowhere else.
 */

/**
 * One `campoAdicional` of `infoAdicional`: a name carried as an attribute and a value carried as
 * text.
 *
 * The Ficha caps `infoAdicional` at fifteen entries; the writer rejects a sixteenth with
 * [FacturaError.TooManyEntries] rather than emitting a document the SRI will return.
 */
data class AdditionalField(val name: String, val value: String)

/**
 * One `detAdicional` of a line's `detallesAdicionales`. Both parts are attributes, so the element
 * itself carries no text.
 */
data class LineDetailField(val name: String, val value: String)
