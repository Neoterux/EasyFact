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
import java.math.BigInteger
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The deterministic derivation of an access key's numeric code (D-11) and of the key built on it.
 *
 * **The numeric code comes from identity only.** The Ficha (section 5.10) treats the eight digits as
 * the issuer's free choice, and the SRI requires a rejected document to be resubmitted under the same
 * key and sequential. A code derived from document content (an amount, a line, the customer) would
 * change when the document is corrected and make lawful resubmission impossible. So the parameter
 * lists below accept the issuer RUC, the series, the sequential and the issue date, and nothing a
 * document is made of; the guarantee is the signature of the function, not a convention.
 *
 * The derivation is documented in `docs/sri-electronic-invoicing.md` section 4 and pinned by a golden
 * vector computed outside the JVM. **Persist the key at allocation and never recompute it for an
 * existing row** (D-11): this derivation is a convenience for the moment of allocation, not a way to
 * rediscover a key.
 */
object KeyDerivation {

    private const val NUMERIC_CODE_DIGITS = 8
    private const val SEQUENTIAL_DIGITS = 9
    private const val FACTURA_DOCUMENT_TYPE = "01"
    private const val NORMAL_EMISSION_TYPE = "1"
    private val NUMERIC_CODE_MODULUS: BigInteger = BigInteger.TEN.pow(NUMERIC_CODE_DIGITS)
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT)

    /**
     * The eight-digit numeric code for this identity.
     *
     * Material is the ASCII string `ruc|estab+ptoEmi|sequential9|ddMMyyyy`, hashed with SHA-256; the
     * whole 256-bit digest is read as an unsigned big integer, reduced modulo 10^8 and left-padded
     * with zeros to eight characters. There is no salt.
     *
     * `java.util.HexFormat` is deliberately not used to render the digest anywhere: it is in JDK 17 but
     * absent from the Android API 31 signature, and the Animal Sniffer gate fails the build on it.
     */
    fun numericCode(
        issuerRuc: String,
        establishment: String,
        emissionPoint: String,
        sequential: Long,
        issueDate: LocalDate,
    ): String {
        val material = issuerRuc + "|" + establishment + emissionPoint + "|" +
            sequential.toString().padStart(SEQUENTIAL_DIGITS, '0') + "|" + issueDate.format(DATE)
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.US_ASCII))
        return BigInteger(1, digest).mod(NUMERIC_CODE_MODULUS).toString().padStart(NUMERIC_CODE_DIGITS, '0')
    }

    /**
     * The access key of a factura issued under this identity, or the segment that is wrong.
     *
     * The parameter list is the SC2 guarantee: it cannot accept document content, so none can reach the
     * numeric code. See Ficha section 5.10 and `docs/sri-electronic-invoicing.md` section 4.
     */
    fun accessKey(
        issuerRuc: String,
        environmentCode: String,
        establishment: String,
        emissionPoint: String,
        sequential: Long,
        issueDate: LocalDate,
    ): Outcome<AccessKey, AccessKeyError> = AccessKey.of(
        issueDate = issueDate,
        documentTypeCode = FACTURA_DOCUMENT_TYPE,
        issuerRuc = issuerRuc,
        environmentCode = environmentCode,
        establishment = establishment,
        emissionPoint = emissionPoint,
        sequential = sequential,
        numericCode = numericCode(issuerRuc, establishment, emissionPoint, sequential, issueDate),
        emissionTypeCode = NORMAL_EMISSION_TYPE,
    )
}
