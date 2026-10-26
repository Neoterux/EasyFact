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
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails the build when a variant's merged manifest carries a permission, exported component or
 * service that [ManifestPolicy] does not allow.
 *
 * The merged manifest is read, never the source manifest: a library or a variant overlay can add
 * to what the app actually ships, and only the merged result shows it. Nothing is resolved from
 * `Project` at execution time, so the task is configuration-cache safe.
 */
abstract class VerifyManifestPolicyTask : DefaultTask() {

    /** The variant's merged manifest (`SingleArtifact.MERGED_MANIFEST`). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    /** Substituted for `{applicationId}` in the allowlists. */
    @get:Input
    abstract val applicationId: Property<String>

    /** Reviewed allowlists. `services` is deliberately not the property name: it clashes with `Task.getServices()`. */
    @get:Input
    abstract val allowedPermissions: ListProperty<String>

    @get:Input
    abstract val allowedExportedComponents: ListProperty<String>

    @get:Input
    abstract val allowedServices: ListProperty<String>

    @TaskAction
    fun verify() {
        val violations = ManifestPolicy.violations(
            manifestText = mergedManifest.get().asFile.readText(),
            permissions = allowedPermissions.get(),
            exportedComponents = allowedExportedComponents.get(),
            services = allowedServices.get(),
            applicationId = applicationId.get(),
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Merged manifest policy violated:\n" + violations.joinToString("\n") { "  $it" } +
                    "\nIf an addition is intended, review it and add it to ManifestPolicy as a deliberate line.",
            )
        }
    }
}
