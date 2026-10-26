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

package com.neoterux.easyfact.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticIdentifiersTest {

    @Test
    fun rucBase_isRunOfRepeatedNines() {
        assertTrue(SyntheticIdentifiers.RUC_BASE.isNotEmpty())
        assertTrue(SyntheticIdentifiers.RUC_BASE.all { it == '9' })
    }

    @Test
    fun ruc_isRucBasePlusEstablishmentSuffix() {
        assertEquals(13, SyntheticIdentifiers.RUC.length)
        assertTrue(SyntheticIdentifiers.RUC.startsWith(SyntheticIdentifiers.RUC_BASE))
    }

    @Test
    fun billingEmail_usesReservedExampleDomain() {
        assertTrue(SyntheticIdentifiers.BILLING_EMAIL.endsWith("@example.com"))
    }

    @Test
    fun customerName_isMarkedAsTest() {
        assertTrue(SyntheticIdentifiers.CUSTOMER_NAME.contains("Test"))
    }
}
