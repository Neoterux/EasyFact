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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Properties

/**
 * What the probe remembers between runs, so that a second run polls instead of resubmitting
 * (a naive re-run would send the same access key again and earn a duplicate-registration error,
 * which both burns a sequential and muddies the one verdict the probe exists to obtain).
 *
 * The record is written after the first successful reception, into [directory], which is the
 * directory the owner's external configuration file sits in. Nothing here is ever written under
 * the repository. The record is keyed by RUC and sequential, but the file name carries only a hash
 * of them: a directory listing must not reveal the RUC.
 */
class ProbeRunState(private val file: Path) {

    /** What was submitted: the key, the exact signed octets, the reception answer and when it came. */
    class Record(
        val accessKey: String,
        val signedOctets: ByteArray,
        val receptionState: String,
        val receivedAtEpochMillis: Long,
    ) {
        /** Never renders the key or the document. */
        override fun toString(): String = "ProbeRunState.Record(redacted)"
    }

    /** `null` until a submission has been recorded. */
    fun load(): Record? {
        if (!Files.isRegularFile(file)) return null
        val properties = Properties()
        Files.newBufferedReader(file).use { properties.load(it) }
        return Record(
            accessKey = properties.getProperty("accessKey") ?: return null,
            signedOctets = Base64.getDecoder().decode(properties.getProperty("signedOctets") ?: return null),
            receptionState = properties.getProperty("receptionState") ?: return null,
            receivedAtEpochMillis = properties.getProperty("receivedAtEpochMillis")?.toLongOrNull() ?: return null,
        )
    }

    /** Whether a submission has already been recorded. */
    fun hasSubmission(): Boolean = load() != null

    fun save(record: Record) {
        val properties = Properties()
        properties.setProperty("accessKey", record.accessKey)
        properties.setProperty("signedOctets", Base64.getEncoder().encodeToString(record.signedOctets))
        properties.setProperty("receptionState", record.receptionState)
        properties.setProperty("receivedAtEpochMillis", record.receivedAtEpochMillis.toString())
        Files.createDirectories(file.parent)
        Files.newBufferedWriter(file).use { properties.store(it, null) }
        restrictToOwner(file)
    }

    companion object {
        private const val STATE_DIRECTORY = "easyfact-probe"

        /** The state file for [config]'s RUC and sequential, beside the configuration file. */
        fun forConfig(config: ProbeConfig): ProbeRunState =
            ProbeRunState(directory(config).resolve("state-${keyHash(config)}.properties"))

        /** The directory that holds the state file, the run log and the stripped response structures. */
        fun directory(config: ProbeConfig): Path = config.stateDirectory.resolve(STATE_DIRECTORY)

        internal fun keyHash(config: ProbeConfig): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("${config.issuerRuc}|${config.establishmentCode}|${config.emissionPointCode}|${config.sequential}".toByteArray())
            return digest.take(6).joinToString("") { "%02x".format(it) }
        }

        internal fun restrictToOwner(path: Path) {
            try {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            } catch (e: UnsupportedOperationException) {
                // A file system without POSIX permissions: the location outside the tree is the guard.
            }
        }
    }
}

/**
 * The probe's local run log. It sits beside the external configuration file, never in the
 * repository, and it is identifier-free by construction: [mask] is applied to every line, so a
 * digit run of ten or more (a RUC, a cédula, an access key) is reduced to its last three digits and
 * an email address loses its local part. It never receives certificate bytes or the password,
 * because no caller has them to hand it.
 */
class ProbeRunLog(private val file: Path) {

    fun line(text: String) {
        val stamped = "${Instant.now()} ${mask(text)}"
        println(stamped)
        Files.createDirectories(file.parent)
        Files.writeString(file, stamped + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        ProbeRunState.restrictToOwner(file)
    }

    companion object {
        private val LONG_DIGIT_RUN = Regex("[0-9]{10,}")
        private val EMAIL_LOCAL_PART = Regex("[A-Za-z0-9._%+-]+@")

        fun forConfig(config: ProbeConfig): ProbeRunLog = ProbeRunLog(ProbeRunState.directory(config).resolve("run.log"))

        /** [text] with long digit runs reduced to `***` plus their last three digits and email local parts masked. */
        fun mask(text: String): String =
            text.replace(LONG_DIGIT_RUN) { "***" + it.value.takeLast(3) }.replace(EMAIL_LOCAL_PART, "***@")
    }
}
