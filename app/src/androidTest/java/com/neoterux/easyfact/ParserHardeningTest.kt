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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neoterux.easyfact.invoicing.accesskey.AccessKey
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.soap.AuthorizationSummary
import com.neoterux.easyfact.invoicing.soap.MalformedReason
import com.neoterux.easyfact.invoicing.soap.ResponseProblem
import com.neoterux.easyfact.invoicing.soap.SriResponseParser
import java.time.LocalDate
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves that the SRI response codec's hardening behaves on ART as it does on the JDK (research Pattern 6,
 * assumption A4): the byte-level [com.neoterux.easyfact.invoicing.soap.ResponseGuard] refuses hostile input
 * before any parser sees it, and the JAXP SAX setup (secure-processing feature in a guarded try, a refusing
 * entity resolver) neither throws on a device nor changes what a legitimate answer yields.
 *
 * **Not a correctness dependency (NFR-04).** The engine's behaviour is covered by JVM tests in
 * `:core:invoicing`; this test only shows that the same code is portable. It runs on the device matrix
 * (`./gradlew :app:verifyDeviceMatrix`), which is deliberately not part of `check`, so a green `check` says
 * nothing about it. The matrix's result is reported as run or not run, never assumed.
 *
 * Every payload is synthetic and built here at run time. The placeholder RUC is assembled from fragments, the
 * key goes through [AccessKey.of], and the only URL is on example.com, which is never fetched (the entity
 * case asserts exactly that the parser is not reached). Each case logs one line under [TAG] holding the API
 * level and the outcome name, never any content.
 */
@RunWith(AndroidJUnit4::class)
class ParserHardeningTest {

    @Test
    fun aBenignFichaShapedAuthorizationParsesOnThisApiLevel() {
        val comprobante = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<factura id=\"comprobante\" version=\"1.1.0\">" +
            "<!-- a literal <autorizacion> element name inside a comment -->" +
            "<infoTributaria><razonSocial>Test Issuer S.A.</razonSocial>" +
            "<claveAcceso>$accessKey</claveAcceso></infoTributaria></factura>"
        val cdata = "<![CDATA[$comprobante]]>"
        val response = envelope(
            "<RespuestaAutorizacionComprobante>" +
                "<claveAccesoConsultada>\n$accessKey\n</claveAccesoConsultada>" +
                "<numeroComprobantes>1</numeroComprobantes>" +
                "<autorizaciones><autorizacion><estado>AUTORIZADO</estado>" +
                "<numeroAutorizacion>\n$accessKey\n</numeroAutorizacion>" +
                "<fechaAutorizacion>2026-10-27T10:15:30.997-05:00</fechaAutorizacion>" +
                "<ambiente>PRUEBAS</ambiente><comprobante>$cdata</comprobante>" +
                "<mensajes><mensaje><identificador>60</identificador>" +
                "<mensaje>\nESTE PROCESO FUE REALIZADO EN EL\nAMBIENTE DE PRUEBAS\n</mensaje>" +
                "<tipo>ADVERTENCIA</tipo></mensaje></mensajes></autorizacion></autorizaciones>" +
                "</RespuestaAutorizacionComprobante>",
        ).toByteArray(Charsets.UTF_8)

        when (val outcome = SriResponseParser.parseAuthorization(response)) {
            is Outcome.Err -> {
                log("benign", "Err(${outcome.error})")
                fail("a benign authorization was refused on API ${Build.VERSION.SDK_INT}: ${outcome.error}")
            }
            is Outcome.Ok -> {
                val answer = outcome.value
                assertEquals(AuthorizationSummary.Authorized(0), answer.summary)
                assertEquals(1, answer.authorizations.size)
                assertArrayEquals(
                    "the comprobante bytes must be the CDATA section exactly as received",
                    cdata.toByteArray(Charsets.UTF_8),
                    answer.authorizations[0].comprobanteOctets(),
                )
                log("benign", "Ok(Authorized(0))")
            }
        }
    }

    @Test(timeout = 10_000)
    fun aBillionLaughsPayloadIsRefusedQuickly() {
        val declarations = StringBuilder("<!ENTITY lol0 \"lol\">")
        for (level in 1..9) {
            val previous = "&lol${level - 1};".repeat(10)
            declarations.append("<!ENTITY lol$level \"$previous\">")
        }
        val payload = ("<?xml version=\"1.0\"?><!DOCTYPE lolz [$declarations]><lolz>&lol9;</lolz>")
            .toByteArray(Charsets.UTF_8)

        assertRefused("billion-laughs", payload, MalformedReason.DOCTYPE)
    }

    @Test
    fun anExternalEntityIsRefusedWithoutANetworkFetch() {
        val payload = (
            "<?xml version=\"1.0\"?>" +
                "<!DOCTYPE probe [<!ENTITY external SYSTEM \"http://example.com/never-fetched.txt\">]>" +
                envelope("<RespuestaAutorizacionComprobante>&external;</RespuestaAutorizacionComprobante>")
                    .removePrefix("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            ).toByteArray(Charsets.UTF_8)

        assertRefused("external-entity", payload, MalformedReason.DOCTYPE)
    }

    @Test
    fun aUtf16PayloadIsRefused() {
        val payload = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "<a/>".toByteArray(Charsets.UTF_16LE)

        assertRefused("utf16", payload, MalformedReason.NOT_UTF8)
    }

    private fun assertRefused(case: String, payload: ByteArray, expected: MalformedReason) {
        when (val outcome = SriResponseParser.parseAuthorization(payload)) {
            is Outcome.Ok -> {
                log(case, "Ok")
                fail("a hostile payload was accepted on API ${Build.VERSION.SDK_INT}")
            }
            is Outcome.Err -> {
                val problem = outcome.error
                assertTrue("expected Malformed but got $problem", problem is ResponseProblem.Malformed)
                val reason = (problem as ResponseProblem.Malformed).reason
                log(case, "Malformed($reason)")
                assertEquals(expected, reason)
            }
        }
    }

    private fun envelope(body: String): String =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" +
            "<ns2:autorizacionComprobanteResponse xmlns:ns2=\"http://ec.gob.sri.ws.autorizacion\">$body" +
            "</ns2:autorizacionComprobanteResponse></soap:Body></soap:Envelope>"

    private fun log(case: String, outcome: String) {
        Log.i(TAG, "api=${Build.VERSION.SDK_INT} case=$case outcome=$outcome")
    }

    private companion object {
        const val TAG = "ParserHardening"

        /** A synthetic key over the placeholder RUC, assembled from fragments (nothing identifier-shaped is written out). */
        val accessKey: String = when (
            val built = AccessKey.of(
                issueDate = LocalDate.of(2026, 10, 27),
                documentTypeCode = "01",
                issuerRuc = "9".repeat(10) + "001",
                environmentCode = "1",
                establishment = "001",
                emissionPoint = "002",
                sequential = 2L,
                numericCode = "12345678",
                emissionTypeCode = "1",
            )
        ) {
            is Outcome.Ok -> built.value.digits
            is Outcome.Err -> throw AssertionError("the synthetic key must build: ${built.error}")
        }
    }
}
