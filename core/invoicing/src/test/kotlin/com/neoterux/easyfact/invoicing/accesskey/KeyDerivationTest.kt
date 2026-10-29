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

import com.neoterux.easyfact.invoicing.factura.FacturaWriter
import com.neoterux.easyfact.invoicing.factura.SampleFactura
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.math.BigInteger
import java.security.MessageDigest
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The D-11 numeric code.
 *
 * The golden value was computed outside the JVM, with `shasum -a 256` for the digest
 * (`e41d116a5d51a2a0705cdba42ca760d846813379c29b1b84e745eeba49efecc4`) and an independent
 * `python3` reduction of that digest modulo 10^8, before it was written here. It is not copied from
 * the production code's own output.
 */
class KeyDerivationTest {

    private val issueDate: LocalDate = LocalDate.of(2026, 10, 27)

    private fun code(
        ruc: String = SyntheticIdentifiers.RUC,
        establishment: String = "001",
        emissionPoint: String = "003",
        sequential: Long = 990064L,
        date: LocalDate = issueDate,
    ): String = KeyDerivation.numericCode(ruc, establishment, emissionPoint, sequential, date)

    private fun key(
        establishment: String = "001",
        emissionPoint: String = "003",
        sequential: Long = 990064L,
        date: LocalDate = issueDate,
    ): AccessKey = when (
        val outcome = KeyDerivation.accessKey(SyntheticIdentifiers.RUC, "1", establishment, emissionPoint, sequential, date)
    ) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw AssertionError("expected a key, got ${outcome.error}")
    }

    /**
     * A second formulation of the derivation: the digest is rendered as hexadecimal text and parsed
     * back as a base-16 integer, instead of being read as an unsigned big-endian byte array.
     */
    private fun independentCode(ruc: String, establishment: String, emissionPoint: String, sequential: Long, date: LocalDate): String {
        val ddMMyyyy = "%02d%02d%04d".format(date.dayOfMonth, date.monthValue, date.year)
        val material = "$ruc|$establishment$emissionPoint|${"%09d".format(sequential)}|$ddMMyyyy"
        val hex = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        return "%08d".format(BigInteger(hex, 16).mod(BigInteger("100000000")))
    }

    @Test
    fun theGoldenVectorMatchesTheValueComputedOutsideTheJvm() {
        assertEquals("58844868", code())
    }

    @Test
    fun theResultIsAlwaysExactlyEightAsciiDigitsAndKeepsItsLeadingZeros() {
        val codes = (1L..200L).map { code(sequential = it) }

        assertTrue(codes.all { it.length == 8 && it.all { c -> c in '0'..'9' } })
        assertTrue("padding is exercised by at least one code that starts with zero", codes.any { it.startsWith("0") })
    }

    @Test
    fun theCodeAgreesWithAnIndependentFormulationOfTheSameDerivation() {
        for (sequential in listOf(1L, 99L, 123L, 990064L, 999_999_999L)) {
            assertEquals(
                independentCode(SyntheticIdentifiers.RUC, "001", "003", sequential, issueDate),
                code(sequential = sequential),
            )
        }
    }

    @Test
    fun changingAnySingleInputChangesTheCode() {
        val baseline = code()

        assertNotEquals(baseline, code(ruc = SyntheticIdentifiers.RUC_BASE + "002"))
        assertNotEquals(baseline, code(establishment = "002"))
        assertNotEquals(baseline, code(emissionPoint = "004"))
        assertNotEquals(baseline, code(sequential = 990065L))
        assertNotEquals(baseline, code(date = issueDate.plusDays(1)))
    }

    @Test
    fun theSameIdentityAlwaysGivesTheSameCode() {
        assertEquals(code(), code())
    }

    @Test
    fun theMaterialJoinsTheEstablishmentAndTheEmissionPointWithNoSeparatorBetweenThem() {
        // Pinned to the documented string `ruc|estab+ptoEmi|sequential9|ddMMyyyy`, formatted by the
        // second formulation above rather than by the production code.
        assertEquals(
            independentCode(SyntheticIdentifiers.RUC, "001", "003", 7L, issueDate),
            code(sequential = 7L),
        )
        assertNotEquals(code(establishment = "001", emissionPoint = "003"), code(establishment = "003", emissionPoint = "001"))
    }

    @Test
    fun theAccessKeyCarriesTheDerivedCodeAtDigits39To46() {
        val digits = key().digits

        assertEquals(code(), digits.substring(39, 47))
        assertEquals("58844868", digits.substring(39, 47))
    }

    @Test
    fun theAccessKeyBuilderTakesIdentityOnlyAndNoDocumentContent() {
        val builder = KeyDerivation::class.java.methods.single { it.name == "accessKey" }

        assertEquals(
            listOf(String::class.java, String::class.java, String::class.java, String::class.java, java.lang.Long.TYPE, LocalDate::class.java),
            builder.parameterTypes.toList(),
        )
    }

    @Test
    fun theWriterAcceptsADocumentWhoseIdentityMatchesTheDerivedKey() {
        val base = SampleFactura.factura()
        val establishment = "002"
        val emissionPoint = "004"
        val sequential = 555L
        val date = LocalDate.of(2026, 11, 2)
        val factura = base.copy(
            taxInfo = base.taxInfo.copy(
                accessKey = key(establishment, emissionPoint, sequential, date),
                establishmentCode = establishment,
                emissionPointCode = emissionPoint,
                sequential = "000000555",
            ),
            invoiceInfo = base.invoiceInfo.copy(issueDate = date),
        )

        val outcome = FacturaWriter.write(factura)

        assertTrue("expected the writer to accept the key, got $outcome", outcome is Outcome.Ok)
    }
}
