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

import com.neoterux.easyfact.invoicing.probe.ProbeDocumentFixture
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the octets [FacturaWriter] emits for three synthetic fixtures, as lowercase-hex SHA-256.
 *
 * These octets are the bytes that get signed and transmitted. The constants describe the writer's
 * output for valid input: a deliberate output change updates them in the same change and states why;
 * any other change that turns this test red is a regression in the bytes that get signed.
 *
 * The constants were computed while `FacturaWriter.kt` was byte-identical to the copy snapshotted
 * before the facet-parity gap plans began, so a later plan that keeps valid output unchanged keeps
 * this test green without touching it.
 */
class FacturaWriterOutputStabilityTest {

    private fun sha256Hex(octets: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(octets).joinToString("") { "%02x".format(it) }

    @Test
    fun theMinimalSampleDocumentIsByteStable() {
        assertEquals(MINIMAL_SAMPLE_SHA256, sha256Hex(SampleFactura.document().octets()))
    }

    @Test
    fun theFullyPopulatedSampleDocumentIsByteStable() {
        val octets = SampleFactura.write(SampleFactura.fullyPopulated()).octets()
        assertEquals(FULLY_POPULATED_SHA256, sha256Hex(octets))
    }

    @Test
    fun theProbeDocumentIsByteStable() {
        assertEquals(PROBE_DOCUMENT_SHA256, sha256Hex(ProbeDocumentFixture.document().octets()))
    }

    private companion object {
        const val MINIMAL_SAMPLE_SHA256 = "04bd91c1f7e93a536896b42d32030b2199d1d1b1e6cc22589ce61ece48f224b3"
        const val FULLY_POPULATED_SHA256 = "3c8733b6743430d17998bcbfe484cb88068f9d0654fb997eb41f88fb6cfa70a4"
        const val PROBE_DOCUMENT_SHA256 = "e8c74a4057b3f970e7b9ca483c7ff10e326ee2b242d7e550f3b2819a6525c65d"
    }
}
