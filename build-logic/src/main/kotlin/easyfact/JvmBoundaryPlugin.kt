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
import org.gradle.api.plugins.JavaPluginExtension

/**
 * Registers `verifyPortableBoundary` on a plain JVM module and attaches it to `check`.
 *
 * The forbidden list is deliberately a blocklist of the packages the portability rule names
 * (AGENTS.md section 4). It cannot know which JDK 17 APIs Android API 31 lacks; that half is
 * Animal Sniffer's job. Only Gradle core APIs are used so `build-logic` needs no Kotlin plugin
 * on its classpath: the JDK 17 pin lives in each module's own build file.
 */
class JvmBoundaryPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        project.plugins.withId("java") {
            val mainOutput = project.extensions.getByType(JavaPluginExtension::class.java)
                .sourceSets.getByName("main").output
            val verify = project.tasks.register("verifyPortableBoundary", VerifyPortableBoundaryTask::class.java) {
                group = "verification"
                description = "Fails if main output references a package outside the portable JDK 17 / Android API 31 subset."
                classDirs.from(mainOutput.classesDirs)
                forbiddenPrefixes.set(FORBIDDEN_PREFIXES)
            }
            project.tasks.named("check") { dependsOn(verify) }

            // Dependency scope for the plain JVM modules: the Kotlin standard library only, plus
            // BouncyCastle on `:core:testing` alone. This is what makes it safe for the boundary
            // scanner above to look at each module's own output and nothing else, because nothing
            // else can be on the runtime classpath. The profile is resolved here, at configuration
            // time, so the task action reads plain inputs and stays configuration-cache safe.
            val profile = DependencyPolicy.profileFor(project.path)
            val dependencyPolicy = project.tasks.register("verifyDependencyPolicy", VerifyDependencyPolicyTask::class.java) {
                group = "verification"
                description = "Fails if the runtime classpath carries anything outside this module's dependency policy profile."
                roots.put(
                    "runtimeClasspath",
                    project.configurations.named("runtimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent },
                )
                allowed.set(profile.allowed)
                denied.set(profile.denied)
                allowedProjects.set(profile.allowedProjects)
                deniedProjects.set(profile.deniedProjects)
            }
            project.tasks.named("check") { dependsOn(dependencyPolicy) }
        }
    }

    private companion object {
        val FORBIDDEN_PREFIXES = listOf(
            "javax/xml/crypto/",
            "javax/xml/stream/",
            "org/xmlpull/",
            "android/",
            "androidx/",
            "dalvik/",
            "libcore/",
        )
    }
}
