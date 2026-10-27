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

package com.neoterux.easyfact.invoicing.accesskey

/**
 * Why an access key could not be built.
 *
 * These values reach logs, so no variant carries the RUC or the key itself: they name the
 * offending field and the measured length, which is enough to find the mistake.
 */
sealed interface AccessKeyError {

    /** The segment named by [field] contains something other than the ASCII digits `0` to `9`. */
    data class SegmentNotNumeric(val field: String) : AccessKeyError

    /** The segment named by [field] has [actual] digits where [expected] are required. */
    data class SegmentWrongLength(val field: String, val expected: Int, val actual: Int) : AccessKeyError

    /** The sequential does not fit in nine digits, or is negative. */
    data class SequentialOutOfRange(val sequential: Long) : AccessKeyError
}
