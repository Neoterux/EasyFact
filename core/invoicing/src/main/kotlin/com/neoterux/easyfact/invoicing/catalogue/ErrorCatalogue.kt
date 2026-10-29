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

package com.neoterux.easyfact.invoicing.catalogue

import com.neoterux.easyfact.invoicing.accesskey.AccessKeyError
import com.neoterux.easyfact.invoicing.factura.FacturaError
import com.neoterux.easyfact.invoicing.identification.IdentificationVerdict
import com.neoterux.easyfact.invoicing.mapping.MappingError
import com.neoterux.easyfact.invoicing.mapping.WithholdingAgentError
import com.neoterux.easyfact.invoicing.result.EngineWarning
import com.neoterux.easyfact.invoicing.result.SigningError
import com.neoterux.easyfact.invoicing.signing.RehydrationError
import com.neoterux.easyfact.invoicing.soap.ResponseProblem
import com.neoterux.easyfact.invoicing.soap.SriMessage
import com.neoterux.easyfact.invoicing.text.SanitizerError
import com.neoterux.easyfact.invoicing.totals.BuyerGuardError
import com.neoterux.easyfact.invoicing.totals.TotalsError

/** How serious a received message is. */
enum class Severity { ERROR, WARNING }

/** A received message beside its catalogue entry. */
data class ExplainedMessage(val entry: CatalogueEntry, val message: SriMessage, val severity: Severity)

/** Placeholder mappings until the catalogue is wired. */
object ErrorCatalogue {
    fun entryFor(error: FacturaError): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(error: AccessKeyError): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(error: RehydrationError): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(error: SigningError): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(error: TotalsError): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(error: BuyerGuardError): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(error: SanitizerError): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(error: MappingError): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(error: WithholdingAgentError): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(verdict: IdentificationVerdict): CatalogueEntry? = CatalogueEntry.ENGINE_BUG
    fun entryFor(warning: EngineWarning): CatalogueEntry = CatalogueEntry.ENGINE_BUG
    fun entryFor(problem: ResponseProblem): CatalogueEntry = CatalogueEntry.ENGINE_BUG

    fun explain(message: SriMessage): ExplainedMessage =
        ExplainedMessage(CatalogueEntry.SRI_UNKNOWN_ERROR, message, Severity.ERROR)
}
