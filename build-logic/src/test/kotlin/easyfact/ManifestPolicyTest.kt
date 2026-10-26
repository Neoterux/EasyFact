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
 * Committed failing fixtures for the merged-manifest gate. All XML is synthetic and inline, so
 * these tests are the permanent proof that the gate can be seen red independent of the real manifest.
 */
class ManifestPolicyTest {

    private val appId = "com.example.synthetic"

    private fun manifest(top: String = "", app: String = "") = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$appId">
            $top
            <application android:allowBackup="true">
                $app
            </application>
        </manifest>
    """.trimIndent().trim()

    private fun violations(
        manifestText: String,
        permissions: List<String> = emptyList(),
        exported: List<String> = emptyList(),
        services: List<String> = emptyList(),
    ) = ManifestPolicy.violations(manifestText, permissions, exported, services, appId)

    @Test
    fun bareManifestPassesAgainstEmptyAllowlists() {
        assertEquals(emptyList<String>(), violations(manifest("")))
    }

    @Test
    fun injectedPermissionIsReportedByName() {
        val result = violations(manifest(top = """<uses-permission android:name="android.permission.INTERNET" />"""))
        assertEquals(listOf("UNEXPECTED PERMISSION android.permission.INTERNET"), result)
    }

    @Test
    fun sdk23PermissionIsReportedToo() {
        val result = violations(manifest(top = """<uses-permission-sdk-23 android:name="android.permission.WAKE_LOCK" />"""))
        assertEquals(listOf("UNEXPECTED PERMISSION android.permission.WAKE_LOCK"), result)
    }

    @Test
    fun allowlistedPermissionPasses() {
        val result = violations(
            manifest(top = """<uses-permission android:name="android.permission.INTERNET" />"""),
            permissions = listOf("android.permission.INTERNET"),
        )
        assertEquals(emptyList<String>(), result)
    }

    @Test
    fun applicationIdTokenIsResolvedForPermissionsTheAppDeclares() {
        val body = """
            <permission android:name="$appId.GUARD" android:protectionLevel="signature" />
            <uses-permission android:name="$appId.GUARD" />
        """.trimIndent()
        assertEquals(emptyList<String>(), violations(manifest(top = body), permissions = listOf("{applicationId}.GUARD")))
        assertEquals(
            listOf("UNEXPECTED DECLARED PERMISSION $appId.GUARD", "UNEXPECTED PERMISSION $appId.GUARD"),
            violations(manifest(top = body)),
        )
    }

    @Test
    fun exportedActivityIsReported() {
        val result = violations(manifest(app = """<activity android:name="com.example.Main" android:exported="true" />"""))
        assertTrue("UNEXPECTED EXPORTED COMPONENT com.example.Main" in result)
    }

    @Test
    fun exportedReceiverAndProviderAreReportedButNonExportedOnesAreNot() {
        val body = """
            <receiver android:name="com.example.OpenReceiver" android:exported="true" />
            <provider android:name="com.example.OpenProvider" android:authorities="a" android:exported="true" />
            <provider android:name="com.example.PrivateProvider" android:authorities="b" android:exported="false" />
            <activity android:name="com.example.Hidden" android:exported="false" />
        """.trimIndent()
        assertEquals(
            listOf(
                "UNEXPECTED EXPORTED COMPONENT com.example.OpenProvider",
                "UNEXPECTED EXPORTED COMPONENT com.example.OpenReceiver",
            ),
            violations(manifest(app = body)),
        )
    }

    @Test
    fun allowlistedExportedComponentPasses() {
        val body = """<receiver android:name="com.example.Known" android:exported="true" />"""
        assertEquals(emptyList<String>(), violations(manifest(app = body), exported = listOf("com.example.Known")))
    }

    @Test
    fun injectedForegroundServiceIsReportedEvenWhenNotExported() {
        val body = """
            <service android:name="androidx.work.impl.foreground.SystemForegroundService"
                android:exported="false" android:foregroundServiceType="dataSync" />
        """.trimIndent()
        assertEquals(
            listOf("UNEXPECTED SERVICE androidx.work.impl.foreground.SystemForegroundService"),
            violations(manifest(app = body)),
        )
    }

    @Test
    fun exportedServiceIsReportedAsBothExportedAndService() {
        val body = """<service android:name="com.example.Svc" android:exported="true" />"""
        assertEquals(
            listOf("UNEXPECTED EXPORTED COMPONENT com.example.Svc", "UNEXPECTED SERVICE com.example.Svc"),
            violations(manifest(app = body)),
        )
    }

    @Test
    fun exportedActivityAliasIsReported() {
        val body = """<activity-alias android:name="com.example.Alias" android:exported="true" android:targetActivity="com.example.Main" />"""
        assertEquals(listOf("UNEXPECTED EXPORTED COMPONENT com.example.Alias"), violations(manifest(app = body)))
    }

    @Test
    fun doctypeDeclarationIsRefusedRatherThanExpanded() {
        val hostile = """<?xml version="1.0"?><!DOCTYPE manifest [<!ENTITY x "y">]><manifest />"""
        assertEquals(
            listOf("merged manifest is not well-formed XML or declares a DOCTYPE"),
            violations(hostile),
        )
    }
}
