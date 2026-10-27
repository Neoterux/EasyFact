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

import com.neoterux.easyfact.invoicing.signing.IssuerNameFormat
import com.neoterux.easyfact.invoicing.signing.IssuerNameFormats
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Properties

/**
 * Owner-held configuration for the test-environment probe.
 *
 * The configuration lives in a properties file that the environment variable [ENVIRONMENT_VARIABLE]
 * names, and that file must sit **outside** the repository working tree. "Gitignored" is a weaker
 * guarantee than "cannot be staged at all": a file inside the tree can be force-added by accident,
 * a file outside it cannot. [fromEnvironment] therefore throws when the file resolves inside the tree.
 *
 * Required keys: `pkcs12Path`, `pkcs12Password`, `issuerRuc`, `establishmentCode`,
 * `emissionPointCode`, `sequential`. Optional keys: `alias`, `issuerNameFormat` (a name from
 * [IssuerNameFormats.ALL]), `pollWindowMinutes`, `issuerLegalName`, `headOfficeAddress`.
 *
 * Every value in the file is identifying or secret. [toString] renders none of it, and nothing in
 * this class logs a value.
 *
 * Test source only: it reads files and uses `java.nio`, which is fine here and nowhere in main.
 */
class ProbeConfig private constructor(
    val pkcs12Path: Path,
    val password: CharArray,
    val issuerRuc: String,
    val establishmentCode: String,
    val emissionPointCode: String,
    val sequential: Long,
    val alias: String?,
    val issuerNameFormatName: String,
    val issuerNameFormat: IssuerNameFormat,
    val pollWindow: Duration,
    val issuerLegalName: String?,
    val headOfficeAddress: String?,
    /** The directory the configuration file sits in; the run state and run log are written here. */
    val stateDirectory: Path,
) {

    override fun toString(): String = "ProbeConfig(redacted)"

    companion object {
        const val ENVIRONMENT_VARIABLE = "EASYFACT_PROBE_CONFIG"

        private val REQUIRED_KEYS = listOf(
            "pkcs12Path",
            "pkcs12Password",
            "issuerRuc",
            "establishmentCode",
            "emissionPointCode",
            "sequential",
        )

        private const val DEFAULT_POLL_WINDOW_MINUTES = 20L
        private const val DEFAULT_ISSUER_NAME_FORMAT = "Rfc2253Jdk"

        /**
         * The configuration, or `null` when the probe is not configured on this machine: the
         * variable is unset, the file does not exist, or a required key is missing.
         *
         * **Every** path that returns `null` reports why through [onSkip], which by default writes
         * to the standard error stream. A silent skip is indistinguishable from a pass, and the two
         * cheapest mistakes to make here — never exporting the variable, and exporting it into a
         * shell whose Gradle daemon was started earlier and therefore cannot see it — both land on
         * the first branch. Leaving that one quiet cost three runs that looked like successes.
         *
         * @throws IllegalStateException if the configuration file resolves inside [repositoryRoot]
         */
        fun fromEnvironment(
            environment: Map<String, String> = System.getenv(),
            repositoryRoot: Path? = findRepositoryRoot(),
            onSkip: (String) -> Unit = DEFAULT_SKIP_REPORTER,
        ): ProbeConfig? {
            val configured = environment[ENVIRONMENT_VARIABLE]?.takeIf { it.isNotBlank() }
                ?: return skip(onSkip, "the environment variable `$ENVIRONMENT_VARIABLE` is unset or blank")
            val file = Path.of(configured)
            if (!Files.isRegularFile(file)) {
                return skip(onSkip, "`$ENVIRONMENT_VARIABLE` names `$configured`, which is not a regular file")
            }

            val realFile = file.toRealPath()
            if (repositoryRoot != null) {
                val realRoot = repositoryRoot.toRealPath()
                check(!realFile.startsWith(realRoot)) {
                    "refusing: the probe configuration file is inside the repository working tree; " +
                        "move it outside the tree so it can never be staged"
                }
            }

            val properties = Properties()
            try {
                Files.newBufferedReader(realFile).use { properties.load(it) }
            } catch (e: IOException) {
                return skip(onSkip, "the configuration file could not be read")
            }

            fun value(key: String): String? = properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }

            for (key in REQUIRED_KEYS) {
                if (value(key) == null) return skip(onSkip, "required key `$key` is missing")
            }
            val sequential = value("sequential")!!.toLongOrNull()
            if (sequential == null || sequential < 1) return skip(onSkip, "key `sequential` is not a positive integer")

            val formatName = value("issuerNameFormat") ?: DEFAULT_ISSUER_NAME_FORMAT
            val format = IssuerNameFormats.ALL.firstOrNull { it.first == formatName }?.second
                ?: return skip(onSkip, "key `issuerNameFormat` does not name a known format")

            val pollMinutes = value("pollWindowMinutes")?.toLongOrNull() ?: DEFAULT_POLL_WINDOW_MINUTES
            if (pollMinutes < 1) return skip(onSkip, "key `pollWindowMinutes` is not a positive integer")

            return ProbeConfig(
                pkcs12Path = Path.of(value("pkcs12Path")!!),
                // The password is kept exactly as written, including any surrounding whitespace the
                // trimmed lookup above would drop; read it raw.
                password = (properties.getProperty("pkcs12Password") ?: "").toCharArray(),
                issuerRuc = value("issuerRuc")!!,
                establishmentCode = value("establishmentCode")!!,
                emissionPointCode = value("emissionPointCode")!!,
                sequential = sequential,
                alias = value("alias"),
                issuerNameFormatName = formatName,
                issuerNameFormat = format,
                pollWindow = Duration.ofMinutes(pollMinutes),
                issuerLegalName = value("issuerLegalName"),
                headOfficeAddress = value("headOfficeAddress"),
                stateDirectory = realFile.parent,
            )
        }

        /** Reports a skip and its reason, so the run says what it wants rather than passing quietly. */
        private val DEFAULT_SKIP_REPORTER: (String) -> Unit = { reason ->
            System.err.println("ProbeConfig: probe skipped, $reason.")
        }

        private fun skip(onSkip: (String) -> Unit, reason: String): ProbeConfig? {
            onSkip(reason)
            return null
        }

        /**
         * The nearest ancestor of the working directory that holds a `.git` entry (a directory in
         * a normal checkout, a file in a linked worktree), or `null` outside any checkout.
         */
        fun findRepositoryRoot(start: Path = Path.of("").toAbsolutePath()): Path? {
            var current: Path? = start
            while (current != null) {
                if (Files.exists(current.resolve(".git"))) return current
                current = current.parent
            }
            return null
        }
    }
}
