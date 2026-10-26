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

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Single home for every build gate that applies to the `:app` module.
 *
 * Applying the plugin before or after `com.android.application` makes no difference: all work
 * is deferred to `withId("com.android.application")`. Later plans add their task registrations
 * to [apply] rather than scattering gate logic through `app/build.gradle.kts`.
 */
class AppGatesPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            registerKeepRuleGate(project)
            val dependencyPolicy = registerDependencyPolicyGate(project)

            // The heavy release-artifact gates live here, not on `check`: they force a full
            // release build and an App Bundle, which is too much for the per-commit loop. Later
            // plans add their own release-artifact gates to this aggregate.
            project.tasks.register("verifyRelease") {
                group = "verification"
                description = "Runs the release-artifact gates: size baseline and keep-rule hygiene."
                dependsOn("checkReleaseSize", "verifyKeepRules")
            }

            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

            // No build-type selector: every variant the project defines gets its own backup gate.
            components.onVariants { variant ->
                registerBackupRulesGate(project, variant)
                addToDependencyPolicyGate(dependencyPolicy, variant)
            }

            components.onVariants(components.selector().withBuildType("release")) { variant ->
                // Read the effective result from the variant instead of trusting that the DSL
                // block was applied: a gate that only checks its own input proves nothing.
                val minified = variant.isMinifyEnabled
                val shrunk = variant.shrinkResources
                if (!minified || !shrunk) {
                    throw GradleException(
                        "release variant must be minified and resource-shrunk " +
                            "(isMinifyEnabled=$minified, shrinkResources=$shrunk)",
                    )
                }

                registerSizeGates(project, variant)
            }
        }
    }

    /**
     * `verifyKeepRules` reads only the keep rules this project writes: `src/<variant>/keepRules/<name>.keep`.
     *
     * The scope is deliberate. The merged R8 configuration under
     * `build/outputs/mapping/release/configuration.txt` also concatenates AGP's own defaults and
     * every AAR's consumer rules, which the project does not write and must not be failed on;
     * widening the input to that file would make the gate fail on code nobody here controls.
     * AGP's R8 configuration analysis report is a human review aid and is intentionally not wired to `check`.
     */
    private fun registerKeepRuleGate(project: Project) {
        val verify = project.tasks.register("verifyKeepRules", VerifyKeepRulesTask::class.java) {
            group = "verification"
            description = "Fails if a project-owned keep rule lacks a '# Reason:' line or is a blanket pattern."
            keepRuleFiles.from(project.layout.projectDirectory.asFileTree.matching { include("src/*/keepRules/*.keep") })
            displayRoot.set(project.layout.projectDirectory)
        }
        project.tasks.named("check") { dependsOn(verify) }
    }

    /**
     * `verifyDependencyPolicy` checks every variant's resolved runtime classpath against the telemetry
     * denylist and the reviewed group allowlist, and keeps BouncyCastle and `:core:testing` off every
     * shipping classpath (D-12 layer 1, D-13).
     *
     * The task is registered once; each variant adds its own graph root in [addToDependencyPolicyGate],
     * so a future flavour or build type is covered without editing this plugin. The root is taken
     * from `resolutionResult.rootComponent`, the configuration-cache-compatible structured form.
     * It is a blocklist and an allowlist, not a proof that nothing phones home.
     */
    private fun registerDependencyPolicyGate(project: Project): TaskProvider<VerifyDependencyPolicyTask> {
        val verify = project.tasks.register("verifyDependencyPolicy", VerifyDependencyPolicyTask::class.java) {
            group = "verification"
            description = "Fails if a shipping runtime classpath carries a denied or unlisted dependency."
            allowed.set(DependencyPolicy.APP_ALLOWED)
            denied.set(DependencyPolicy.DENIED)
            allowedProjects.set(DependencyPolicy.APP_ALLOWED_PROJECTS)
            deniedProjects.set(DependencyPolicy.APP_DENIED_PROJECTS)
        }
        project.tasks.named("check") { dependsOn(verify) }
        return verify
    }

    private fun addToDependencyPolicyGate(verify: TaskProvider<VerifyDependencyPolicyTask>, variant: ApplicationVariant) {
        val runtime = variant.runtimeConfiguration
        verify.configure { roots.put(runtime.name, runtime.incoming.resolutionResult.rootComponent) }
    }

    /**
     * `verify<Variant>BackupRules` asserts default-deny backup on the variant's **merged** manifest
     * and the rule files it points at. The source manifest is deliberately never read: a variant
     * overlay or a library can change what the app actually ships, and only the merged result shows it.
     */
    private fun registerBackupRulesGate(project: Project, variant: ApplicationVariant) {
        val taskName = "verify${variant.name.replaceFirstChar { it.uppercase() }}BackupRules"
        val verify = project.tasks.register(taskName, VerifyBackupRulesTask::class.java) {
            group = "verification"
            description = "Fails if the ${variant.name} merged manifest's backup rules are not default-deny."
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            variant.sources.res?.all?.let { dirs -> resourceDirs.from(dirs.map { layers -> layers.flatten() }) }
            expectedAllowBackup.set(EXPECTED_ALLOW_BACKUP)
            extractionDomains.set(BackupRulesCheck.EXTRACTION_DOMAINS)
            legacyDomains.set(BackupRulesCheck.LEGACY_DOMAINS)
        }
        project.tasks.named("check") { dependsOn(verify) }
    }

    /**
     * Registers the size gate and the baseline recorder for the release variant, wiring both to
     * AGP's own APK and App Bundle artifacts.
     */
    private fun registerSizeGates(project: Project, variant: ApplicationVariant) {
        val baselineFile = project.layout.projectDirectory.file(BASELINE_FILE_NAME)
        project.tasks.register("checkReleaseSize", CheckReleaseSizeTask::class.java) {
            group = "verification"
            description = "Fails if the release APK or App Bundle exceeds the committed size baseline."
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            baseline.set(baselineFile)
            // Byte-for-byte reproducibility across machines and JDKs was never demonstrated, only
            // across reruns on one machine, so a baseline compared on another machine could drift
            // by a few bytes. One KiB absorbs that while any real dependency growth far exceeds it.
            toleranceBytes.set(SIZE_TOLERANCE_BYTES)
        }
        project.tasks.register("recordReleaseSizeBaseline", RecordReleaseSizeBaselineTask::class.java) {
            group = "verification"
            description = "Re-measures the release artifacts and rewrites the size baseline; requires -Preason."
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            bundle.set(variant.artifacts.get(SingleArtifact.BUNDLE))
            builtArtifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            this.baselineFile.set(baselineFile)
            reason.set(project.providers.gradleProperty("reason"))
        }
    }

    private companion object {
        const val BASELINE_FILE_NAME = "size-baseline.properties"
        const val SIZE_TOLERANCE_BYTES = 1024L

        /** Stays `true` by decision so the rules themselves remain exercisable on a device. */
        const val EXPECTED_ALLOW_BACKUP = "true"
    }
}
