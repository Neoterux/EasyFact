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

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations

/**
 * Mints the PKCS#12 stores and expected signed documents of the on-device golden-byte test into
 * [outputDir], by running the generator in a forked JVM.
 *
 * The generator classpath is supplied as a property and the process is started through an injected
 * [ExecOperations]; nothing is resolved from `Project` at execution time, so the task is
 * configuration-cache safe. The directory is wiped before every run, so a case that was dropped from
 * the generator cannot linger as a stale file.
 *
 * It fails, rather than succeeding with nothing, when the generator wrote no manifest or when
 * [GoldenAssetCheck] finds the manifest and the files disagreeing: a device matrix that ran against no
 * assets would measure nothing.
 *
 * The task is untracked: it runs on every build that needs the assets, so a minted store never goes
 * stale on disk and key material is not kept alive by an up-to-date check. The output directory is
 * under `build/`, which is not tracked by git.
 */
@UntrackedTask(because = "Minted key material is regenerated on every build, never kept by an up-to-date check")
abstract class GenerateGoldenSigningAssetsTask : DefaultTask() {

    /** The generator's own classpath: `:core:testing` and `:core:invoicing` with their runtime dependencies. */
    @get:Classpath
    abstract val generatorClasspath: ConfigurableFileCollection

    @get:Input
    abstract val mainClass: Property<String>

    /** The assets root: it is added to the instrumented-test APK as-is, so files live under `golden/`. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        if (generatorClasspath.isEmpty) {
            throw GradleException("The golden asset generator has an empty classpath")
        }
        val root = outputDir.get().asFile
        root.deleteRecursively()
        root.mkdirs()

        val generatorClasspathFiles = generatorClasspath
        val generatorMain = mainClass.get()
        execOperations.javaexec {
            classpath = generatorClasspathFiles
            mainClass.set(generatorMain)
            args(root.absolutePath)
        }

        val golden = File(root, "golden")
        val manifest = File(golden, "manifest.properties")
        val problems = GoldenAssetCheck.problems(manifest.takeIf { it.isFile }?.readText(Charsets.ISO_8859_1)) { name ->
            File(golden, name).let { if (it.isFile) it.length() else -1L }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The golden asset generator produced an incomplete asset set in $root:\n" + problems.joinToString("\n") { "  $it" },
            )
        }
        val assets = golden.listFiles().orEmpty().count { it.isFile }
        logger.lifecycle("Generated $assets golden signing assets into $root")
    }
}
