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

package com.neoterux.easyfact

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.signing.FixedIdSource
import com.neoterux.easyfact.invoicing.signing.IssuerNameFormats
import com.neoterux.easyfact.invoicing.signing.Rfc2253Jdk
import com.neoterux.easyfact.invoicing.signing.SigningIdentity
import com.neoterux.easyfact.invoicing.signing.XadesBesSigner
import com.neoterux.easyfact.invoicing.xml.CanonicalDocument
import com.neoterux.easyfact.invoicing.xml.CanonicalEmitter
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.InvalidKeyException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.OffsetDateTime
import java.util.Properties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * NFR-07: a device loads a PKCS#12, signs a fixed document with a fixed identifier source and signing
 * time, and must produce exactly the bytes the JVM produced for the same inputs.
 *
 * The cases are read from the manifest that `generateGoldenSigningAssets` writes into this test APK's
 * assets, not listed here, so the generator and this test cannot drift apart in what they cover. The
 * stores and expected documents are minted at build time under `build/` and are never tracked.
 *
 * Two outcomes are measurements, and each is a visible failure naming the API level rather than a skip:
 * whether a PKCS#12 encoding opens on this platform at all, and whether the platform renders the issuer
 * name exactly as the JVM does. A failure message carries every difference found in that case, so one
 * run answers all of them. Every case also logs a one-line outcome under the `GoldenBytes` tag.
 *
 * **Per-API-level expectations.** A case that signs may declare, in the generator's case definition, the
 * lowest API level from which it is expected to open (`opensFromApi`, carried in the manifest). Below
 * that level the test does not skip: it asserts the refusal, an `IOException` whose cause chain names the
 * MAC or key failure, so a platform that starts opening the file fails the test and the expectation is
 * revisited. At or above it the case must open and sign byte-identically to the JVM. The only such
 * case is `p12-modern-aes` (PBES2 AES-256 with an HmacSHA256 MAC), measured to be refused on API 31 and
 * opened on API 34 and 37. **API 32 and 33 are unmeasured and are assumed to refuse like 31**: the
 * threshold is 34, the lowest level measured to open it. If an emulator on 32 or 33 opens the file the
 * test fails and the threshold must be lowered with a measurement.
 *
 * **Password rejection.** A rejection case carries a `controlPassword`, the correct one. Where the store
 * opens on this level, the test opens it with the control password first and fails if that does not
 * succeed, then opens it with the wrong password and requires an `IOException`: a store that cannot be
 * read at all fails the case instead of passing it. `wrong-password` uses the legacy encoding, which
 * opens on every measured level, so every level runs at least one real password rejection (logged as
 * `password-refused-after-control-opened`). `wrong-password-modern-aes` keeps the AES encoding covered:
 * below its `opensFromApi` the test asserts the encoding refusal and logs it as
 * `encoding-refused-below-api-N`, never as a password rejection.
 */
@RunWith(Parameterized::class)
class GoldenBytesSigningTest(private val caseId: String) {

    @Test
    fun deviceMatchesTheJvm() {
        assertManifestCoversBothSigningAndRejection()
        val case = manifest.getValue(caseId)
        val api = Build.VERSION.SDK_INT
        when (case.expect) {
            "REJECTS_PASSWORD" -> assertWrongPasswordIsRefused(case, api)
            "SIGNS" -> assertSignsLikeTheJvm(case, api)
            else -> fail("case $caseId has an unknown expectation '${case.expect}'")
        }
    }

    /** Guards the matrix itself: an empty or one-sided manifest must not look like a pass. */
    private fun assertManifestCoversBothSigningAndRejection() {
        val expectations = manifest.values.map { it.expect }.toSet()
        assertTrue("manifest has no case that signs: $expectations", "SIGNS" in expectations)
        assertTrue("manifest has no case that rejects a password: $expectations", "REJECTS_PASSWORD" in expectations)
        val api = Build.VERSION.SDK_INT
        val realRejections = manifest.filterValues { it.expect == "REJECTS_PASSWORD" && it.opensFromApi <= api }
        assertTrue(
            "manifest has no password-rejection case whose store opens on API $api, so this level would run no " +
                "real password rejection: ${manifest.filterValues { it.expect == "REJECTS_PASSWORD" }.mapValues { it.value.opensFromApi }}",
            realRejections.isNotEmpty(),
        )
    }

    /**
     * A wrong password is refused for the password's sake or the case fails. Below `opensFromApi` the store
     * cannot be read at all, so the encoding refusal is asserted and logged as such. Otherwise the store is
     * first opened with the control password, because a store that cannot be opened would refuse any
     * password and the wrong-password check would prove nothing.
     */
    private fun assertWrongPasswordIsRefused(case: GoldenCase, api: Int) {
        val control = case.controlPassword
        assertTrue("rejection case $caseId has no controlPassword in the manifest, so it could pass vacuously", !control.isNullOrEmpty())
        if (api < case.opensFromApi) {
            assertRefusedToOpen(case, api, control!!)
            return
        }
        try {
            openStore(case, control!!)
        } catch (e: Exception) {
            fail(
                "API $api could not open the ${case.encoding} PKCS#12 of case $caseId with its CONTROL password " +
                    "(${e.javaClass.name}: ${e.message}); a wrong-password refusal of this store would prove nothing",
            )
            return
        }
        try {
            openStore(case, case.password)
        } catch (e: IOException) {
            // The documented failure of KeyStore.load for a wrong password or a corrupt store.
            Log.i(TAG, "api=$api case=$caseId encoding=${case.encoding} outcome=password-refused-after-control-opened")
            return
        }
        fail("API $api opened the PKCS#12 of case $caseId with the wrong password; it must refuse with an IOException")
    }

    /**
     * The expected refusal of a store this API level cannot open. It asserts rather than skips: an
     * `IOException` whose cause chain names the MAC or key failure. Anything else, including a
     * successful open, is a failure, because the expectation no longer describes the platform.
     */
    private fun assertRefusedToOpen(case: GoldenCase, api: Int, password: String = case.password) {
        try {
            openStore(case, password)
        } catch (e: IOException) {
            val chain = generateSequence<Throwable>(e) { it.cause }.toList()
            val namesMacOrKeyFailure = chain.any {
                it is InvalidKeyException || it.message.orEmpty().contains("error constructing MAC")
            }
            assertTrue(
                "API $api refused the ${case.encoding} PKCS#12 of case $caseId, but not for the expected MAC/key " +
                    "reason: ${chain.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }}",
                namesMacOrKeyFailure,
            )
            val outcome = if (case.expect == "REJECTS_PASSWORD") "encoding-refused-below-api-${case.opensFromApi}" else "refused-as-expected-below-api-${case.opensFromApi}"
            Log.i(TAG, "api=$api case=$caseId encoding=${case.encoding} outcome=$outcome")
            return
        } catch (e: Exception) {
            fail("API $api failed to open the ${case.encoding} PKCS#12 of case $caseId with ${e.javaClass.name}, not the expected IOException: ${e.message}")
            return
        }
        fail(
            "API $api opened the ${case.encoding} PKCS#12 of case $caseId, which is expected to be refused below " +
                "API ${case.opensFromApi}; the expectation (opensFromApi in the generator's case definition) needs a new measurement",
        )
    }

    private fun assertSignsLikeTheJvm(case: GoldenCase, api: Int) {
        if (api < case.opensFromApi) {
            assertRefusedToOpen(case, api)
            return
        }
        val problems = ArrayList<String>()

        val identity = try {
            loadIdentity(case)
        } catch (e: Exception) {
            // Where a store cannot load on a platform, this is the answer to which encodings open where.
            fail(
                "API $api could not open the ${case.encoding} PKCS#12 of case $caseId: " +
                    "${e.javaClass.name}: ${e.message}",
            )
            return
        }

        for ((formatName, format) in IssuerNameFormats.ALL) {
            val expected = case.issuerRenderings.getValue(formatName)
            val actual = format.render(identity.certificate)
            if (actual != expected) {
                problems += "issuer rendering $formatName differs on API $api: JVM='$expected' device='$actual'"
            }
        }

        val unsigned = unsignedDocument()
        val storedUnsigned = readAsset(UNSIGNED_ASSET)
        if (!unsigned.octets().contentEquals(storedUnsigned)) {
            fail(
                "the document this test rebuilds is not the unsigned.xml the generator wrote " +
                    "(${unsigned.octets().size} vs ${storedUnsigned.size} bytes); the two builders have drifted",
            )
        }

        val signer = XadesBesSigner(FixedIdSource(firstId), Rfc2253Jdk) { signingTime }
        when (val outcome = signer.sign(unsigned, identity)) {
            is Outcome.Err -> problems += "signing failed on API $api with ${outcome.error}"
            is Outcome.Ok -> {
                val expected = readAsset("$ASSET_DIR/${case.expectedSigned}")
                val actual = outcome.value.octets()
                try {
                    assertArrayEquals(expected, actual)
                } catch (e: AssertionError) {
                    problems += "signed bytes differ on API $api: ${firstDifference(expected, actual)}"
                }
            }
        }

        val outcomeLine = if (problems.isEmpty()) "identical-to-jvm" else "DIFFERS"
        Log.i(TAG, "api=$api case=$caseId encoding=${case.encoding} outcome=$outcomeLine")
        if (problems.isNotEmpty()) {
            fail("case $caseId (${case.description}):\n" + problems.joinToString("\n") { "  $it" })
        }
    }

    private fun openStore(case: GoldenCase, password: String = case.password): KeyStore =
        KeyStore.getInstance("PKCS12").apply { load(ByteArrayInputStream(readAsset("$ASSET_DIR/${case.p12}")), password.toCharArray()) }

    private fun loadIdentity(case: GoldenCase): SigningIdentity {
        val store = openStore(case)
        val password = case.password.toCharArray()
        val alias = store.aliases().nextElement()
        val key = store.getKey(alias, password) as PrivateKey
        val certificate = store.getCertificate(alias) as X509Certificate
        return SigningIdentity(key, certificate)
    }

    /** The same emitter calls as `GoldenAssetGenerator.unsignedDocument`; compared byte for byte with the generator's file. */
    private fun unsignedDocument(): CanonicalDocument = CanonicalEmitter()
        .open("factura", mapOf("id" to "comprobante", "version" to "1.1.0"))
        .open("infoTributaria")
        .open("razonSocial").text("Test Emisor S.A. & Cia Ñandú").close()
        .close()
        .close()
        .document()

    private fun firstDifference(expected: ByteArray, actual: ByteArray): String {
        val shared = minOf(expected.size, actual.size)
        val index = (0 until shared).firstOrNull { expected[it] != actual[it] }
        val at = index ?: shared
        fun window(bytes: ByteArray) = String(bytes, maxOf(0, at - 24), minOf(64, bytes.size - maxOf(0, at - 24)), Charsets.UTF_8)
        return "sizes ${expected.size} (JVM) vs ${actual.size} (device), first difference at offset $at; " +
            "JVM='${window(expected)}' device='${window(actual)}'"
    }

    private class GoldenCase(
        val description: String,
        val p12: String,
        val password: String,
        val controlPassword: String?,
        val encoding: String,
        val expect: String,
        val expectedSigned: String?,
        val opensFromApi: Int,
        val issuerRenderings: Map<String, String>,
    )

    private companion object {
        const val TAG = "GoldenBytes"
        const val ASSET_DIR = "golden"
        const val UNSIGNED_ASSET = "$ASSET_DIR/unsigned.xml"

        /** The test APK's own assets: that is where the generated directory is packaged. */
        fun readAsset(path: String): ByteArray =
            InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { it.readBytes() }

        val properties: Properties by lazy {
            Properties().apply { load(ByteArrayInputStream(readAsset("$ASSET_DIR/manifest.properties"))) }
        }

        val signingTime: OffsetDateTime by lazy { OffsetDateTime.parse(properties.getProperty("signingTime")) }
        val firstId: Int by lazy { properties.getProperty("firstId").toInt() }

        val manifest: Map<String, GoldenCase> by lazy {
            val ids = properties.getProperty("cases").split(',').filter { it.isNotBlank() }
            assertTrue("the manifest names no case", ids.isNotEmpty())
            ids.associateWith { id ->
                val prefix = "case.$id"
                GoldenCase(
                    description = properties.getProperty("$prefix.description"),
                    p12 = properties.getProperty("$prefix.p12"),
                    password = properties.getProperty("$prefix.password"),
                    controlPassword = properties.getProperty("$prefix.controlPassword"),
                    encoding = properties.getProperty("$prefix.encoding"),
                    expect = properties.getProperty("$prefix.expect"),
                    expectedSigned = properties.getProperty("$prefix.expectedSigned"),
                    opensFromApi = properties.getProperty("$prefix.opensFromApi")?.toIntOrNull() ?: 0,
                    issuerRenderings = IssuerNameFormats.ALL.associate { (name, _) ->
                        name to properties.getProperty("$prefix.issuer.$name").orEmpty()
                    },
                )
            }
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = manifest.keys.map { arrayOf<Any>(it) }
    }
}
