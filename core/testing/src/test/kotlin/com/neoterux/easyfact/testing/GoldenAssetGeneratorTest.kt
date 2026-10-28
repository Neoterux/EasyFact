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

import com.neoterux.easyfact.testing.GoldenAssetGenerator.Expectation
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyStore
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast JVM proof that every rejection case of the on-device matrix can only pass for the right reason:
 * its store opens with the control password and is refused with the device password. Nothing is written
 * to disk; the stores are minted in memory with each case's own shape and encoding.
 */
class GoldenAssetGeneratorTest {

    private val notBefore: Instant = Instant.parse("2026-09-27T12:00:00Z")
    private val notAfter: Instant = notBefore.plus(395, ChronoUnit.DAYS)

    private val rejectionCases get() = GoldenAssetGenerator.CASES.filter { it.expectation == Expectation.REJECTS_PASSWORD }

    private fun mint(case: GoldenAssetGenerator.GoldenCase): ByteArray {
        val identity = SyntheticCertificates.rsa2048(case.shape, notBefore, notAfter)
        // The generator protects every store with the default password; the control password must be it.
        return SyntheticCertificates.pkcs12(identity, SyntheticCertificates.DEFAULT_PASSWORD.toCharArray(), case.encoding)
    }

    private fun open(store: ByteArray, password: String): KeyStore =
        KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(store), password.toCharArray()) }

    @Test
    fun thereIsAtLeastOneRejectionCase() {
        assertTrue("no REJECTS_PASSWORD case in the matrix", rejectionCases.isNotEmpty())
    }

    @Test
    fun everyRejectionCaseHasAControlPassword() {
        for (case in rejectionCases) {
            assertNotNull("rejection case ${case.id} has no controlPassword", case.controlPassword)
            assertTrue("rejection case ${case.id} has a blank controlPassword", case.controlPassword!!.isNotBlank())
        }
    }

    @Test
    fun everyRejectionCaseOpensWithItsControlPassword() {
        for (case in rejectionCases) {
            val store = open(mint(case), case.controlPassword!!)
            assertTrue("case ${case.id}: the store opened but holds no entry", store.aliases().hasMoreElements())
        }
    }

    @Test
    fun everyRejectionCaseIsRefusedWithItsDevicePassword() {
        for (case in rejectionCases) {
            assertThrows("case ${case.id}: the device password must be refused", IOException::class.java) {
                open(mint(case), case.devicePassword)
            }
        }
    }

    @Test
    fun theDevicePasswordOfARejectionCaseDiffersFromItsControlPassword() {
        for (case in rejectionCases) {
            assertTrue(
                "case ${case.id}: the wrong password equals the control password, so the case proves nothing",
                case.devicePassword != case.controlPassword,
            )
        }
    }

    @Test
    fun aSigningCaseCarriesNoControlPassword() {
        for (case in GoldenAssetGenerator.CASES.filter { it.expectation == Expectation.SIGNS }) {
            assertEquals("signing case ${case.id} must not carry a controlPassword", null, case.controlPassword)
        }
    }

    @Test
    fun atLeastOneRejectionCaseUsesTheLegacyEncodingWithNoApiFloor() {
        val everywhere = rejectionCases.filter { it.encoding == Pkcs12Encoding.LEGACY_PBE && it.opensFromApi == 0 }
        assertTrue(
            "no rejection case uses LEGACY_PBE with opensFromApi 0, so API 31 has no real password rejection: " +
                rejectionCases.joinToString { "${it.id}=${it.encoding}@${it.opensFromApi}" },
            everywhere.isNotEmpty(),
        )
    }
}
