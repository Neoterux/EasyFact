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

import com.neoterux.easyfact.invoicing.result.Outcome
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The SRI's 49-digit access key (`claveAcceso`): the document's identity, printed on the RIDE and
 * used as the filename stem of the deliverables.
 *
 * | Digits | Field |
 * | --- | --- |
 * | 8 | issue date, `ddMMyyyy` |
 * | 2 | document type |
 * | 13 | issuer RUC |
 * | 1 | environment |
 * | 6 | series: establishment (3) and emission point (3) |
 * | 9 | sequential, zero-padded |
 * | 8 | numeric code |
 * | 1 | emission type |
 * | 1 | check digit, modulus 11 over the preceding 48 |
 *
 * A key is built from identity alone. After a rejection the SRI requires resubmission under the
 * same key and sequential, so a key derived from document content (an amount, a line item, the
 * customer) would make lawful resubmission impossible once the content is corrected. [of] is
 * deliberately incapable of accepting such a value: it takes the issue date, the issuer's RUC, the
 * series, the sequential, the numeric code and the two type codes, and nothing else.
 *
 * [toString] does not render the digits: they contain the issuer's RUC, and objects reach logs.
 */
class AccessKey private constructor(val digits: String) {

    /** The environment digit, at character index 23: `1` test, `2` production. */
    val environmentDigit: Char get() = digits[ENVIRONMENT_INDEX]

    /** The issuer RUC segment, at character indices 10 through 22. */
    val issuerRucSegment: String get() = digits.substring(RUC_START, ENVIRONMENT_INDEX)

    override fun equals(other: Any?): Boolean = other is AccessKey && other.digits == digits

    override fun hashCode(): Int = digits.hashCode()

    override fun toString(): String = "AccessKey(redacted)"

    companion object {
        private const val ENVIRONMENT_INDEX = 23
        private const val RUC_START = 10
        private const val KEY_LENGTH_WITHOUT_CHECK_DIGIT = 48
        private const val MAX_SEQUENTIAL = 999_999_999L
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT)

        /**
         * Builds a key, or says which segment is wrong. Segments are checked in the order they
         * appear in the key, and the first problem found is reported.
         */
        fun of(
            issueDate: LocalDate,
            documentTypeCode: String,
            issuerRuc: String,
            environmentCode: String,
            establishment: String,
            emissionPoint: String,
            sequential: Long,
            numericCode: String,
            emissionTypeCode: String,
        ): Outcome<AccessKey, AccessKeyError> {
            val date = issueDate.format(DATE)
            segmentProblem("issueDate", date, 8)?.let { return Outcome.Err(it) }
            segmentProblem("documentTypeCode", documentTypeCode, 2)?.let { return Outcome.Err(it) }
            segmentProblem("issuerRuc", issuerRuc, 13)?.let { return Outcome.Err(it) }
            segmentProblem("environmentCode", environmentCode, 1)?.let { return Outcome.Err(it) }
            segmentProblem("establishment", establishment, 3)?.let { return Outcome.Err(it) }
            segmentProblem("emissionPoint", emissionPoint, 3)?.let { return Outcome.Err(it) }
            if (sequential < 0 || sequential > MAX_SEQUENTIAL) return Outcome.Err(AccessKeyError.SequentialOutOfRange(sequential))
            segmentProblem("numericCode", numericCode, 8)?.let { return Outcome.Err(it) }
            segmentProblem("emissionTypeCode", emissionTypeCode, 1)?.let { return Outcome.Err(it) }

            val withoutCheckDigit = date + documentTypeCode + issuerRuc + environmentCode + establishment + emissionPoint +
                sequential.toString().padStart(9, '0') + numericCode + emissionTypeCode
            return Outcome.Ok(AccessKey(withoutCheckDigit + checkDigit(withoutCheckDigit)))
        }

        /**
         * The modulus-11 check digit of the first 48 digits. Weights 2, 3, 4, 5, 6, 7 cycle from
         * the rightmost digit leftwards; the digit is `11 - (sum mod 11)`, with 11 written as 0
         * and 10 written as 1. Those two substitutions are the most commonly mis-implemented
         * rules in the scheme.
         *
         * @throws IllegalArgumentException if [first48] is not exactly 48 ASCII digits
         */
        fun checkDigit(first48: String): Int {
            require(first48.length == KEY_LENGTH_WITHOUT_CHECK_DIGIT && first48.all { it in '0'..'9' }) {
                "expected exactly 48 ASCII digits, got ${first48.length} characters"
            }
            var sum = 0
            var weight = 2
            for (index in first48.indices.reversed()) {
                sum += (first48[index] - '0') * weight
                weight = if (weight == 7) 2 else weight + 1
            }
            return when (val raw = 11 - sum % 11) {
                11 -> 0
                10 -> 1
                else -> raw
            }
        }

        /** `null` when [value] is exactly [length] ASCII digits. `Char.isDigit` is not used: it accepts non-ASCII digits. */
        private fun segmentProblem(field: String, value: String, length: Int): AccessKeyError? = when {
            value.any { it !in '0'..'9' } -> AccessKeyError.SegmentNotNumeric(field)
            value.length != length -> AccessKeyError.SegmentWrongLength(field, length, value.length)
            else -> null
        }
    }
}
