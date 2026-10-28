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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A list container is never written empty. Each case starts from a valid synthetic fixture and
 * empties exactly one list: a container the schema requires is refused with
 * [FacturaError.EmptyCollection] and yields no octets.
 */
class FacturaEmptyContainerTest {

    @Test
    fun aFacturaWithNoLinesIsRefusedAndYieldsNoDocument() {
        val outcome = FacturaWriter.write(SampleFactura.factura().copy(lines = emptyList()))
        assertEquals(Outcome.Err(FacturaError.EmptyCollection("detalles")), outcome)
    }
}
