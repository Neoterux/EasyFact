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

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project

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
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
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
            }
        }
    }
}
