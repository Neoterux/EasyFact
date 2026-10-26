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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a module's own compiled main output names a forbidden package.
 *
 * Only the module's own class directories are scanned, never a dependency jar: `kotlin-stdlib`
 * legitimately names an Android class by string for a reflective SDK-version probe, so scanning
 * it would produce a false failure and invite widening the forbidden list. Dependency scope is
 * enforced separately by the classpath allowlist.
 *
 * All decisions are delegated to [ClassFileScan]. The task holds no mutable state and resolves
 * nothing from `Project` at execution time, so it is configuration-cache safe and, because every
 * input is declared, up-to-date checking never reuses a stale verdict.
 */
abstract class VerifyPortableBoundaryTask : DefaultTask() {

    /** Class directories of the module's main source set output. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirs: ConfigurableFileCollection

    /** Forbidden packages in slash form, for example `javax/xml/stream/`. */
    @get:Input
    abstract val forbiddenPrefixes: ListProperty<String>

    @TaskAction
    fun verify() {
        val prefixes = forbiddenPrefixes.get()
        val hits = ArrayList<String>()
        classDirs.asFileTree.matching { include("**/*.class") }.visit {
            if (!isDirectory) {
                val bytes = file.readBytes()
                for (constant in ClassFileScan.violations(bytes, prefixes)) {
                    hits += "${relativePath.pathString} -> $constant"
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                "Portability boundary violated:\n" + hits.sorted().joinToString("\n") { "  $it" },
            )
        }
    }
}
