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

import com.android.build.api.variant.BuiltArtifactsLoader
import java.io.File
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Fails when the release dex names a URL host that [UrlHostScan.ALLOWED_HOSTS] does not list.
 *
 * The input is the real release APK from `SingleArtifact.APK`, resolved through the variant's
 * [BuiltArtifactsLoader] exactly as `CheckReleaseSizeTask` does, so the task depends on the tasks
 * that build the APK and scans what AGP actually produced after R8. Every `classes*.dex` entry is
 * read; an APK with no dex entry is a failure, not a pass, because a scan that read nothing would
 * certify nothing.
 *
 * On success the full host set is printed, so a reviewer sees what is in there rather than only
 * that nothing failed, followed by [UrlHostScan.RESIDUAL_GAP]. The statement is part of the
 * result, not a footnote: a green run proves less than it appears to.
 *
 * The task is untracked so it prints on every invocation.
 */
@UntrackedTask(because = "Prints the observed host set and the residual-gap statement on every run")
abstract class VerifyReleaseUrlHostsTask : DefaultTask() {

    /** `SingleArtifact.APK`: the directory holding the APK(s) and their listing metadata. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDir: DirectoryProperty

    /** Resolves the real APK file(s) from the listing in [apkDir]. */
    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    @get:Input
    abstract val allowedHosts: ListProperty<String>

    @TaskAction
    fun verify() {
        val artifacts = builtArtifactsLoader.get().load(apkDir.get())
            ?: throw GradleException("Could not read the APK listing in ${apkDir.get().asFile}")
        val apkFiles = artifacts.elements.map { File(it.outputFile) }
        if (apkFiles.isEmpty()) {
            throw GradleException("The release APK listing in ${apkDir.get().asFile} names no APK")
        }

        val hosts = sortedSetOf<String>()
        var dexEntries = 0
        var dexBytes = 0L
        for (apk in apkFiles) {
            ZipFile(apk).use { zip ->
                for (entry in zip.entries().asSequence().filter { DEX_ENTRY.matches(it.name) }) {
                    val content = zip.getInputStream(entry).use { it.readBytes() }
                    dexEntries++
                    dexBytes += content.size
                    hosts += UrlHostScan.hosts(content)
                }
            }
        }
        if (dexEntries == 0) {
            throw GradleException("No classes*.dex entry was found in ${apkFiles.joinToString { it.name }}: nothing was scanned")
        }

        val violations = UrlHostScan.violations(hosts, allowedHosts.get())
        println("Scanned $dexEntries dex file(s), $dexBytes bytes, in ${apkFiles.joinToString { it.name }}")
        println("URL hosts found in the release dex (${hosts.size}): ${hosts.joinToString().ifEmpty { "(none)" }}")
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Release dex contains a non-allowlisted URL host:\n" + violations.joinToString("\n") { "  $it" } +
                    "\nIf the host is intended, review it and add it to UrlHostScan.ALLOWED_HOSTS as a deliberate line." +
                    "\n" + UrlHostScan.RESIDUAL_GAP,
            )
        }
        println("All hosts are allowlisted.")
        println(UrlHostScan.RESIDUAL_GAP)
    }

    private companion object {
        val DEX_ENTRY = Regex("classes\\d*\\.dex")
    }
}
