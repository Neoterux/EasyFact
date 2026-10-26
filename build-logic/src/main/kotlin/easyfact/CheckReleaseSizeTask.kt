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
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Shared measurement of the release APK and App Bundle, read from AGP's own artifact API.
 *
 * The inputs come from `SingleArtifact.APK` and `SingleArtifact.BUNDLE` through the variant
 * artifacts, never from globbing `build/outputs`, so the task depends on the tasks that produce
 * the artifacts and measures exactly what AGP built. No separate bundle-inspection tool is downloaded.
 */
abstract class ReleaseArtifactTask : DefaultTask() {

    /** `SingleArtifact.APK`: the directory holding the APK(s) and their listing metadata. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apkDir: DirectoryProperty

    /** `SingleArtifact.BUNDLE`: the signed App Bundle. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundle: RegularFileProperty

    /** Resolves the real APK file(s) from the listing in [apkDir]. */
    @get:Internal
    abstract val builtArtifactsLoader: Property<BuiltArtifactsLoader>

    protected fun measure(): SizeBaseline.Sizes {
        val artifacts = builtArtifactsLoader.get().load(apkDir.get())
            ?: throw GradleException("Could not read the APK listing in ${apkDir.get().asFile}")
        val apkFiles = artifacts.elements.map { File(it.outputFile) }
        if (apkFiles.isEmpty()) {
            throw GradleException("The release APK listing in ${apkDir.get().asFile} names no APK")
        }
        val apkBytes = apkFiles.sumOf { it.length() }
        val aabBytes = bundle.get().asFile.length()
        println("MEASURED apk.bytes=$apkBytes (files=${apkFiles.size}) aab.bytes=$aabBytes")
        return SizeBaseline.Sizes(apkBytes = apkBytes, aabBytes = aabBytes)
    }
}

/**
 * Fails when the measured release APK or App Bundle exceeds the committed baseline plus
 * [toleranceBytes].
 *
 * The baseline is a plain required `@InputFile`: marking a not-yet-existing file input as
 * optional was measured failing task validation on the very first run, so the baseline is
 * committed in the same change as this task. A missing file therefore fails loudly.
 *
 * The task is untracked so it re-measures and prints on every invocation; the work is a few
 * `File.length()` calls, and an "up-to-date" verdict would hide the measured numbers.
 */
@UntrackedTask(because = "Re-measures and prints the artifact sizes on every run; the work is a few stat calls")
abstract class CheckReleaseSizeTask : ReleaseArtifactTask() {

    /** `app/size-baseline.properties`, read at execution time. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

    /** Allowed growth over the baseline, in bytes. */
    @get:Input
    abstract val toleranceBytes: Property<Long>

    @TaskAction
    fun check() {
        val measured = measure()
        val recorded = SizeBaseline.parse(baseline.get().asFile.readText())
        val hits = SizeBaseline.violations(measured, recorded, toleranceBytes.get())
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Release artifact exceeds the recorded baseline:\n" + hits.joinToString("\n") { "  $it" } +
                    "\nIf the growth is intended, record it with: " +
                    "./gradlew :app:recordReleaseSizeBaseline -Preason=\"<why the size grew>\"",
            )
        }
    }
}
