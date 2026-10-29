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

package com.neoterux.easyfact.invoicing.soap

import com.neoterux.easyfact.invoicing.result.Outcome
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lenient response parser (SRI-08, D-39..D-45). Every input is a synthetic fixture. Assertion
 * messages never print response content.
 */
class SriResponseParserTest {

    private fun reception(bytes: ByteArray): ReceptionResponse =
        when (val outcome = SriResponseParser.parseReception(bytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected a reception response, got ${outcome.error}")
        }

    private fun authorization(bytes: ByteArray): AuthorizationResponse =
        when (val outcome = SriResponseParser.parseAuthorization(bytes)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("expected an authorization response, got ${outcome.error}")
        }

    private fun receptionProblem(bytes: ByteArray): ResponseProblem =
        when (val outcome = SriResponseParser.parseReception(bytes)) {
            is Outcome.Ok -> throw AssertionError("expected a problem, got a response")
            is Outcome.Err -> outcome.error
        }

    private fun authorizationProblem(bytes: ByteArray): ResponseProblem =
        when (val outcome = SriResponseParser.parseAuthorization(bytes)) {
            is Outcome.Ok -> throw AssertionError("expected a problem, got a response")
            is Outcome.Err -> outcome.error
        }

    private fun malformedReason(problem: ResponseProblem): MalformedReason =
        (problem as? ResponseProblem.Malformed)?.reason ?: throw AssertionError("expected Malformed, got $problem")

    private fun text(bytes: ByteArray?): String = String(bytes ?: ByteArray(0), Charsets.UTF_8)

    private fun resource(name: String): ByteArray =
        (javaClass.getResourceAsStream("/fixtures/sri/$name") ?: throw AssertionError("missing resource $name")).use { it.readBytes() }

    private inline fun <T> withDefaultLocale(locale: Locale, block: () -> T): T {
        val saved = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(saved)
        }
    }

    // --- Reception ------------------------------------------------------------------------

    @Test
    fun aReceivedAnswerIsReceivedWithNoDocuments() {
        val bytes = SriResponseFixtures.receptionReceived()
        val response = reception(bytes)
        assertEquals(ReceptionState.Received, response.state)
        assertTrue(response.documents.isEmpty())
        assertTrue(response.messages.isEmpty())
        assertTrue(response.raw.contentEquals(bytes))
    }

    @Test
    fun theWrapperNameDoesNotMatterForReception() {
        val response = reception(SriResponseFixtures.receptionReceivedRespuestaSolicitud())
        assertEquals(ReceptionState.Received, response.state)
    }

    @Test
    fun aBareReceivedWithoutAComprobantesElementHasNoDocuments() {
        val response = reception(SriResponseFixtures.receptionBareReceived())
        assertEquals(ReceptionState.Received, response.state)
        assertTrue(response.documents.isEmpty())
    }

    @Test
    fun aReturnedAnswerKeepsMessage35WithoutAKey() {
        val response = reception(SriResponseFixtures.receptionReturned())
        assertEquals(ReceptionState.Returned, response.state)
        assertEquals(1, response.documents.size)
        assertNull(response.documents[0].accessKey)
        assertEquals(1, response.messages.size)
        val message = response.messages[0]
        assertEquals("35", message.identifier)
        assertEquals("DOCUMENTO INVÁLIDO", message.message)
        assertEquals("ERROR", message.type)
        assertNotNull(message.additionalInformation)
    }

    @Test
    fun aReturnedAnswerWithAKeyReportsTheKey() {
        val response = reception(SriResponseFixtures.receptionReturnedWithKey())
        assertEquals(SriResponseFixtures.accessKey, response.documents.single().accessKey)
        assertEquals("43", response.messages.single().identifier)
    }

    @Test
    fun aReturnedAnswerWithNoMessagesInventsNoIdentifier() {
        val response = reception(SriResponseFixtures.receptionReturnedEmpty())
        assertEquals(ReceptionState.Returned, response.state)
        assertTrue(response.documents.isEmpty())
        assertTrue(response.messages.isEmpty())
    }

    @Test
    fun anUnrecognisedReceptionStateIsUnknownAndKeepsTheTrimmedText() {
        val response = reception(SriResponseFixtures.receptionUnknownState())
        assertEquals(ReceptionState.Unknown("en  cola"), response.state)
    }

    @Test
    fun receptionStatesAreMatchedLeniently() {
        assertEquals(ReceptionState.Received, reception(receptionWithState(" recibida\n")).state)
        assertEquals(ReceptionState.Returned, reception(receptionWithState("Devuelta")).state)
        assertEquals(ReceptionState.Unknown(""), reception(receptionWithState("")).state)
    }

    private fun receptionWithState(state: String): ByteArray = String(SriResponseFixtures.receptionReceived(), Charsets.UTF_8)
        .replace("RECIBIDA", state)
        .toByteArray(Charsets.UTF_8)

    // --- Authorization: the Ficha samples ---------------------------------------------------

    @Test
    fun anAuthorizedFichaSampleIsAuthorizedWithItsValuesTrimmed() {
        val bytes = SriResponseFixtures.authorizationAuthorized()
        val response = authorization(bytes)

        assertEquals(SriResponseFixtures.accessKey, response.queriedAccessKey)
        assertEquals(1, response.declaredCount)
        assertTrue(response.declaredCountMatches)
        assertEquals(AuthorizationSummary.Authorized(0), response.summary)
        assertTrue(response.raw.contentEquals(bytes))

        val entry = response.authorizations.single()
        assertEquals(AuthorizationState.Authorized, entry.state)
        assertEquals(SriResponseFixtures.accessKey, entry.number)
        assertEquals("PRUEBAS", entry.environmentRaw)
        assertEquals(SriResponseFixtures.SAMPLE_DATE, entry.authorizedAtRaw)
        assertEquals(ZoneOffset.ofHours(-5), entry.authorizedAt?.offset)
        assertFalse(entry.inheritsNamespace)
    }

    @Test
    fun theComprobanteBytesAreTheCdataSectionExactlyAsReceived() {
        val entry = authorization(SriResponseFixtures.authorizationAuthorized()).authorizations.single()
        assertArrayEquals(SriResponseFixtures.comprobanteCdata.toByteArray(Charsets.UTF_8), entry.comprobanteOctets())
    }

    @Test
    fun theElementBytesAreTheAutorizacionElementExactlyAsReceived() {
        val entry = authorization(SriResponseFixtures.authorizationAuthorized()).authorizations.single()
        val element = text(entry.elementOctets())
        assertTrue(element.startsWith("<autorizacion>"))
        assertTrue(element.endsWith("</autorizacion>"))
        assertTrue(element.contains(SriResponseFixtures.comprobanteCdata))
    }

    @Test
    fun anAdvertenciaMessageIsKeptAndNeverMakesTheAnswerAFailure() {
        val response = authorization(SriResponseFixtures.authorizationAuthorized())
        val warning = response.authorizations.single().messages.single()
        assertEquals("60", warning.identifier)
        assertEquals("ADVERTENCIA", warning.type)
        assertEquals("ESTE PROCESO FUE REALIZADO EN EL\nAMBIENTE DE PRUEBAS", warning.message)
        assertNull(warning.additionalInformation)
        assertEquals(AuthorizationSummary.Authorized(0), response.summary)
    }

    @Test
    fun aRejectedFichaSampleIsNotAuthorizedWithNoNumberAndATrimmedMessage() {
        val response = authorization(SriResponseFixtures.authorizationRejected())
        val entry = response.authorizations.single()
        assertEquals(AuthorizationState.NotAuthorized("RECHAZADO"), entry.state)
        assertNull(entry.number)
        assertEquals(1, entry.messages.size)
        assertEquals("46", entry.messages[0].identifier)
        assertEquals("RUC no existe", entry.messages[0].message)
        assertEquals(AuthorizationSummary.NotAuthorized(0), response.summary)
    }

    @Test
    fun anEscapedComprobanteKeepsItsEscapes() {
        val response = authorization(SriResponseFixtures.authorizationEscaped())
        val entry = response.authorizations.single()
        assertEquals(AuthorizationState.Authorized, entry.state)
        val content = text(entry.comprobanteOctets())
        assertEquals(SriResponseFixtures.comprobanteEscaped, content)
        assertTrue(content.contains("&lt;"))
        assertFalse(content.contains("<!"))
    }

    @Test
    fun aCrLfInsideTheComprobanteSurvivesByteForByte() {
        val entry = authorization(SriResponseFixtures.authorizationCrLf()).authorizations.single()
        val content = entry.comprobanteOctets() ?: throw AssertionError("expected comprobante bytes")
        assertTrue(text(content).contains("\r\n<!-- second line -->\r\n"))
    }

    @Test
    fun aLargeEscapedComprobanteIsAcceptedUnderTheSizeCap() {
        val bytes = SriResponseFixtures.authorizationLarge()
        val entry = authorization(bytes).authorizations.single()
        val content = entry.comprobanteOctets() ?: throw AssertionError("expected comprobante bytes")
        assertTrue(content.size >= 1024 * 1024)
        assertTrue(bytes.size < ResponseGuard.MAX_RESPONSE_BYTES)
    }

    @Test
    fun anAuthorizedEntryWithoutANumberIsAuthorizedIncompleteNeverAuthorized() {
        val response = authorization(SriResponseFixtures.authorizationAuthorizedWithoutNumber())
        assertEquals(AuthorizationState.Authorized, response.authorizations.single().state)
        assertEquals(AuthorizationSummary.AuthorizedIncomplete(0), response.summary)
    }

    @Test
    fun anAuthorizedEntryWithoutAComprobanteIsAuthorizedIncompleteNeverAuthorized() {
        val response = authorization(SriResponseFixtures.authorizationAuthorizedWithoutComprobante())
        assertNull(response.authorizations.single().comprobanteOctets())
        assertEquals(AuthorizationSummary.AuthorizedIncomplete(0), response.summary)
    }

    @Test
    fun aSelfClosingComprobanteIsAuthorizedIncompleteNeverAuthorized() {
        val response = authorization(SriResponseFixtures.authorizationAuthorizedWithSelfClosingComprobante())
        assertEquals(AuthorizationState.Authorized, response.authorizations.single().state)
        assertEquals(AuthorizationSummary.AuthorizedIncomplete(0), response.summary)
    }

    @Test
    fun anEmptyComprobanteIsAuthorizedIncompleteNeverAuthorized() {
        val response = authorization(SriResponseFixtures.authorizationAuthorizedWithComprobanteContent(""))
        assertEquals(AuthorizationSummary.AuthorizedIncomplete(0), response.summary)
    }

    @Test
    fun aWhitespaceOnlyComprobanteIsAuthorizedIncompleteNeverAuthorized() {
        val response = authorization(SriResponseFixtures.authorizationAuthorizedWithComprobanteContent(" \r\n\t  \n"))
        assertEquals(AuthorizationSummary.AuthorizedIncomplete(0), response.summary)
    }

    @Test
    fun anEmptyOrBlankCdataComprobanteIsAuthorizedIncompleteNeverAuthorized() {
        for (content in listOf("<![CDATA[]]>", "\n<![CDATA[ \r\n ]]>\n")) {
            val response = authorization(SriResponseFixtures.authorizationAuthorizedWithComprobanteContent(content))
            assertEquals(content, AuthorizationSummary.AuthorizedIncomplete(0), response.summary)
        }
    }

    @Test
    fun aComprobanteWithContentStaysAuthorizedAndItsBytesAreUntouched() {
        val response = authorization(SriResponseFixtures.authorizationAuthorizedWithComprobanteContent("<![CDATA[x]]>"))
        assertEquals(AuthorizationSummary.Authorized(0), response.summary)
        assertTrue("<![CDATA[x]]>".toByteArray().contentEquals(response.authorizations.single().comprobanteOctets()))
    }

    @Test
    fun aCompleteAuthorizedEntryWinsOverAnEarlierIncompleteOne() {
        val response = authorization(SriResponseFixtures.authorizationIncompleteThenComplete())
        assertEquals(AuthorizationSummary.Authorized(1), response.summary)
    }

    // --- Authorization: several entries and the summary -----------------------------------

    @Test
    fun repeatedEntriesAreAllReturnedInOrderAndAnAuthorizedOneWins() {
        val response = authorization(SriResponseFixtures.authorizationRepeated())
        assertEquals(3, response.authorizations.size)
        assertEquals(AuthorizationState.NotAuthorized("NO AUTORIZADO"), response.authorizations[0].state)
        assertEquals(AuthorizationState.Authorized, response.authorizations[1].state)
        assertEquals(AuthorizationState.NotAuthorized("RECHAZADO"), response.authorizations[2].state)
        assertEquals(AuthorizationSummary.Authorized(1), response.summary)
    }

    @Test
    fun eachEntryExposesItsOwnElementBytes() {
        val response = authorization(SriResponseFixtures.authorizationRepeated())
        val names = listOf("first-entry", "second-entry", "third-entry")
        for ((index, name) in names.withIndex()) {
            val entry = response.authorizations[index]
            assertEquals(name, entry.number)
            assertTrue(text(entry.elementOctets()).contains(name))
            assertFalse(names.filter { it != name }.any { text(entry.elementOctets()).contains(it) })
        }
    }

    @Test
    fun withNoneAuthorizedTheLatestDateDecides() {
        val response = authorization(SriResponseFixtures.authorizationNoneAuthorizedOutOfOrder())
        assertEquals(AuthorizationSummary.NotAuthorized(1), response.summary)
    }

    @Test
    fun withOneUnparseableDateTheLastEntryDecides() {
        val response = authorization(SriResponseFixtures.authorizationNoneAuthorizedOneBadDate())
        assertNull(response.authorizations[0].authorizedAt)
        assertEquals("not a date", response.authorizations[0].authorizedAtRaw)
        assertEquals(AuthorizationSummary.Undetermined(2), response.summary)
    }

    @Test
    fun anEqualInstantGoesToTheLaterEntry() {
        val response = authorization(SriResponseFixtures.authorizationNoneAuthorizedTiedDates())
        assertEquals(AuthorizationSummary.Undetermined(1), response.summary)
    }

    @Test
    fun anEmptyAuthorizacionesSummarisesAsNoAuthorizationsNotAsNotAuthorized() {
        val response = authorization(SriResponseFixtures.authorizationEmpty())
        assertTrue(response.authorizations.isEmpty())
        assertEquals(0, response.declaredCount)
        assertEquals(AuthorizationSummary.NoAuthorizations, response.summary)
        assertFalse(response.summary is AuthorizationSummary.NotAuthorized)
    }

    @Test
    fun anAbsentAuthorizacionesSummarisesAsNoAuthorizationsNotAsNotAuthorized() {
        val response = authorization(SriResponseFixtures.authorizationAbsent())
        assertTrue(response.authorizations.isEmpty())
        assertEquals(AuthorizationSummary.NoAuthorizations, response.summary)
        assertFalse(response.summary is AuthorizationSummary.NotAuthorized)
    }

    @Test
    fun aCountThatDisagreesWithTheEntriesIsRecordedNotAnError() {
        val response = authorization(SriResponseFixtures.authorizationCountMismatch())
        assertEquals(2, response.declaredCount)
        assertEquals(1, response.authorizations.size)
        assertFalse(response.declaredCountMatches)
    }

    @Test
    fun aDefaultNamespaceOnTheWrapperIsFlaggedOnTheSlice() {
        val entry = authorization(SriResponseFixtures.authorizationInheritingNamespace()).authorizations.single()
        assertTrue(entry.inheritsNamespace)
        assertEquals(AuthorizationState.Authorized, entry.state)
    }

    // --- Lenient states --------------------------------------------------------------------

    @Test
    fun anUnrecognisedStateIsUnknownAndNeverSuccess() {
        val response = authorization(SriResponseFixtures.authorizationWithState("EN PROCESO"))
        assertEquals(AuthorizationState.Unknown("EN PROCESO"), response.authorizations.single().state)
        assertEquals(AuthorizationSummary.Undetermined(0), response.summary)
    }

    @Test
    fun aMissingStateIsUnknown() {
        val response = authorization(SriResponseFixtures.authorizationWithState(""))
        assertEquals(AuthorizationState.Unknown(""), response.authorizations.single().state)
        assertEquals(AuthorizationSummary.Undetermined(0), response.summary)
    }

    @Test
    fun lenientSpellingsMapToTheSameStates() {
        assertSpellings()
    }

    @Test
    fun lenientSpellingsMapToTheSameStatesUnderATurkishDefaultLocale() {
        withDefaultLocale(Locale.forLanguageTag("tr-TR")) { assertSpellings() }
    }

    private fun assertSpellings() {
        fun state(raw: String): AuthorizationState =
            authorization(SriResponseFixtures.authorizationWithState(raw)).authorizations.single().state

        assertEquals(AuthorizationState.Authorized, state("  autorizado "))
        assertEquals(AuthorizationState.Authorized, state("AUTORIZADO"))
        assertEquals(AuthorizationState.NotAuthorized("No  Autorizado"), state("No  Autorizado"))
        assertEquals(AuthorizationState.NotAuthorized("rechazada"), state("rechazada"))
        assertEquals(AuthorizationState.NotAuthorized("RECHAZADO"), state("RECHAZADO"))
        assertEquals(AuthorizationState.NotAuthorized("NO AUTORIZADO"), state("NO AUTORIZADO"))
        assertEquals(AuthorizationState.Unknown("autorizadoo"), state("autorizadoo"))
    }

    // --- Dates -----------------------------------------------------------------------------

    @Test
    fun aDayFirstDateIsReadAsGuayaquilTime() {
        val entry = authorization(SriResponseFixtures.authorizationWithDate("27/10/2026 10:15:30")).authorizations.single()
        assertEquals("27/10/2026 10:15:30", entry.authorizedAtRaw)
        assertEquals(OffsetDateTime.of(2026, 10, 27, 10, 15, 30, 0, ZoneOffset.ofHours(-5)), entry.authorizedAt)
    }

    @Test
    fun anUnparseableDateGivesNullAndNoError() {
        val entry = authorization(SriResponseFixtures.authorizationWithDate("not a date")).authorizations.single()
        assertEquals("not a date", entry.authorizedAtRaw)
        assertNull(entry.authorizedAt)
        assertEquals(AuthorizationState.Authorized, entry.state)
    }

    @Test
    fun anImpossibleDayFirstDateGivesNull() {
        val entry = authorization(SriResponseFixtures.authorizationWithDate("31/02/2026 10:15:30")).authorizations.single()
        assertNull(entry.authorizedAt)
    }

    // --- Transport-class answers -----------------------------------------------------------

    @Test
    fun aSoapFaultIsASoapFaultWithCodeAndReason() {
        val bytes = SriResponseFixtures.soapFault()
        val fault = receptionProblem(bytes) as? ResponseProblem.SoapFault ?: throw AssertionError("expected SoapFault")
        assertEquals("soap:Server", fault.code)
        assertEquals("synthetic server failure", fault.reason)
        assertTrue(fault.raw.contentEquals(bytes))
        val sameForAuthorization = authorizationProblem(bytes)
        assertTrue(sameForAuthorization is ResponseProblem.SoapFault)
    }

    @Test
    fun aSoap12FaultIsReadFromCodeValueAndReasonText() {
        val fault = authorizationProblem(SriResponseFixtures.soap12Fault()) as? ResponseProblem.SoapFault
            ?: throw AssertionError("expected SoapFault")
        assertEquals("env:Receiver", fault.code)
        assertEquals("synthetic receiver failure", fault.reason)
    }

    @Test
    fun aWellFormedBodyWithoutTheExpectedElementIsUnexpectedBody() {
        val bytes = SriResponseFixtures.unexpectedBody()
        assertTrue(receptionProblem(bytes) is ResponseProblem.UnexpectedBody)
        assertTrue(authorizationProblem(bytes) is ResponseProblem.UnexpectedBody)
        assertTrue(receptionProblem(SriResponseFixtures.notSoap()) is ResponseProblem.UnexpectedBody)
    }

    @Test
    fun theWrongKindOfResponseIsUnexpectedBody() {
        val problemForAuthorization = authorizationProblem(SriResponseFixtures.receptionReturned())
        val problemForReception = receptionProblem(SriResponseFixtures.authorizationAuthorized())
        assertTrue(problemForAuthorization is ResponseProblem.UnexpectedBody)
        assertTrue(problemForReception is ResponseProblem.UnexpectedBody)
    }

    @Test
    fun aTruncatedResponseIsNotWellFormed() {
        val bytes = SriResponseFixtures.notWellFormed()
        val problem = receptionProblem(bytes)
        assertEquals(MalformedReason.NOT_WELL_FORMED, malformedReason(problem))
        assertTrue(problem.raw.contentEquals(bytes))
    }

    @Test
    fun anEmptyResponseIsMalformedAsEmpty() {
        assertEquals(MalformedReason.EMPTY, malformedReason(authorizationProblem(ByteArray(0))))
    }

    @Test
    fun hostileDoctypePayloadsAreRefusedAsDoctype() {
        for (bytes in listOf(SriResponseFixtures.billionLaughs(), SriResponseFixtures.externalEntity())) {
            val reception = receptionProblem(bytes)
            val authorization = authorizationProblem(bytes)
            assertEquals(MalformedReason.DOCTYPE, malformedReason(reception))
            assertEquals(MalformedReason.DOCTYPE, malformedReason(authorization))
            assertTrue(reception.raw.contentEquals(bytes))
            assertTrue(authorization.raw.contentEquals(bytes))
        }
    }

    @Test
    fun nestingBeyondTheCapIsNotWellFormedAndNeverOverflowsTheStack() {
        val deep = SriResponseFixtures.nestedTooDeep(100_000)
        assertEquals(MalformedReason.NOT_WELL_FORMED, malformedReason(receptionProblem(deep)))
        assertEquals(MalformedReason.NOT_WELL_FORMED, malformedReason(authorizationProblem(deep)))
    }

    @Test
    fun aBodyOfTooManyElementsIsNotWellFormed() {
        val wide = SriResponseFixtures.tooManyElements(200_000)
        assertEquals(MalformedReason.NOT_WELL_FORMED, malformedReason(receptionProblem(wide)))
    }

    @Test
    fun anAutorizacionOutsideAutorizacionesIsASliceMismatch() {
        val bytes = SriResponseFixtures.authorizationStrayEntry()
        val problem = authorizationProblem(bytes)
        assertEquals(MalformedReason.RAW_SLICE_MISMATCH, malformedReason(problem))
        assertTrue(problem.raw.contentEquals(bytes))
    }

    @Test
    fun noProblemIsEverTreatedAsAnAuthorizationOrAReception() {
        val inputs = listOf(
            SriResponseFixtures.soapFault(),
            SriResponseFixtures.unexpectedBody(),
            SriResponseFixtures.notWellFormed(),
            SriResponseFixtures.billionLaughs(),
        )
        for (bytes in inputs) {
            assertTrue(SriResponseParser.parseReception(bytes) is Outcome.Err)
            assertTrue(SriResponseParser.parseAuthorization(bytes) is Outcome.Err)
        }
    }

    // --- Phase 2 structure-only resources --------------------------------------------------

    @Test
    fun theStructureOnlyAuthorizationResourceParsesWithAnUnknownState() {
        val response = authorization(resource("structure-only-autorizacion.xml"))
        val entry = response.authorizations.single()
        assertEquals(AuthorizationState.Unknown(""), entry.state)
        assertNull(entry.number)
        assertNull(entry.authorizedAt)
        assertEquals(AuthorizationSummary.Undetermined(0), response.summary)
    }

    @Test
    fun theStructureOnlyReceptionResourceParsesWithAnUnknownState() {
        val response = reception(resource("structure-only-recepcion.xml"))
        assertEquals(ReceptionState.Unknown(""), response.state)
        assertEquals(1, response.documents.size)
        assertNull(response.documents[0].accessKey)
    }

    // --- Privacy ---------------------------------------------------------------------------

    @Test
    fun noRenderingOfAStateOrOfAnEntryHoldingOneContainsTheRawEstadoText() {
        val secret = "ZZsecretZZ"
        val unknownAuthorization = authorization(SriResponseFixtures.authorizationWithState(secret))
        val notAuthorized = authorization(SriResponseFixtures.authorizationWithState("RECHAZADO"))
        val states = listOf(
            ReceptionState.Unknown(secret),
            AuthorizationState.NotAuthorized(secret),
            AuthorizationState.Unknown(secret),
            unknownAuthorization.authorizations.single().state,
            notAuthorized.authorizations.single().state,
        )
        // The parsed entries render their state, so a state that leaked would leak through them too.
        val renderings = states.map { it.toString() } +
            unknownAuthorization.authorizations.single().toString() +
            notAuthorized.authorizations.single().toString() +
            notAuthorized.toString()
        for (rendering in renderings) {
            assertFalse("a rendering leaked the estado text: $rendering", rendering.contains(secret))
            assertFalse("a rendering leaked the estado text: $rendering", rendering.contains("RECHAZADO"))
        }
        assertEquals(AuthorizationState.Unknown(secret), unknownAuthorization.authorizations.single().state)
    }

    @Test
    fun noRenderingOfAParsedObjectContainsContent() {
        val returned = reception(SriResponseFixtures.receptionReturnedWithKey())
        val rejected = authorization(SriResponseFixtures.authorizationRejected())
        val authorized = authorization(SriResponseFixtures.authorizationAuthorized())
        val withInformation = reception(SriResponseFixtures.receptionReturned())
        val fault = receptionProblem(SriResponseFixtures.soapFault())

        val renderings = listOf(
            returned.toString(),
            returned.raw.toString(),
            returned.documents.single().toString(),
            returned.messages.single().toString(),
            withInformation.messages.single().toString(),
            rejected.toString(),
            rejected.authorizations.single().toString(),
            rejected.authorizations.single().messages.single().toString(),
            authorized.toString(),
            authorized.authorizations.single().toString(),
            authorized.authorizations.single().messages.single().toString(),
            fault.toString(),
            receptionProblem(SriResponseFixtures.unexpectedBody()).toString(),
            receptionProblem(SriResponseFixtures.notWellFormed()).toString(),
        )
        val forbidden = listOf(
            SriResponseFixtures.accessKey,
            "RUC no existe",
            "ESTE PROCESO",
            "synthetic detail",
            "synthetic server failure",
            "INVÁLIDO",
            "CLAVE ACCESO",
            "Test Issuer",
        )
        for (rendering in renderings) {
            for (value in forbidden) {
                assertFalse("a rendering leaked content", rendering.contains(value))
            }
        }
    }

    @Test
    fun aMessageRendersItsIdentifierOnlyWhenItLooksLikeAProtocolCode() {
        fun rendered(identifier: String) = SriMessage(identifier, "text", null, "ERROR").toString()

        assertEquals("SriMessage(identifier=35)", rendered("35"))
        assertEquals("SriMessage(identifier=)", rendered(""))
        for (hostile in listOf("ZZsecretZZ", "35 extra", "1234567", "\u0663\u0665", "35\n")) {
            val rendering = rendered(hostile)
            assertFalse("a rendering leaked the identifier text: $rendering", rendering.contains(hostile.trim()))
            assertEquals("SriMessage(identifier=redacted)", rendering)
        }
    }

    @Test
    fun theRawWrapperKeepsAPrivateCopy() {
        val bytes = SriResponseFixtures.receptionReceived()
        val original = bytes.copyOf()
        val response = reception(bytes)
        bytes[0] = 0
        assertTrue(response.raw.contentEquals(original))
        val copy = response.raw.octets()
        copy[0] = 0
        assertTrue(response.raw.contentEquals(original))
        assertEquals(original.size, response.raw.size)
    }
}
