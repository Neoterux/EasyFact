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
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.artifacts.result.UnresolvedDependencyResult
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a resolved runtime classpath carries a module that [DependencyPolicy]
 * denies or does not list.
 *
 * The inputs are the root components of the resolved graphs, obtained with
 * `configuration.incoming.resolutionResult.rootComponent`. That is the structured form Gradle
 * supports as a configuration-cache-compatible task input; no printed
 * dependency report is ever parsed. Nothing is resolved from `Project` at execution time.
 *
 * Project dependencies are part of the graph and are reported by their path (`:core:testing`), so
 * a shipping classpath that reaches the test-helper module is visible. Constraint edges are
 * skipped: a constraint selects a version for a module that is already in the graph, it does not
 * add one. An edge that failed to resolve is itself a violation, because a graph that could not be
 * read cannot be certified.
 */
abstract class VerifyDependencyPolicyTask : DefaultTask() {

    /** Resolved graph roots keyed by the name of the configuration they came from. */
    @get:Input
    abstract val roots: MapProperty<String, ResolvedComponentResult>

    @get:Input
    abstract val allowed: ListProperty<String>

    @get:Input
    abstract val denied: ListProperty<String>

    @get:Input
    abstract val allowedProjects: ListProperty<String>

    @get:Input
    abstract val deniedProjects: ListProperty<String>

    @TaskAction
    fun verify() {
        val graphs = roots.get()
        if (graphs.isEmpty()) {
            throw GradleException("Dependency policy gate is wired to no configuration: it would certify nothing")
        }
        val failures = ArrayList<String>()
        for ((configuration, root) in graphs.toSortedMap()) {
            val identifiers = HashSet<String>()
            val unresolved = HashSet<String>()
            collect(root, identifiers, unresolved)
            val violations = unresolved.map { "UNRESOLVED $it" } + DependencyPolicy.violations(
                identifiers = identifiers,
                allowed = allowed.get(),
                denied = denied.get(),
                allowedProjects = allowedProjects.get(),
                deniedProjects = deniedProjects.get(),
            )
            if (violations.isEmpty()) {
                logger.lifecycle(
                    "Dependency policy: ${identifiers.size} modules on $configuration, all allowed " +
                        "(a blocklist plus a reviewed allowlist, not a proof that nothing phones home)",
                )
            } else {
                failures += "Dependency policy violated on $configuration:\n" +
                    violations.joinToString("\n") { "  $it" }
            }
        }
        if (failures.isNotEmpty()) {
            throw GradleException(failures.joinToString("\n"))
        }
    }

    private fun collect(root: ResolvedComponentResult, identifiers: MutableSet<String>, unresolved: MutableSet<String>) {
        val seen = HashSet<ResolvedComponentResult>()
        val queue = ArrayDeque<ResolvedComponentResult>()
        seen += root
        queue += root
        while (queue.isNotEmpty()) {
            val component = queue.removeFirst()
            for (edge in component.dependencies) {
                when (edge) {
                    is UnresolvedDependencyResult -> unresolved += edge.requested.displayName
                    is ResolvedDependencyResult -> {
                        if (edge.isConstraint) continue
                        val selected = edge.selected
                        identifiers += identify(selected)
                        if (seen.add(selected)) queue += selected
                    }
                }
            }
        }
    }

    private fun identify(component: ResolvedComponentResult): String = when (val id = component.id) {
        is ProjectComponentIdentifier -> id.projectPath
        is ModuleComponentIdentifier -> "${id.group}:${id.module}"
        else -> id.displayName
    }
}
