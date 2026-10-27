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

import com.neoterux.easyfact.invoicing.result.Outcome
import com.neoterux.easyfact.invoicing.signing.IssuerNameFormat
import com.neoterux.easyfact.invoicing.signing.IssuerNameFormats
import com.neoterux.easyfact.invoicing.signing.SignedDocument
import com.neoterux.easyfact.invoicing.signing.SigningIdentity
import com.neoterux.easyfact.invoicing.signing.XadesBesSigner
import com.neoterux.easyfact.invoicing.verify.Jsr105Verifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test

/**
 * The probe: one real document, signed with the owner's real certificate, submitted once to the
 * SRI test environment and polled for its verdict.
 *
 * It is inert everywhere except the owner's machine. Every test begins by assuming the probe is
 * configured, so on any other machine, and in CI, each one reports as skipped rather than failed.
 * Run the three steps in order and individually, as the checkpoint instructs:
 *
 * - `offlineDryRun`: no network. Prints how each issuer-name rendering differs for the configured
 *   certificate, then signs and verifies offline.
 * - `submit`: guards, submits once, records the answer, persists the run state. A later run finds
 *   the state and does not submit again.
 * - `poll`: asks authorization about the recorded key until a final answer or the poll window ends.
 *
 * Nothing printed, logged or asserted here contains certificate bytes, the password, or a full
 * identification number: every failure message goes through [ProbeRunLog.mask] for the same reason
 * the log does, because an assertion message is written to the JUnit XML and the HTML report.
 * Issuer renderings are reported as attribute types and value kinds, never as values.
 */
class CelcerProbeTest {

    private val config: ProbeConfig? = ProbeConfig.fromEnvironment()

    @Test
    fun offlineDryRun() {
        Assume.assumeTrue("the probe is not configured on this machine", config != null)
        val config = config!!
        val log = ProbeRunLog.forConfig(config)
        val identity = loadIdentity(config)
        val certificate = identity.certificate

        log.line("dry run: certificate chain loaded; key usage and size follow")
        log.line("  RSA modulus bits: ${(certificate.publicKey as? RSAPublicKey)?.modulus?.bitLength() ?: "not RSA"}")
        log.line("  valid from ${certificate.notBefore.toInstant()} to ${certificate.notAfter.toInstant()}")
        log.line("  key usage (digitalSignature, nonRepudiation): ${certificate.keyUsage?.let { "${it[0]}, ${it[1]}" } ?: "extension absent"}")

        printIssuerRenderings(certificate, log)

        val values = valuesFor(config)
        for ((name, format) in IssuerNameFormats.ALL) {
            val signed = sign(config, identity, format)
            val octets = signed.octets()
            ProbeDocumentShape.assertMatchesRecordedShape(octets)
            ProbeEnvironmentGuard.requireTestEnvironment(
                ProbeDocumentFixture.accessKey(values).digits,
                octets,
                ProbeEnvironmentGuard.receptionEndpoint,
            )
            val verdict = Jsr105Verifier.verdict(octets)
            log.line("  issuer rendering $name: signed, shape and environment guard pass, JDK verification $verdict")
            assertEquals("the JDK did not validate the signature made with $name", Jsr105Verifier.Verdict.VALID, verdict)
        }
        log.line("dry run: configured rendering is ${config.issuerNameFormatName}")
        log.line("dry run: the document carries ${ProbeDocumentShape.recordedPaths().size} element paths, all matching the recorded element set")
    }

    @Test
    fun submit() {
        Assume.assumeTrue("the probe is not configured on this machine", config != null)
        val config = config!!
        val state = ProbeRunState.forConfig(config)
        Assume.assumeTrue("a submission is already recorded for this sequential; run poll instead", !state.hasSubmission())
        val log = ProbeRunLog.forConfig(config)

        val values = valuesFor(config)
        val accessKey = ProbeDocumentFixture.accessKey(values).digits
        val signed = sign(config, loadIdentity(config), config.issuerNameFormat)
        val octets = signed.octets()

        // The shape assertion and the environment guard both run before the network is touched.
        ProbeDocumentShape.assertMatchesRecordedShape(octets)
        ProbeEnvironmentGuard.requireTestEnvironment(accessKey, octets, ProbeEnvironmentGuard.receptionEndpoint)
        val paths = ProbeDocumentShape.elementPaths(octets)
        log.line("submit: issuer rendering ${config.issuerNameFormatName}; ${octets.size} octets; element set ${paths.size} paths: $paths")

        val response = ThrowawaySoap().validarComprobante(accessKey, octets)
        val receivedAt = Instant.now()
        // The raw answer is kept before anything parses it, so a parse problem after a real
        // reception can never lose the only record of what the SRI said.
        writeRaw(config, "last-recepcion-response.xml", response)
        writeStructure(config, "structure-only-recepcion.xml", response, log)
        val reading = readOrFail(response, log)
        log.line("submit: reception states ${reading.states}; fault ${reading.fault ?: "none"}")
        reading.messages.forEach { log.line("submit: message code=${it.code} type=${it.type} text=${it.text} info=${it.additionalInformation}") }

        val received = reading.states.any { it == "RECIBIDA" }
        if (received) {
            state.save(ProbeRunState.Record(accessKey, octets, reading.states.first(), receivedAt.toEpochMilli()))
            log.line("submit: reception recorded; a re-run will poll instead of resubmitting")
        }
        // The SRI echoes the RUC back inside `informacionAdicional`, so the failure message is
        // masked exactly as the run log is: an assertion message reaches the JUnit XML and the
        // HTML report, and neither is a place for a full identification number.
        assertTrue(ProbeRunLog.mask("reception did not answer RECIBIDA: states=${reading.states} messages=${reading.messages}"), received)
    }

    @Test
    fun poll() {
        Assume.assumeTrue("the probe is not configured on this machine", config != null)
        val config = config!!
        val record = ProbeRunState.forConfig(config).load()
        Assume.assumeTrue("nothing has been submitted for this sequential yet; run submit first", record != null)
        record!!
        val log = ProbeRunLog.forConfig(config)
        val soap = ThrowawaySoap()

        val startedPolling = Instant.now()
        val deadline = startedPolling.plus(config.pollWindow)
        log.line("poll: starting ${Duration.between(Instant.ofEpochMilli(record.receivedAtEpochMillis), startedPolling).seconds} s after reception; window ${config.pollWindow.toMinutes()} min")

        var delay = Duration.ofSeconds(5)
        var firstAnswerLogged = false
        var finalStates: List<String> = emptyList()
        var finalMessages: List<ThrowawaySoap.Message> = emptyList()
        while (true) {
            val response = soap.autorizacionComprobante(record.accessKey, record.signedOctets)
            writeRaw(config, "last-autorizacion-response.xml", response)
            val reading = readOrFail(response, log)
            val elapsed = Duration.between(Instant.ofEpochMilli(record.receivedAtEpochMillis), Instant.now()).seconds
            log.line("poll: states ${reading.states}; fault ${reading.fault ?: "none"}; ${elapsed}s after reception")
            reading.messages.forEach { log.line("poll: message code=${it.code} type=${it.type} text=${it.text} info=${it.additionalInformation}") }

            val answered = reading.states.any { it.isNotEmpty() }
            if (answered) writeStructure(config, "structure-only-autorizacion.xml", response, log)
            if (answered && !firstAnswerLogged) {
                firstAnswerLogged = true
                log.line("poll: first authorization answer ${elapsed}s after the reception response (a single observation, not a latency guarantee)")
            }
            finalStates = reading.states
            finalMessages = reading.messages
            if (reading.states.any { it in FINAL_STATES }) {
                log.line("poll: final states $finalStates ${elapsed}s after reception")
                break
            }
            if (Instant.now().plus(delay).isAfter(deadline)) {
                log.line("poll: no final state within the window; last states $finalStates")
                fail(ProbeRunLog.mask("no final authorization state within the poll window; last states=$finalStates"))
            }
            Thread.sleep(delay.toMillis())
            delay = minOf(delay.multipliedBy(3).dividedBy(2), MAX_POLL_DELAY)
        }
        assertTrue(ProbeRunLog.mask("final states were $finalStates, not AUTORIZADO; messages=$finalMessages"), "AUTORIZADO" in finalStates)
    }

    private fun valuesFor(config: ProbeConfig): ProbeDocumentFixture.Values {
        val defaults = ProbeDocumentFixture.Values()
        return defaults.copy(
            issueDate = LocalDate.now(ZoneId.of("America/Guayaquil")),
            issuerRuc = config.issuerRuc,
            establishmentCode = config.establishmentCode,
            emissionPointCode = config.emissionPointCode,
            sequential = config.sequential,
            issuerLegalName = config.issuerLegalName ?: defaults.issuerLegalName,
            headOfficeAddress = config.headOfficeAddress ?: defaults.headOfficeAddress,
        )
    }

    private fun sign(config: ProbeConfig, identity: SigningIdentity, format: IssuerNameFormat): SignedDocument {
        val document = ProbeDocumentFixture.document(valuesFor(config))
        return when (val outcome = XadesBesSigner(issuerNameFormat = format).sign(document, identity)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> throw AssertionError("signing failed at a named stage: ${outcome.error}")
        }
    }

    private fun loadIdentity(config: ProbeConfig): SigningIdentity {
        val store = KeyStore.getInstance("PKCS12")
        Files.newInputStream(config.pkcs12Path).use { store.load(it, config.password) }
        val keyAliases = store.aliases().toList().filter { store.isKeyEntry(it) }
        val alias = config.alias ?: run {
            check(keyAliases.size == 1) {
                "the PKCS#12 holds ${keyAliases.size} key entries; set `alias` in the probe configuration to choose one"
            }
            keyAliases.single()
        }
        val key = store.getKey(alias, config.password) as? PrivateKey ?: error("the chosen alias has no private key")
        val certificate = store.getCertificate(alias) as? X509Certificate ?: error("the chosen alias has no X.509 certificate")
        return SigningIdentity(key, certificate)
    }

    /**
     * Reports, for every issuer-name rendering, the attribute types in order and whether the
     * organizationIdentifier value (OID 2.5.4.97), if present, is written as hex DER or as text.
     * No attribute value is printed: the answer the owner needs is about the rule, not the issuer.
     */
    private fun printIssuerRenderings(certificate: X509Certificate, log: ProbeRunLog) {
        val baseline = IssuerNameFormats.ALL.first().second.render(certificate)
        var carriesOrganizationIdentifier = false
        for ((name, format) in IssuerNameFormats.ALL) {
            val rendering = format.render(certificate)
            val attributes = splitAttributes(rendering)
            val types = attributes.map { it.substringBefore('=').trim() }
            val organizationIdentifier = attributes.firstOrNull { it.substringBefore('=').trim().removePrefix("OID.") == ORGANIZATION_IDENTIFIER_OID }
            if (organizationIdentifier != null) carriesOrganizationIdentifier = true
            val form = organizationIdentifier?.let {
                if (it.substringAfter('=').trim().startsWith("#")) "hex DER" else "decoded text"
            } ?: "absent"
            log.line("  rendering $name: types $types; 2.5.4.97 value $form; identical to the first rendering: ${rendering == baseline}")
        }
        log.line("  issuer carries OID $ORGANIZATION_IDENTIFIER_OID: $carriesOrganizationIdentifier")
    }

    /** Splits an RFC 2253 or RFC 1779 name on commas that are not escaped. */
    private fun splitAttributes(name: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        for (c in name) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' -> { current.append(c); escaped = true }
                c == ',' -> { parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }

    /** Keeps the raw [response] beside the run state, outside the repository and readable by the owner only. */
    private fun writeRaw(config: ProbeConfig, fileName: String, response: String) {
        val target = ProbeRunState.directory(config).resolve(fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, response)
        ProbeRunState.restrictToOwner(target)
    }

    /** Reads [response], or logs the start of it, masked, and fails when it is not XML (an HTML error page, say). */
    private fun readOrFail(response: String, log: ProbeRunLog): ThrowawaySoap.Reading =
        try {
            ThrowawaySoap.readState(response)
        } catch (e: org.xml.sax.SAXException) {
            log.line("response was not readable XML; first characters: ${response.take(300).replace('\n', ' ')}")
            throw AssertionError("the response was not readable XML", e)
        }

    /** Writes a value-stripped copy of [response] beside the state, for the owner to review and commit. */
    private fun writeStructure(config: ProbeConfig, fileName: String, response: String, log: ProbeRunLog) {
        val stripped = try {
            ThrowawaySoap.structureOnly(response)
        } catch (e: org.xml.sax.SAXException) {
            log.line("structure: the response could not be stripped (${e.javaClass.simpleName}); nothing written")
            return
        }
        val target: Path = ProbeRunState.directory(config).resolve(fileName)
        Files.createDirectories(target.parent)
        Files.writeString(target, stripped)
        log.line("structure: value-stripped response written as $fileName beside the run state")
    }

    private companion object {
        const val ORGANIZATION_IDENTIFIER_OID = "2.5.4.97"
        val FINAL_STATES = setOf("AUTORIZADO", "NO AUTORIZADO", "RECHAZADO")
        val MAX_POLL_DELAY: Duration = Duration.ofMinutes(2)
    }
}
