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

/**
 * Pure policy over the module identifiers of a resolved dependency graph. No Gradle types, so it
 * runs in a JVM unit test.
 *
 * An identifier is either `group:name` for an external module or a project path such as
 * `:core:testing`. Two verdicts, deliberately worded differently because they mean different
 * things to the reader:
 *
 * - `DENIED <id>`: the module matches the denylist. It is something this product must never ship.
 * - `NOT ALLOWLISTED <id>`: the module matches neither list. It is not known to be bad; nobody has
 *   reviewed it yet. This is the stronger half of the policy: any new group fails until a human
 *   adds it, which is what forces the AGENTS.md section 2.4 dependency review.
 *
 * An allowlist entry without a colon is a group prefix and matches the group itself and every
 * subgroup (`androidx` matches `androidx.core`, not `androidxfoo`). An entry with a colon is an
 * exact `group:name` and is used where a whole group would be wider than what the build carries.
 *
 * This is a blocklist plus a reviewed allowlist, not a proof of no egress: it cannot see a URL
 * built at runtime, reflection, a WebView, a native library, or an allowlisted library that itself
 * phones home. `UrlHostScan` narrows the gap further and does not close it either.
 */
object DependencyPolicy {

    /**
     * Group prefixes of known analytics, crash-reporting, APM, attribution and remote-logging SDKs.
     * Product invariant (PROJECT.md, AGENTS.md section 2.2): none of these ships in any variant.
     * Never trim this list to make a build pass.
     */
    val DENIED: List<String> = listOf(
        "com.google.firebase",
        "com.google.android.gms",
        "com.google.android.datatransport",
        "io.sentry",
        "com.bugsnag",
        "com.mixpanel",
        "com.amplitude",
        "com.segment",
        "com.microsoft.appcenter",
        "com.datadoghq",
        "com.newrelic",
        "io.opentelemetry",
        "com.appsflyer",
        "com.adjust",
        "com.facebook.android",
        "com.onesignal",
        "org.matomo",
        "com.posthog",
        "com.instabug",
        "ch.acra",
        // AGENTS.md section 3: BouncyCastle is `testImplementation` scope only. It has no XML-DSig
        // API and costs 2.4 MB of dex after R8. It is legal on a test classpath and forbidden on a
        // shipping one, and this entry is what makes that a build failure rather than a review hope.
        "org.bouncycastle",
        // xades4j is the independent XAdES verifier used as a test oracle (NFR-05). It is LGPL and
        // drags Guava, Guice and JAXB with it; none of that may reach a device.
        "com.googlecode.xades4j",
        // Apache Santuario arrives transitively through xades4j on the test classpath. Adopting it
        // in the shipping runtime is a separate owner decision with measured sizes, not a drift.
        "org.apache.santuario",
        // Guice is xades4j's injector; it has no place in the shipping runtime.
        "com.google.inject",
        // JAXB (xades4j's binding layer) is absent from Android and heavy where it can be bundled.
        "jakarta.xml.bind",
        "org.glassfish.jaxb",
    )

    /**
     * What the `:app` shipping runtime classpaths carry today, and nothing speculative. The
     * View-based UI stack (appcompat, material) is still present and will be replaced by Compose;
     * the two single modules are the only things material and androidx pull in outside `androidx`,
     * `org.jetbrains` and `com.google.android.material`. Add a group here only as a deliberate,
     * reviewed dependency decision (AGENTS.md section 2.4).
     */
    val APP_ALLOWED: List<String> = listOf(
        "androidx",
        "org.jetbrains",
        "com.google.android.material",
        "com.google.errorprone:error_prone_annotations",
        "com.google.guava:listenablefuture",
    )

    /** Project dependencies `:app` may ship. */
    val APP_ALLOWED_PROJECTS: List<String> = listOf(":core:invoicing")

    /** `:core:testing` holds test helpers and must never reach a shipping classpath (D-13). */
    val APP_DENIED_PROJECTS: List<String> = listOf(":core:testing")

    /**
     * The plain JVM modules carry the Kotlin standard library and its single annotations
     * dependency, nothing else. This is what lets the boundary scanner restrict itself to each
     * module's own output: dependency scope is covered here instead.
     */
    val JVM_ALLOWED: List<String> = listOf(
        "org.jetbrains.kotlin:kotlin-stdlib",
        "org.jetbrains:annotations",
    )

    /**
     * `:core:testing` is the one module that may carry BouncyCastle: it mints the synthetic
     * certificates every signing test needs. Everything else in [JVM_ALLOWED] still applies.
     * The group prefix covers `bcpkix-jdk18on`, `bcprov-jdk18on` and `bcutil-jdk18on`.
     */
    val TESTING_ALLOWED: List<String> = JVM_ALLOWED + "org.bouncycastle"

    /**
     * [DENIED] minus BouncyCastle. Derived by filtering, never re-typed, so a future denylist entry
     * is inherited here automatically. Denial wins over allowance in [violations], so leaving
     * `org.bouncycastle` in this list would defeat [TESTING_ALLOWED].
     */
    val TESTING_DENIED: List<String> = DENIED.filter { it != "org.bouncycastle" }

    /** The allow/deny lists one module's `verifyDependencyPolicy` runs against. */
    data class Profile(
        val allowed: List<String>,
        val denied: List<String>,
        val allowedProjects: List<String>,
        val deniedProjects: List<String>,
    )

    /**
     * The policy profile for the plain JVM module at [projectPath]. `:core:testing` is the single
     * exception to the shared allowlist: it is a test-helper module whose output never reaches a
     * device, and [APP_DENIED_PROJECTS] already keeps it off `:app`. Every other path gets the
     * plain-JVM profile: the Kotlin standard library only, with no project dependencies allowed.
     */
    fun profileFor(projectPath: String): Profile = when (projectPath) {
        ":core:testing" -> Profile(TESTING_ALLOWED, TESTING_DENIED, emptyList(), emptyList())
        else -> Profile(JVM_ALLOWED, DENIED, emptyList(), emptyList())
    }

    /**
     * Violation lines for [identifiers], sorted and de-duplicated; empty when the graph is clean.
     * Denial wins over allowance, so a denied group nested under an allowed prefix still fails.
     */
    fun violations(
        identifiers: Collection<String>,
        allowed: List<String>,
        denied: List<String>,
        allowedProjects: List<String> = emptyList(),
        deniedProjects: List<String> = emptyList(),
    ): List<String> {
        val result = sortedSetOf<String>()
        for (id in identifiers) {
            val isProject = id.startsWith(":")
            val deniedList = if (isProject) deniedProjects else denied
            val allowedList = if (isProject) allowedProjects else allowed
            when {
                deniedList.any { matches(it, id, isProject) } -> result += "DENIED $id"
                allowedList.none { matches(it, id, isProject) } -> result += "NOT ALLOWLISTED $id"
            }
        }
        return result.toList()
    }

    private fun matches(entry: String, id: String, isProject: Boolean): Boolean {
        if (isProject) return entry == id
        if (entry.contains(':')) return entry == id
        val group = id.substringBefore(':')
        return group == entry || group.startsWith("$entry.")
    }
}
