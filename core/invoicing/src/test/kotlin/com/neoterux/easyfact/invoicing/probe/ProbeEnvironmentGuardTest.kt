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

package com.neoterux.easyfact.invoicing.probe

import com.neoterux.easyfact.invoicing.factura.FacturaWriter
import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.signing.FixedIdSource
import com.neoterux.easyfact.invoicing.signing.TestIdentities
import com.neoterux.easyfact.invoicing.signing.XadesBesSigner
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * The part of the probe that runs unconditionally in CI: no certificate, no network. It covers the
 * environment guard, the configuration reader, the shape pin, the fixture, the SOAP envelopes and
 * readers, the run state and the run log mask.
 *
 * Every identifier-shaped value is built at run time so the privacy scan stays green.
 */
class ProbeEnvironmentGuardTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val probeSourceDirectory: Path = Path.of("src/test/kotlin/com/neoterux/easyfact/invoicing/probe")
    private val validKey: String get() = ProbeDocumentFixture.accessKey().digits
    private val validOctets: ByteArray get() = ProbeDocumentFixture.canonicalOctets()

    private fun signedFixture(values: ProbeDocumentFixture.Values = ProbeDocumentFixture.Values()): ByteArray {
        val signer = XadesBesSigner(FixedIdSource(100000), clock = TestIdentities.fixedClock)
        val outcome = signer.sign(ProbeDocumentFixture.document(values), TestIdentities.plain)
        return (outcome as Outcome.Ok).value.octets()
    }

    private fun assertRefuses(expectedFragment: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal containing '$expectedFragment'")
        } catch (e: IllegalArgumentException) {
            assertTrue("message was: ${e.message}", e.message!!.contains(expectedFragment))
        }
    }

    // ---- ProbeEnvironmentGuard ------------------------------------------------------------

    @Test
    fun theGuardAcceptsATestKeyATestDocumentAndBothTestEndpoints() {
        ProbeEnvironmentGuard.requireTestEnvironment(validKey, validOctets, ProbeEnvironmentGuard.receptionEndpoint)
        ProbeEnvironmentGuard.requireTestEnvironment(validKey, validOctets, ProbeEnvironmentGuard.authorizationEndpoint)
        assertEquals("celcer.sri.gob.ec", ProbeEnvironmentGuard.receptionEndpoint.host)
        assertEquals("celcer.sri.gob.ec", ProbeEnvironmentGuard.authorizationEndpoint.host)
    }

    @Test
    fun theGuardRefusesAnEnvironmentDigitOfTwo() {
        val productionKey = ProbeDocumentFixture.accessKey(ProbeDocumentFixture.Values(environmentCode = "2")).digits
        assertEquals('2', productionKey[23])
        assertRefuses("environment digit") {
            ProbeEnvironmentGuard.requireTestEnvironment(productionKey, validOctets, ProbeEnvironmentGuard.receptionEndpoint)
        }
    }

    @Test
    fun theGuardRefusesAKeyOfTheWrongLength() {
        assertRefuses("49 characters") {
            ProbeEnvironmentGuard.requireTestEnvironment(validKey.dropLast(1), validOctets, ProbeEnvironmentGuard.receptionEndpoint)
        }
        assertRefuses("49 characters") {
            ProbeEnvironmentGuard.requireTestEnvironment(validKey + "0", validOctets, ProbeEnvironmentGuard.receptionEndpoint)
        }
    }

    @Test
    fun theGuardRefusesAnyHostOtherThanTheTestHostAndAnyNonHttpsScheme() {
        assertRefuses("host") {
            ProbeEnvironmentGuard.requireTestEnvironment(validKey, validOctets, URI("https://example.com/comprobantes-electronicos-ws/x"))
        }
        assertRefuses("host") {
            // A host that merely contains the test host's name is not the test host.
            ProbeEnvironmentGuard.requireTestEnvironment(validKey, validOctets, URI("https://celcer.sri.gob.ec.example.com/x"))
        }
        assertRefuses("HTTPS") {
            ProbeEnvironmentGuard.requireTestEnvironment(validKey, validOctets, URI("http://celcer.sri.gob.ec/x"))
        }
    }

    @Test
    fun theGuardRefusesADocumentWhoseAmbienteIsNotOne() {
        val productionDocument = ProbeDocumentFixture.canonicalOctets(ProbeDocumentFixture.Values(environmentCode = "2"))
        assertRefuses("ambiente") {
            ProbeEnvironmentGuard.requireTestEnvironment(validKey, productionDocument, ProbeEnvironmentGuard.receptionEndpoint)
        }
        assertRefuses("ambiente") {
            ProbeEnvironmentGuard.requireTestEnvironment(validKey, "<factura id=\"comprobante\"/>".toByteArray(), ProbeEnvironmentGuard.receptionEndpoint)
        }
    }

    @Test
    fun theProbePackageNamesNoProductionHost() {
        val productionHost = "cel" + ".sri.gob.ec"
        val offenders = Files.list(probeSourceDirectory).use { files ->
            files.filter { it.toString().endsWith(".kt") }.filter { Files.readString(it).contains(productionHost) }.map { it.fileName.toString() }.toList()
        }
        assertEquals("these files name the production host: $offenders", emptyList<String>(), offenders)
    }

    // ---- ProbeConfig ----------------------------------------------------------------------

    private val completeProperties = mapOf(
        "pkcs12Path" to "/somewhere/not-a-real-store.p12",
        "pkcs12Password" to "not-a-real-password",
        "issuerRuc" to "9".repeat(10) + "001",
        "establishmentCode" to "001",
        "emissionPointCode" to "002",
        "sequential" to "7",
    )

    private fun writeConfig(directory: Path, properties: Map<String, String>): Path {
        val file = directory.resolve("probe.properties")
        Files.writeString(file, properties.entries.joinToString("\n") { "${it.key}=${it.value}" })
        return file
    }

    /** Collects the skip reasons [ProbeConfig.fromEnvironment] reports, in place of the stderr default. */
    private class SkipReasons : (String) -> Unit {
        val reasons = mutableListOf<String>()
        override fun invoke(reason: String) { reasons += reason }
        fun only(): String = reasons.single()
    }

    @Test
    fun theConfigurationIsNullWhenTheVariableIsUnsetBlankOrNamesAMissingFile() {
        val repo = folder.newFolder("repo").toPath()

        val unset = SkipReasons()
        assertNull(ProbeConfig.fromEnvironment(emptyMap(), repo, unset))
        assertTrue(unset.only(), unset.only().contains(ProbeConfig.ENVIRONMENT_VARIABLE))
        assertTrue(unset.only(), unset.only().contains("unset or blank"))

        val blank = SkipReasons()
        assertNull(ProbeConfig.fromEnvironment(mapOf(ProbeConfig.ENVIRONMENT_VARIABLE to "  "), repo, blank))
        assertTrue(blank.only(), blank.only().contains("unset or blank"))

        val absentPath = folder.root.resolve("absent.properties").path
        val absent = SkipReasons()
        assertNull(ProbeConfig.fromEnvironment(mapOf(ProbeConfig.ENVIRONMENT_VARIABLE to absentPath), repo, absent))
        assertTrue(absent.only(), absent.only().contains("not a regular file"))
        assertTrue(absent.only(), absent.only().contains(absentPath))
    }

    @Test
    fun theConfigurationIsNullWhenARequiredKeyIsMissing() {
        val repo = folder.newFolder("repo").toPath()
        val outside = folder.newFolder("outside").toPath()
        for (missing in completeProperties.keys) {
            val file = writeConfig(outside, completeProperties - missing)
            val reasons = SkipReasons()
            assertNull("missing $missing", ProbeConfig.fromEnvironment(mapOf(ProbeConfig.ENVIRONMENT_VARIABLE to file.toString()), repo, reasons))
            assertTrue(reasons.only(), reasons.only().contains(missing))
        }
    }

    @Test
    fun aSkipAlwaysReportsAReasonSoItIsNotMistakenForAPass() {
        val repo = folder.newFolder("repo").toPath()
        val outside = folder.newFolder("outside").toPath()
        val rejected = listOf(
            emptyMap(),
            mapOf("sequential" to "0"),
            mapOf("sequential" to "not-a-number"),
            mapOf("issuerNameFormat" to "NoSuchFormat"),
            mapOf("pollWindowMinutes" to "0"),
        )
        for (override in rejected) {
            val file = writeConfig(outside, completeProperties + override)
            val reasons = SkipReasons()
            val config = ProbeConfig.fromEnvironment(mapOf(ProbeConfig.ENVIRONMENT_VARIABLE to file.toString()), repo, reasons)
            if (override.isEmpty()) {
                // The control: a complete configuration loads and reports nothing.
                assertNotNull(config)
                assertTrue(reasons.reasons.toString(), reasons.reasons.isEmpty())
            } else {
                assertNull("accepted $override", config)
                assertEquals("exactly one reason for $override", 1, reasons.reasons.size)
                assertTrue(reasons.only(), reasons.only().isNotBlank())
            }
        }
    }

    @Test
    fun aCompleteConfigurationOutsideTheTreeLoadsAndRendersNoValue() {
        val repo = folder.newFolder("repo").toPath()
        val outside = folder.newFolder("outside").toPath()
        val file = writeConfig(outside, completeProperties)
        val config = ProbeConfig.fromEnvironment(mapOf(ProbeConfig.ENVIRONMENT_VARIABLE to file.toString()), repo)
        assertNotNull(config)
        config!!
        assertEquals(7L, config.sequential)
        assertEquals("Rfc2253Jdk", config.issuerNameFormatName)
        assertEquals("002", config.emissionPointCode)
        assertEquals("ProbeConfig(redacted)", config.toString())
        assertFalse(config.toString().contains(config.issuerRuc))
        assertEquals(outside.toRealPath(), config.stateDirectory)
    }

    @Test
    fun aConfigurationInsideTheRepositoryTreeIsRefusedLoudly() {
        val repo = folder.newFolder("repo").toPath()
        val file = writeConfig(repo, completeProperties)
        try {
            ProbeConfig.fromEnvironment(mapOf(ProbeConfig.ENVIRONMENT_VARIABLE to file.toString()), repo)
            fail("expected a refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("inside the repository working tree"))
        }
    }

    @Test
    fun aConfigurationInsideTheRealWorkingTreeIsRefusedToo() {
        val root = ProbeConfig.findRepositoryRoot()
        Assume.assumeTrue("not running inside a checkout", root != null)
        val build = Path.of("build").toAbsolutePath().also { Files.createDirectories(it) }
        val directory = Files.createTempDirectory(build, "probe-config-test")
        try {
            val file = writeConfig(directory, completeProperties)
            try {
                ProbeConfig.fromEnvironment(mapOf(ProbeConfig.ENVIRONMENT_VARIABLE to file.toString()))
                fail("expected a refusal")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("inside the repository working tree"))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    // ---- ProbeDocumentFixture and ProbeDocumentShape ---------------------------------------

    @Test
    fun theFixtureEmitsThroughTheWriterAndIsNotBuiltByHand() {
        assertArrayEquals(FacturaWriter.write(ProbeDocumentFixture.factura()).octets(), ProbeDocumentFixture.canonicalOctets())
        val source = Files.readString(probeSourceDirectory.resolve("ProbeDocumentFixture.kt"))
        assertTrue(source.contains("FacturaWriter.write"))
        assertFalse("the fixture must not assemble XML by string", source.contains("\"<"))
    }

    @Test
    fun theElementPathsOfTheFixtureEqualTheCommittedFileExactly() {
        assertEquals(ProbeDocumentShape.recordedPaths(), ProbeDocumentShape.elementPaths(validOctets))
        ProbeDocumentShape.assertMatchesRecordedShape(validOctets)
    }

    @Test
    fun theSignatureSubtreeDoesNotChangeTheShape() {
        val signed = signedFixture()
        assertEquals(ProbeDocumentShape.elementPaths(validOctets), ProbeDocumentShape.elementPaths(signed))
        ProbeDocumentShape.assertMatchesRecordedShape(signed)
    }

    @Test
    fun theCommittedFileCarriesNoValueAndOpensWithItsThreePartComment() {
        val raw = Files.readAllLines(Path.of("src/test/resources/fixtures/sri/probe-document-element-set.txt"))
        val comment = raw.takeWhile { it.startsWith("#") }.joinToString("\n")
        assertTrue(comment.contains("1. What this file pins"))
        assertTrue(comment.contains("2. It tracks the writer"))
        assertTrue(comment.contains("3. It is not the historical record"))
        assertTrue(comment.contains("02-05, Task 2"))
        val paths = raw.filter { !it.startsWith("#") && it.isNotBlank() }
        assertTrue(paths.all { path -> path.split('/').all { part -> part.all { c -> c.isLetter() } } })
    }

    @Test
    fun theComparisonFailsForAnAddedElement() {
        val tampered = String(validOctets).replace("</factura>", "<extra></extra></factura>").toByteArray()
        val failure = runCatching { ProbeDocumentShape.assertMatchesRecordedShape(tampered) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("added: [factura/extra]"))
    }

    @Test
    fun theComparisonFailsForARemovedElement() {
        val tampered = String(validOctets).replace(Regex("<propina>[^<]*</propina>"), "").toByteArray()
        val failure = runCatching { ProbeDocumentShape.assertMatchesRecordedShape(tampered) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("missing: [factura/infoFactura/propina]"))
    }

    @Test
    fun theComparisonFailsForAReorderedBlock() {
        val original = String(validOctets)
        val swapped = original.replace(
            Regex("(<propina>[^<]*</propina>)(<importeTotal>[^<]*</importeTotal>)"),
            "$2$1",
        )
        assertTrue("the swap must change the document", swapped != original)
        val failure = runCatching { ProbeDocumentShape.assertMatchesRecordedShape(swapped.toByteArray()) }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("different order"))
    }

    // ---- ThrowawaySoap --------------------------------------------------------------------

    private fun elementsNamed(octets: ByteArray, name: String): List<Element> {
        val nodes = ProbeEnvironmentGuard.parseHardened(octets).getElementsByTagNameNS("*", name)
        return List(nodes.length) { nodes.item(it) as Element }
    }

    @Test
    fun theReceptionEnvelopeCarriesExactlyTheSignedOctetsAsBase64() {
        val signed = signedFixture()
        val envelope = ThrowawaySoap.recepcionEnvelope(signed)
        val payload = elementsNamed(envelope.toByteArray(), "xml").single()
        assertNull("the payload element is unqualified", payload.namespaceURI)
        assertFalse("no line break may enter the payload", payload.textContent.contains('\n') || payload.textContent.contains('\r'))
        assertArrayEquals(signed, Base64.getDecoder().decode(payload.textContent))
        val operation = elementsNamed(envelope.toByteArray(), "validarComprobante").single()
        assertEquals("http://ec.gob.sri.ws.recepcion", operation.namespaceURI)
    }

    @Test
    fun theAuthorizationEnvelopeCarriesTheKeyInTheUnqualifiedRequestElement() {
        val envelope = ThrowawaySoap.autorizacionEnvelope(validKey)
        val request = elementsNamed(envelope.toByteArray(), "claveAccesoComprobante").single()
        assertNull(request.namespaceURI)
        assertEquals(validKey, request.textContent)
        val operation = elementsNamed(envelope.toByteArray(), "autorizacionComprobante").single()
        assertEquals("http://ec.gob.sri.ws.autorizacion", operation.namespaceURI)
    }

    private fun soap(body: String) =
        "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>$body</soap:Body></soap:Envelope>"

    @Test
    fun aReceptionAnswerWithoutMessagesReadsAsOneState() {
        val reading = ThrowawaySoap.readState(
            soap("<ns2:respuestaRecepcionComprobante xmlns:ns2=\"http://ec.gob.sri.ws.recepcion\"><RespuestaRecepcionComprobante><estado>RECIBIDA</estado><comprobantes/></RespuestaRecepcionComprobante></ns2:respuestaRecepcionComprobante>"),
        )
        assertEquals(listOf("RECIBIDA"), reading.states)
        assertTrue(reading.messages.isEmpty())
        assertNull(reading.fault)
    }

    @Test
    fun aReturnedDocumentReadsEveryMessageWithPaddedValuesTrimmed() {
        val reading = ThrowawaySoap.readState(
            soap(
                "<r><estado> DEVUELTA </estado><comprobantes><comprobante><claveAcceso>k</claveAcceso><mensajes>" +
                    "<mensaje><identificador> 43 </identificador><mensaje> CLAVE ACCESO REGISTRADA </mensaje><informacionAdicional> info </informacionAdicional><tipo> ERROR </tipo></mensaje>" +
                    "<mensaje><identificador>35</identificador><mensaje>ARCHIVO NO CUMPLE ESTRUCTURA XML</mensaje><tipo>ERROR</tipo></mensaje>" +
                    "</mensajes></comprobante></comprobantes></r>",
            ),
        )
        assertEquals(listOf("DEVUELTA"), reading.states)
        assertEquals(listOf("43", "35"), reading.messages.map { it.code })
        assertEquals("CLAVE ACCESO REGISTRADA", reading.messages[0].text)
        assertEquals("info", reading.messages[0].additionalInformation)
        assertEquals("ERROR", reading.messages[0].type)
        assertEquals("", reading.messages[1].additionalInformation)
    }

    @Test
    fun severalAuthorizationsAnAdvertenciaAndAnUnexpectedStateAreAllJustData() {
        val reading = ThrowawaySoap.readState(
            soap(
                "<r><claveAccesoConsultada>k</claveAccesoConsultada><numeroComprobantes>2</numeroComprobantes><autorizaciones>" +
                    "<autorizacion><estado>RECHAZADO</estado><comprobante><![CDATA[<factura><estado>NOT-A-STATE</estado></factura>]]></comprobante><mensajes/></autorizacion>" +
                    "<autorizacion><estado>AUTORIZADO</estado><numeroAutorizacion>n</numeroAutorizacion><mensajes>" +
                    "<mensaje><identificador>60</identificador><mensaje>ADVERTENCIA</mensaje><tipo>ADVERTENCIA</tipo></mensaje></mensajes></autorizacion>" +
                    "</autorizaciones></r>",
            ),
        )
        assertEquals(listOf("RECHAZADO", "AUTORIZADO"), reading.states)
        assertEquals(listOf("60"), reading.messages.map { it.code })
        assertEquals("ADVERTENCIA", reading.messages.single().type)
    }

    @Test
    fun aSoapFaultIsReportedAndAnEmptyAnswerHasNoStates() {
        val fault = ThrowawaySoap.readState(soap("<soap:Fault><faultcode>s</faultcode><faultstring> broken </faultstring></soap:Fault>"))
        assertEquals("broken", fault.fault)
        assertTrue(fault.states.isEmpty())
        val empty = ThrowawaySoap.readState(soap("<r><claveAccesoConsultada>k</claveAccesoConsultada><numeroComprobantes>0</numeroComprobantes><autorizaciones/></r>"))
        assertTrue(empty.states.isEmpty())
    }

    @Test
    fun aResponseCarryingADoctypeIsRefusedNotExpanded() {
        val hostile = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/hostname\">]><x>&e;</x>"
        try {
            ThrowawaySoap.readState(hostile)
            fail("a DOCTYPE must be refused")
        } catch (e: org.xml.sax.SAXException) {
            // expected
        }
    }

    @Test
    fun structureOnlyKeepsNamesNestingAndAttributeNamesAndNothingElse() {
        val authorizationNumber = "7".repeat(10)
        val response = soap(
            "<r xmlns:ns2=\"http://ec.gob.sri.ws.autorizacion\" kind=\"secret-attribute\"><autorizaciones><autorizacion><estado>AUTORIZADO</estado>" +
                "<numeroAutorizacion>$authorizationNumber</numeroAutorizacion><comprobante><![CDATA[<factura>customer data</factura>]]></comprobante></autorizacion></autorizaciones></r>",
        )
        val stripped = ThrowawaySoap.structureOnly(response)
        assertFalse(stripped.contains("AUTORIZADO"))
        assertFalse(stripped.contains(authorizationNumber))
        assertFalse(stripped.contains("customer data"))
        assertFalse(stripped.contains("secret-attribute"))
        assertTrue(stripped.contains("kind=\"\""))
        assertTrue(stripped.contains("xmlns:ns2=\"http://ec.gob.sri.ws.autorizacion\""))
        val document = ProbeEnvironmentGuard.parseHardened(stripped.toByteArray())
        assertNoCharacterData(document.documentElement)
        for (name in listOf("autorizaciones", "autorizacion", "estado", "numeroAutorizacion", "comprobante")) {
            assertEquals(name, 1, document.getElementsByTagNameNS("*", name).length)
        }
    }

    private fun assertNoCharacterData(node: Node) {
        var next = node.firstChild
        while (next != null) {
            when (next.nodeType) {
                Node.TEXT_NODE -> assertTrue("character data survived", next.nodeValue.isBlank())
                Node.CDATA_SECTION_NODE -> fail("a CDATA section survived")
                Node.ELEMENT_NODE -> assertNoCharacterData(next)
            }
            next = next.nextSibling
        }
    }

    @Test
    fun everyNetworkCallIsGuardedAndUsesOnlyTheGuardsEndpoints() {
        val calls = mutableListOf<URI>()
        val soap = ThrowawaySoap { endpoint, _ -> calls += endpoint; "<r/>" }
        soap.validarComprobante(validKey, validOctets)
        soap.autorizacionComprobante(validKey, validOctets)
        assertEquals(listOf(ProbeEnvironmentGuard.receptionEndpoint, ProbeEnvironmentGuard.authorizationEndpoint), calls)

        val productionDocument = ProbeDocumentFixture.canonicalOctets(ProbeDocumentFixture.Values(environmentCode = "2"))
        val before = calls.size
        assertRefuses("ambiente") { soap.validarComprobante(validKey, productionDocument) }
        assertRefuses("ambiente") { soap.autorizacionComprobante(validKey, productionDocument) }
        assertEquals("a refused call must not reach the transport", before, calls.size)
    }

    // ---- ProbeRunState and ProbeRunLog ------------------------------------------------------

    @Test
    fun theRunStateRoundTripsTheKeyAndTheSignedOctetsAndReportsASubmission() {
        val state = ProbeRunState(folder.root.toPath().resolve("nested").resolve("state.properties"))
        assertFalse(state.hasSubmission())
        assertNull(state.load())
        val octets = signedFixture()
        state.save(ProbeRunState.Record(validKey, octets, "RECIBIDA", 1_700_000_000_000L))
        assertTrue(state.hasSubmission())
        val loaded = state.load()!!
        assertEquals(validKey, loaded.accessKey)
        assertArrayEquals(octets, loaded.signedOctets)
        assertEquals("RECIBIDA", loaded.receptionState)
        assertEquals(1_700_000_000_000L, loaded.receivedAtEpochMillis)
        assertEquals("ProbeRunState.Record(redacted)", loaded.toString())
    }

    @Test
    fun theStateFileNameCarriesNoIdentifierAndDiffersPerSequential() {
        val outside = folder.newFolder("outside").toPath()
        val file = writeConfig(outside, completeProperties)
        val repo = folder.newFolder("repo").toPath()
        fun load(properties: Map<String, String>): ProbeConfig {
            writeConfig(outside, properties)
            return ProbeConfig.fromEnvironment(mapOf(ProbeConfig.ENVIRONMENT_VARIABLE to file.toString()), repo)!!
        }
        val first = ProbeRunState.forConfig(load(completeProperties))
        val second = ProbeRunState.forConfig(load(completeProperties + ("sequential" to "8")))
        val ruc = completeProperties.getValue("issuerRuc")
        val nameOf = { s: ProbeRunState -> ProbeRunState::class.java.getDeclaredField("file").also { it.isAccessible = true }.get(s).toString() }
        assertFalse(nameOf(first).contains(ruc))
        assertTrue(nameOf(first) != nameOf(second))
        assertTrue(nameOf(first).startsWith(outside.toRealPath().toString()))
    }

    @Test
    fun theRunLogMaskIsAppliedToEveryIdentificationNumberAndEmail() {
        val ruc = "9".repeat(10) + "001"
        val key = "1".repeat(46) + "123"
        assertEquals("ruc ***001 key ***123", ProbeRunLog.mask("ruc $ruc key $key"))
        assertEquals("short 123456789 kept", ProbeRunLog.mask("short 123456789 kept"))
        assertEquals("mail ***@example.com", ProbeRunLog.mask("mail billing@example.com"))
    }
}
