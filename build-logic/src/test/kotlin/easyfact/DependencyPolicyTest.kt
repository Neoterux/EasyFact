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
}
