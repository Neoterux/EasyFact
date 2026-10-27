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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Committed failing fixtures for the dependency-graph gate. Every identifier is synthetic and
 * inline, so these tests are the permanent proof that the gate can be seen red independent of the
 * real build.
 */
class DependencyPolicyTest {

    private fun appViolations(vararg ids: String) = DependencyPolicy.violations(
        identifiers = ids.toList(),
        allowed = DependencyPolicy.APP_ALLOWED,
        denied = DependencyPolicy.DENIED,
        allowedProjects = DependencyPolicy.APP_ALLOWED_PROJECTS,
        deniedProjects = DependencyPolicy.APP_DENIED_PROJECTS,
    )

    private fun testingViolations(vararg ids: String): List<String> {
        val profile = DependencyPolicy.profileFor(":core:testing")
        return DependencyPolicy.violations(
            identifiers = ids.toList(),
            allowed = profile.allowed,
            denied = profile.denied,
            allowedProjects = profile.allowedProjects,
            deniedProjects = profile.deniedProjects,
        )
    }

    private fun jvmViolations(vararg ids: String): List<String> {
        val profile = DependencyPolicy.profileFor(":core:invoicing")
        return DependencyPolicy.violations(
            identifiers = ids.toList(),
            allowed = profile.allowed,
            denied = profile.denied,
            allowedProjects = profile.allowedProjects,
            deniedProjects = profile.deniedProjects,
        )
    }

    @Test
    fun cleanShippingSetPasses() {
        val result = appViolations(
            "androidx.core:core",
            "androidx.lifecycle:lifecycle-runtime",
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "org.jetbrains:annotations",
            "com.google.android.material:material",
            "com.google.errorprone:error_prone_annotations",
            "com.google.guava:listenablefuture",
            ":core:invoicing",
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun denylistedAnalyticsGroupIsDeniedAndNamed() {
        val result = appViolations("androidx.core:core", "com.google.firebase:firebase-analytics")
        assertEquals(listOf("DENIED com.google.firebase:firebase-analytics"), result)
    }

    @Test
    fun transitiveMeasurementModulesOfADeniedGroupAreDeniedToo() {
        val result = appViolations(
            "com.google.android.gms:play-services-measurement",
            "com.google.android.gms:play-services-ads-identifier",
            "com.google.android.datatransport:transport-api",
        )
        assertEquals(3, result.size)
        assertTrue(result.all { it.startsWith("DENIED ") })
    }

    @Test
    fun unknownGroupIsNotAllowlistedRatherThanDenied() {
        val result = appViolations("com.example.imageloader:loader")
        assertEquals(listOf("NOT ALLOWLISTED com.example.imageloader:loader"), result)
    }

    @Test
    fun bouncyCastleIsDeniedOnAShippingClasspath() {
        val result = appViolations("org.bouncycastle:bcprov-jdk18on", "org.bouncycastle:bcpkix-jdk18on")
        assertEquals(
            listOf("DENIED org.bouncycastle:bcpkix-jdk18on", "DENIED org.bouncycastle:bcprov-jdk18on"),
            result,
        )
    }

    @Test
    fun coreTestingProjectDependencyIsDenied() {
        val result = appViolations(":core:invoicing", ":core:testing")
        assertEquals(listOf("DENIED :core:testing"), result)
    }

    @Test
    fun unlistedProjectDependencyIsNotAllowlisted() {
        val result = appViolations(":feature:unreviewed")
        assertEquals(listOf("NOT ALLOWLISTED :feature:unreviewed"), result)
    }

    @Test
    fun groupPrefixMatchesSubgroupsButNotLookalikes() {
        assertEquals(emptyList<String>(), appViolations("androidx.annotation:annotation-jvm"))
        assertEquals(
            listOf("NOT ALLOWLISTED androidxfoo:lib"),
            appViolations("androidxfoo:lib"),
        )
    }

    @Test
    fun wholeGroupIsNotAllowedWhereOnlyOneModuleIsListed() {
        // The build carries guava's listenablefuture shim only; full guava is a new decision.
        val result = appViolations("com.google.guava:guava")
        assertEquals(listOf("NOT ALLOWLISTED com.google.guava:guava"), result)
    }

    @Test
    fun denialWinsOverAllowance() {
        val result = DependencyPolicy.violations(
            identifiers = listOf("androidx.analytics:tracker"),
            allowed = listOf("androidx"),
            denied = listOf("androidx.analytics"),
        )
        assertEquals(listOf("DENIED androidx.analytics:tracker"), result)
    }

    @Test
    fun jvmModulesAllowTheKotlinStandardLibraryOnly() {
        fun jvm(vararg ids: String) =
            DependencyPolicy.violations(ids.toList(), DependencyPolicy.JVM_ALLOWED, DependencyPolicy.DENIED)
        assertEquals(emptyList<String>(), jvm("org.jetbrains.kotlin:kotlin-stdlib", "org.jetbrains:annotations"))
        assertEquals(
            listOf("NOT ALLOWLISTED org.jetbrains.kotlinx:kotlinx-coroutines-core"),
            jvm("org.jetbrains.kotlinx:kotlinx-coroutines-core"),
        )
        assertEquals(listOf("DENIED org.bouncycastle:bcprov-jdk18on"), jvm("org.bouncycastle:bcprov-jdk18on"))
    }

    @Test
    fun bouncyCastleIsAllowedOnTheTestingProfile() {
        val result = testingViolations(
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains:annotations",
            "org.bouncycastle:bcprov-jdk18on",
            "org.bouncycastle:bcpkix-jdk18on",
            "org.bouncycastle:bcutil-jdk18on",
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun bouncyCastleIsDeniedOnThePlainJvmProfile() {
        val result = jvmViolations("org.bouncycastle:bcprov-jdk18on")
        assertEquals(listOf("DENIED org.bouncycastle:bcprov-jdk18on"), result)
    }

    @Test
    fun bouncyCastleIsDeniedOnTheAppProfile() {
        val result = appViolations("org.bouncycastle:bcpkix-jdk18on")
        assertEquals(listOf("DENIED org.bouncycastle:bcpkix-jdk18on"), result)
    }

    @Test
    fun testingProfileStillRejectsUnreviewedGroups() {
        val result = testingViolations("com.example.imageloader:loader")
        assertEquals(listOf("NOT ALLOWLISTED com.example.imageloader:loader"), result)
    }

    @Test
    fun testingProfileStillDeniesTelemetry() {
        val result = testingViolations("com.google.firebase:firebase-analytics")
        assertEquals(listOf("DENIED com.google.firebase:firebase-analytics"), result)
    }

    @Test
    fun xades4jIsDeniedOnEveryProfile() {
        val id = "com.googlecode.xades4j:xades4j"
        val expected = listOf("DENIED $id")
        assertEquals(expected, appViolations(id))
        assertEquals(expected, jvmViolations(id))
        assertEquals(expected, testingViolations(id))
    }

    @Test
    fun santuarioAndItsTransitivesAreDeniedOnEveryProfile() {
        val ids = arrayOf(
            "org.apache.santuario:xmlsec",
            "com.google.inject:guice",
            "jakarta.xml.bind:jakarta.xml.bind-api",
            "org.glassfish.jaxb:jaxb-runtime",
        )
        val expected = ids.map { "DENIED $it" }.sorted()
        assertEquals(expected, appViolations(*ids))
        assertEquals(expected, jvmViolations(*ids))
        assertEquals(expected, testingViolations(*ids))
    }

    /**
     * Every module `com.googlecode.xades4j:xades4j:2.4.1` actually puts on
     * `:core:invoicing`'s `testRuntimeClasspath`, as resolved by
     * `./gradlew :core:invoicing:dependencies --configuration testRuntimeClasspath` on
     * 2026-10-27. Versions are left out because the policy reads `group:name` only.
     *
     * No gate inspects `testRuntimeClasspath` — `verifyDependencyPolicy` deliberately scans the
     * runtime classpath alone — so this list is the written-down record of what the verification
     * dependency drags in, and the fixture the assertions below run against.
     */
    private val measuredXades4jTestClasspath = arrayOf(
        "com.googlecode.xades4j:xades4j",
        "com.google.guava:guava",
        "com.google.guava:failureaccess",
        "com.google.guava:listenablefuture",
        "com.google.code.findbugs:jsr305",
        "org.checkerframework:checker-qual",
        "com.google.errorprone:error_prone_annotations",
        "com.google.j2objc:j2objc-annotations",
        "com.google.inject:guice",
        "jakarta.inject:jakarta.inject-api",
        "aopalliance:aopalliance",
        "org.apache.santuario:xmlsec",
        "commons-codec:commons-codec",
        "com.fasterxml.woodstox:woodstox-core",
        "org.codehaus.woodstox:stax2-api",
        "org.bouncycastle:bcpkix-jdk18on",
        "org.bouncycastle:bcprov-jdk18on",
        "org.bouncycastle:bcutil-jdk18on",
        "jakarta.xml.bind:jakarta.xml.bind-api",
        "jakarta.activation:jakarta.activation-api",
        "org.glassfish.jaxb:jaxb-runtime",
        "org.glassfish.jaxb:jaxb-core",
        "org.glassfish.jaxb:txw2",
        "org.eclipse.angus:angus-activation",
        "com.sun.istack:istack-commons-runtime",
    )

    @Test
    fun noMeasuredXades4jModuleIsAllowedOnThePlainJvmProfile() {
        // `:core:invoicing` is the module that declares xades4j. Nothing it drags in may appear
        // on that module's own runtime classpath, so every measured module must be a violation.
        val result = jvmViolations(*measuredXades4jTestClasspath)
        assertEquals(measuredXades4jTestClasspath.size, result.size)
    }

    @Test
    fun theOnlyMeasuredXades4jModulesTheAppProfileAllowsAreTheTwoAlreadyReviewedShims() {
        val violating = appViolations(*measuredXades4jTestClasspath)
            // A violation line is `DENIED <id>` or `NOT ALLOWLISTED <id>`; the id is the last field.
            .map { it.substringAfterLast(' ') }
            .toSet()
        val allowed = measuredXades4jTestClasspath.toSet() - violating
        // Both are already on the shipping classpath through androidx and material; neither
        // arrives because of xades4j, and neither carries code.
        assertEquals(
            setOf("com.google.errorprone:error_prone_annotations", "com.google.guava:listenablefuture"),
            allowed,
        )
    }

    @Test
    fun theHeavyweightXades4jModulesAreDeniedByNameAndNotMerelyUnreviewed() {
        val heavyweight = arrayOf(
            "com.googlecode.xades4j:xades4j",
            "org.apache.santuario:xmlsec",
            "com.google.inject:guice",
            "jakarta.xml.bind:jakarta.xml.bind-api",
            "org.glassfish.jaxb:jaxb-runtime",
            "org.glassfish.jaxb:jaxb-core",
            "org.glassfish.jaxb:txw2",
        )
        val expected = heavyweight.map { "DENIED $it" }.sorted()
        assertEquals(expected, appViolations(*heavyweight))
        assertEquals(expected, jvmViolations(*heavyweight))
        // The testing profile relaxes BouncyCastle and nothing else.
        assertEquals(expected, testingViolations(*heavyweight))
        assertEquals(
            listOf("DENIED org.bouncycastle:bcprov-jdk18on"),
            jvmViolations("org.bouncycastle:bcprov-jdk18on"),
        )
        assertEquals(emptyList<String>(), testingViolations("org.bouncycastle:bcprov-jdk18on"))
    }

    @Test
    fun testingDeniedIsDerivedFromDeniedMinusBouncyCastle() {
        assertEquals(
            DependencyPolicy.DENIED.filter { it != "org.bouncycastle" },
            DependencyPolicy.TESTING_DENIED,
        )
        assertTrue("org.bouncycastle" !in DependencyPolicy.TESTING_DENIED)
        // Every other denied group is inherited, so a future entry cannot be skipped here.
        assertTrue(DependencyPolicy.TESTING_DENIED.containsAll(DependencyPolicy.DENIED - "org.bouncycastle"))
        assertEquals(DependencyPolicy.DENIED.size - 1, DependencyPolicy.TESTING_DENIED.size)
    }

    @Test
    fun onlyCoreTestingGetsTheTestingProfile() {
        val testing = DependencyPolicy.profileFor(":core:testing")
        assertEquals(DependencyPolicy.TESTING_ALLOWED, testing.allowed)
        assertEquals(DependencyPolicy.TESTING_DENIED, testing.denied)
        for (path in listOf(":core:invoicing", ":app", ":core:other", ":testing")) {
            val profile = DependencyPolicy.profileFor(path)
            assertEquals("allowed for $path", DependencyPolicy.JVM_ALLOWED, profile.allowed)
            assertEquals("denied for $path", DependencyPolicy.DENIED, profile.denied)
            assertTrue("bouncycastle denied for $path", "org.bouncycastle" in profile.denied)
        }
    }
}
