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

import com.neoterux.easyfact.invoicing.accesskey.AccessKey
import com.neoterux.easyfact.invoicing.accesskey.KeyDerivation
import com.neoterux.easyfact.invoicing.factura.FacturaError
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.testing.SyntheticIdNumbers
import com.neoterux.easyfact.testing.SyntheticIdentifiers
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROADMAP SC2 (SRI-02): a corrected document keeps its access key.
 *
 * The SRI wants a rejected document resubmitted under the same key and sequential, so the key must come
 * from identity alone. [KeyDerivation.accessKey] has no parameter that can carry document content, and the
 * writer refuses a key that disagrees with the document it is written into; these tests exercise both ends
 * with requests that differ in everything except identity. Assertion messages name the case and never print
 * a key, which embeds the issuer RUC.
 */
class KeyInvarianceTest {

    private fun derive(
        sequential: Long = MapperFixtures.DEFAULT_SEQUENTIAL,
        date: LocalDate = MapperFixtures.issueDate,
        establishment: String = "001",
        emissionPoint: String = "002",
    ): AccessKey = when (
        val outcome = KeyDerivation.accessKey(
            issuerRuc = SyntheticIdentifiers.RUC,
            environmentCode = Environment.TEST.code,
            establishment = establishment,
            emissionPoint = emissionPoint,
            sequential = sequential,
            issueDate = date,
        )
    ) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> throw AssertionError("expected a key, got ${outcome.error}")
    }

    @Test
    fun requestsDifferingOnlyInContentDeriveTheSameKeyAndBothMapUnderIt() {
        val first = MapperFixtures.request()
        val second = MapperFixtures.request(
            buyer = MapperFixtures.buyer(
                identification = SyntheticIdNumbers.cedula(2),
                legalName = "Other Customer S.A.",
                billingEmail = "other@example.com",
                address = "Avenida Falsa 456",
            ),
            lines = listOf(
                MapperFixtures.line(code = "X-9", description = "Another service", quantity = "3", unitPrice = "12.5", ivaRateCode = "0"),
                MapperFixtures.line(code = null, description = "Second line", quantity = "2", unitPrice = "7", ivaRateCode = "4"),
            ),
            templateFields = listOf(TemplateField("Campo", "Valor")),
        )
        val derived = derive()

        assertEquals("the two requests carry different keys", first.accessKey, second.accessKey)
        assertEquals("the request key is not the derived one", derived, first.accessKey)
        val firstXml = MapperFixtures.xml(MapperFixtures.mappedOk("first request", first))
        val secondXml = MapperFixtures.xml(MapperFixtures.mappedOk("second request", second))
        assertTrue("first document lacks the key", firstXml.contains("<claveAcceso>${derived.digits}</claveAcceso>"))
        assertTrue("second document lacks the key", secondXml.contains("<claveAcceso>${derived.digits}</claveAcceso>"))
        assertNotEquals("the two documents are identical", firstXml, secondXml)
    }

    @Test
    fun anAmountChangeAloneKeepsTheKey() {
        val original = MapperFixtures.request(lines = listOf(MapperFixtures.line(unitPrice = "100")))
        val corrected = MapperFixtures.request(lines = listOf(MapperFixtures.line(unitPrice = "120")))

        assertEquals(original.accessKey, corrected.accessKey)
        assertEquals(derive(), original.accessKey)
        MapperFixtures.mappedOk("original amount", original)
        MapperFixtures.mappedOk("corrected amount", corrected)
    }

    @Test
    fun aDifferentSequentialChangesTheKey() {
        assertNotEquals(derive(sequential = 123L), derive(sequential = 124L))
    }

    @Test
    fun aDifferentIssueDateChangesTheKey() {
        assertNotEquals(derive(date = LocalDate.of(2026, 10, 27)), derive(date = LocalDate.of(2026, 10, 28)))
    }

    @Test
    fun aDifferentEstablishmentChangesTheKey() {
        assertNotEquals(derive(establishment = "001"), derive(establishment = "002"))
    }

    @Test
    fun aDifferentEmissionPointChangesTheKey() {
        assertNotEquals(derive(emissionPoint = "002"), derive(emissionPoint = "003"))
    }

    @Test
    fun aKeyDerivedForAnotherSequentialIsRefusedByTheWriter() {
        val request = MapperFixtures.request(
            sequential = 123L,
            accessKey = MapperFixtures.key(sequential = 124L),
        )

        val refusal = MapperFixtures.refusal("key of another sequential", request)

        assertTrue("expected a writer refusal", refusal is MappingError.Writer)
        assertTrue(
            "expected AccessKeyMismatch",
            (refusal as MappingError.Writer).error is FacturaError.AccessKeyMismatch,
        )
    }

    @Test
    fun aKeyDerivedForAnotherDateIsRefusedByTheWriter() {
        val request = MapperFixtures.request(accessKey = MapperFixtures.key(date = LocalDate.of(2026, 10, 28)))

        val refusal = MapperFixtures.refusal("key of another date", request)

        assertTrue("expected a writer refusal", refusal is MappingError.Writer)
        assertTrue(
            "expected AccessKeyMismatch",
            (refusal as MappingError.Writer).error is FacturaError.AccessKeyMismatch,
        )
    }

    @Test
    fun theAccessKeyRefusalNamesElementsAndNeverDigits() {
        val request = MapperFixtures.request(accessKey = MapperFixtures.key(sequential = 124L))
        val rendered = MapperFixtures.refusal("key rendering", request).toString()

        assertTrue("the refusal renders the key", !rendered.contains(MapperFixtures.key(sequential = 124L).digits))
        assertTrue("the refusal renders the RUC", !rendered.contains(SyntheticIdentifiers.RUC))
    }
}
