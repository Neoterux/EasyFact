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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a project-owned R8 keep-rule file breaks [KeepRuleLint]'s policy.
 *
 * Every decision is delegated to [KeepRuleLint]. The task resolves nothing from `Project` at
 * execution time, so it is configuration-cache safe. An empty input collection is a failure,
 * not a pass: a gate wired to a glob that matches nothing would certify nothing.
 */
abstract class VerifyKeepRulesTask : DefaultTask() {

    /** The project-owned `src/<variant>/keepRules/<name>.keep` files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val keepRuleFiles: ConfigurableFileCollection

    /** Used only to print module-relative paths in messages; it does not affect the verdict. */
    @get:Internal
    abstract val displayRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val files = keepRuleFiles.files.sortedBy { it.path }
        if (files.isEmpty()) {
            throw GradleException(
                "Keep-rule hygiene gate is wired to no files: expected at least one src/<variant>/keepRules/<name>.keep",
            )
        }
        val root = displayRoot.asFile.get()
        val hits = files.flatMap { file ->
            KeepRuleLint.violations(file.readText(), file.relativeTo(root).path)
        }
        if (hits.isNotEmpty()) {
            throw GradleException("Keep-rule hygiene violated:\n" + hits.joinToString("\n") { "  $it" })
        }
    }
}
