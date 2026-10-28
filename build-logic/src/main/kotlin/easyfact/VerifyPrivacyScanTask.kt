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

package easyfact

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

/**
 * Fails when a git-tracked file carries an identifier-shaped string that [PrivacyScan] does not
 * allowlist.
 *
 * The file list comes from `git ls-files -z`, never from a directory walk. That is the property
 * the gate rests on: whatever is not tracked is invisible to it, including everything under a
 * `build/` directory, so a generated test certificate cannot trip it. It also means the gate
 * measures what would be published, which is what the repository's privacy rule is about.
 *
 * `git` runs through an injected [ExecOperations] and no `Project` is captured, so the task is
 * configuration-cache safe. An empty tracked-file list is a failure, not a pass: a scan that
 * visited nothing certifies nothing. Binary files are skipped by extension, and the skipped count
 * is printed so that blind spot is visible instead of silent.
 *
 * On a violation the message carries rule, path and line only (see [PrivacyScan]). On success the
 * counts and [PrivacyScan.RESIDUAL_GAP] are printed, because a green run proves less than it
 * appears to. The task is untracked so it runs, and prints, on every invocation.
 *
 * Every tracked path is also checked against [PrivacyScan.KEY_MATERIAL_EXTENSIONS] before any
 * skip, so a tracked key store fails the gate by its file type even though it counts as binary or
 * is absent from the working tree.
 */
@UntrackedTask(because = "Lists tracked files from git on every run and prints the residual-gap statement")
abstract class VerifyPrivacyScanTask : DefaultTask() {

    @get:Input
    abstract val syntheticRuc: Property<String>

    @get:Input
    abstract val allowedEmailDomain: Property<String>

    /**
     * The checkout root: the directory `git ls-files` is run in.
     *
     * Deliberately `@Internal`, not an input directory. The files read are the ones git tracks, not
     * the directory tree, and declaring the whole checkout as an input makes it overlap every
     * module's `build/` output, which Gradle's implicit-dependency validation rejects.
     */
    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun verify() {
        val root = repositoryRoot.asFile.get()
        val tracked = trackedPaths(root)
        if (tracked.isEmpty()) {
            throw GradleException(
                "Privacy scan is wired to no files: `git ls-files` returned nothing in $root, " +
                    "so a pass would mean nothing was looked at.",
            )
        }

        // The path rule runs over every tracked path before any skip: a key store is refused even
        // when it counts as binary or is absent from the working tree, because git would still
        // publish it.
        val pathViolations = PrivacyScan.keyMaterialPathViolations(tracked)

        val scanned = ArrayList<PrivacyScan.ScannedFile>()
        var skippedBinary = 0
        var skippedMissing = 0
        for (path in tracked) {
            if (path.substringAfterLast('.', "").lowercase() in BINARY_EXTENSIONS) {
                skippedBinary++
                continue
            }
            val file = File(root, path)
            if (!file.isFile) {
                // Tracked but deleted from the working tree, or a submodule entry: nothing to read.
                skippedMissing++
                continue
            }
            // Decoding never throws: an undecodable byte becomes a replacement character.
            scanned += PrivacyScan.ScannedFile(path, String(file.readBytes(), Charsets.UTF_8).lines())
        }

        val preconditions = PrivacyScan.preconditionViolations(scanned)
        if (preconditions.isNotEmpty()) {
            throw GradleException(
                "Privacy scan precondition failed:\n" + preconditions.joinToString("\n") { "  $it" } +
                    "\n" + PrivacyScan.RESIDUAL_GAP,
            )
        }

        val allowlist = PrivacyScan.Allowlist(syntheticRuc.get(), allowedEmailDomain.get())
        val violations = (pathViolations + PrivacyScan.violations(scanned, allowlist)).toSortedSet().toList()
        val summary = "Scanned ${scanned.size} tracked file(s); skipped $skippedBinary as binary" +
            (if (skippedMissing > 0) " and $skippedMissing absent from the working tree" else "") +
            "; checked ${tracked.size} tracked path(s) against the key-material extension rule."
        println(summary)
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Privacy scan found identifier-shaped content or key-material files in tracked paths. " +
                    "Fix the file or remove it from git; do not loosen the rule. The matched value is " +
                    "deliberately not printed.\n" +
                    violations.joinToString("\n") { "  $it" } +
                    "\n" + PrivacyScan.RESIDUAL_GAP,
            )
        }
        println("No tracked file carries a non-allowlisted identifier shape.")
        println(PrivacyScan.RESIDUAL_GAP)
    }

    private fun trackedPaths(root: File): List<String> {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        try {
            execOperations.exec {
                commandLine("git", "ls-files", "-z")
                workingDir = root
                standardOutput = stdout
                errorOutput = stderr
            }
        } catch (e: Exception) {
            throw GradleException(
                "`git ls-files` failed in $root (is this a git checkout?): ${stderr.toString(Charsets.UTF_8).trim()}",
                e,
            )
        }
        return stdout.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
    }

    private companion object {
        /** Extensions whose content is not text. Skips are counted and printed, never silent. */
        val BINARY_EXTENSIONS = setOf("jar", "webp", "png", "pdf", "zip", "keystore")
    }
}
