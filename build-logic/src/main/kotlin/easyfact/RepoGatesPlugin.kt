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

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Home for the build gates that concern the whole repository rather than one module.
 *
 * The privacy scan reads `git ls-files` for the entire checkout, so it is registered once, on the
 * root project, and not per module: registering it per module would scan the same files once for
 * each module and misrepresent what each run covered.
 *
 * The plugin is applied from the root `plugins` block, before the root script body registers its
 * own `check` task. The attachment is therefore made lazily with `configureEach`, which works
 * whether `check` already exists or is registered afterwards; `named("check")` would fail in the
 * second case. Later repository-wide gates are added to [apply].
 */
class RepoGatesPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val verify = project.tasks.register("verifyPrivacyScan", VerifyPrivacyScanTask::class.java) {
            group = "verification"
            description = "Fails if a git-tracked file carries an identifier-shaped string " +
                "(RUC, cedula, access key, key marker, non-example email) that is not an allowlisted placeholder."
            repositoryRoot.set(project.layout.projectDirectory)
            syntheticRuc.set(PrivacyScan.Allowlist.DEFAULT.syntheticRuc)
            allowedEmailDomain.set(PrivacyScan.Allowlist.DEFAULT.allowedEmailDomain)
        }
        project.tasks.configureEach {
            if (name == "check") dependsOn(verify)
        }
    }
}
