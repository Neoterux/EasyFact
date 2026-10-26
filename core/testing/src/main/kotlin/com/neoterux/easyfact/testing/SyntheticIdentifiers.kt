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

/**
 * Obviously synthetic placeholder identifiers shared by tests in later phases.
 *
 * This repository is public (AGENTS.md section 2.2): nothing here may resemble a real
 * identification number, name or address. If a test needs a structurally valid check digit,
 * it must compute one from [RUC_BASE] rather than paste a real value.
 */
object SyntheticIdentifiers {

    /** Ten repeated nines; the first ten digits of a placeholder RUC. */
    const val RUC_BASE: String = "9999999999"

    /** Placeholder RUC: [RUC_BASE] followed by the usual establishment suffix. */
    const val RUC: String = RUC_BASE + "001"

    const val CUSTOMER_NAME: String = "Test Customer S.A."

    const val BILLING_EMAIL: String = "billing@example.com"
}
