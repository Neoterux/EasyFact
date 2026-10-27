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

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.animalsniffer)
    id("easyfact.jvm-boundary")
}

// Pin the JDK 17 API level without provisioning a second JDK: the Gradle daemon runs on JDK 25,
// and `--release 17` / `-Xjdk-release=17` make the compilers reject anything newer than 17.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

// Android-membership gate: checks main-source bytecode against the API 31 signature, so a JDK 17
// API that Android API 31 lacks (for example java.util.HexFormat) fails `check`. Main source only:
// the JSR-105 and StAX verification oracle is legal in test source (D-04, AGENTS.md section 4).
val mainSourceSet = sourceSets["main"]
animalsniffer {
    sourceSets = listOf(mainSourceSet)
}

// The test-environment probe (`CelcerProbeTest`) is configured through a path to a properties
// file that lives outside this tree. Two separate traps sit between exporting that path and the
// probe seeing it, and both are handled here.
//
// A Gradle daemon's environment is fixed when the daemon starts. Exporting EASYFACT_PROBE_CONFIG
// in a shell that then reuses an older daemon leaves the forked test JVM unable to see it, and the
// probe skips — which looks exactly like a pass. The `easyfact.probeConfig` project property
// travels with the build invocation instead, so it reaches a daemon of any age; prefer it.
// Whichever source supplies the value, it is forwarded explicitly to the test JVM below.
//
// Gradle also does not treat an environment variable as a task input unless it is declared, so
// without `inputs.property` a second run, after the owner configures their machine, would report
// the task UP-TO-DATE and silently never run the probe. Declaring it makes a configuration change
// re-run the task. The value is only a path: editing the file it names, or wanting a fresh
// submission against an unchanged configuration, still needs `--rerun`.
val probeConfigPath = providers.gradleProperty("easyfact.probeConfig")
    .orElse(providers.environmentVariable("EASYFACT_PROBE_CONFIG"))
    .orElse("")

tasks.withType<Test>().configureEach {
    inputs.property("probeConfig", probeConfigPath)
    environment("EASYFACT_PROBE_CONFIG", probeConfigPath.get())
}

dependencies {
    // The artifact is a `.signature` file, which the plugin only finds through the `@signature`
    // qualifier (artifact type), not as the default jar.
    signature(libs.androidApi31Signature) {
        artifact {
            type = "signature"
            extension = "signature"
        }
    }
    testImplementation(libs.junit)
    // Synthetic certificates and identifiers. Test scope only: never `implementation`.
    testImplementation(project(":core:testing"))
}
