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

import com.android.build.api.variant.BuiltArtifact
import com.android.build.api.variant.BuiltArtifacts
import com.android.build.api.variant.BuiltArtifactsLoader
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Behavioral proof that the release-dex host gate task fails closed. The APK is a real zip built in
 * the test, the dex bytes are synthetic (`example.com` hosts only), and the AGP listing types are
 * replaced by dynamic proxies so no Android build is needed.
 *
 * The task is driven through its public `verify()` action exactly as Gradle would run it.
 */
class VerifyReleaseUrlHostsTaskTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A listing whose loader resolves [apks] as the built artifacts; `null` simulates an unreadable listing. */
    private fun listingOf(apks: List<File>?): BuiltArtifacts? = apks?.let { files ->
        val elements: Collection<BuiltArtifact> = files.map { file ->
            stub<BuiltArtifact> { name, _ ->
                if (name == "getOutputFile") file.absolutePath else unexpected(name)
            }
        }
        stub<BuiltArtifacts> { name, _ ->
            if (name == "getElements") elements else unexpected(name)
        }
    }

    private fun taskWith(listing: BuiltArtifacts?, allowed: List<String> = UrlHostScan.ALLOWED_HOSTS): VerifyReleaseUrlHostsTask {
        val project = ProjectBuilder.builder().withProjectDir(tmp.newFolder("project")).build()
        val task = project.tasks.register("verifyReleaseUrlHosts", VerifyReleaseUrlHostsTask::class.java).get()
        task.apkDir.set(tmp.newFolder("apk-listing"))
        task.allowedHosts.set(allowed)
        task.builtArtifactsLoader.set(stub<BuiltArtifactsLoader> { name, _ ->
            if (name == "load") listing else unexpected(name)
        })
        return task
    }

    private fun apk(name: String, entries: Map<String, String>): File {
        val file = tmp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, text) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(text.toByteArray(Charsets.ISO_8859_1))
                zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun apkWithNoDexEntryFailsClosedInsteadOfPassingEmpty() {
        val apk = apk(
            "app-release.apk",
            mapOf("AndroidManifest.xml" to "<manifest/>", "resources.arsc" to "x"),
        )
        val error = assertThrows(GradleException::class.java) {
            taskWith(listingOf(listOf(apk))).verify()
        }
        assertTrue("unexpected message: ${error.message}", error.message!!.contains("nothing was scanned"))
    }

    @Test
    fun dexWithOnlyAllowlistedHostPasses() {
        val apk = apk("app-release.apk", mapOf("classes.dex" to "see https://developer.android.com/reference/x"))
        taskWith(listingOf(listOf(apk))).verify()
    }

    @Test
    fun dexWithNoUrlHostAtAllPasses() {
        val apk = apk("app-release.apk", mapOf("classes.dex" to "plain constant, no endpoint here"))
        taskWith(listingOf(listOf(apk))).verify()
    }

    @Test
    fun dexWithNonAllowlistedTelemetryHostFails() {
        val apk = apk(
            "app-release.apk",
            mapOf("classes.dex" to "https://telemetry.example.com/v1/collect"),
        )
        val error = assertThrows(GradleException::class.java) {
            taskWith(listingOf(listOf(apk))).verify()
        }
        val message = error.message!!
        assertTrue("unexpected message: $message", message.contains("UNEXPECTED HOST telemetry.example.com"))
        assertTrue("unexpected message: $message", message.contains("non-allowlisted"))
    }

    @Test
    fun violationInSecondaryDexIsNotMissed() {
        val apk = apk(
            "app-release.apk",
            mapOf(
                "classes.dex" to "https://developer.android.com/ok",
                "classes2.dex" to "https://telemetry.example.com/v1/collect",
            ),
        )
        assertThrows(GradleException::class.java) {
            taskWith(listingOf(listOf(apk))).verify()
        }
    }

    @Test
    fun hostAddedToTaskAllowlistIsAccepted() {
        val apk = apk("app-release.apk", mapOf("classes.dex" to "https://telemetry.example.com/v1/collect"))
        taskWith(listingOf(listOf(apk)), allowed = listOf("telemetry.example.com")).verify()
    }

    @Test
    fun listingNamingNoApkFails() {
        val error = assertThrows(GradleException::class.java) {
            taskWith(listingOf(emptyList())).verify()
        }
        assertTrue("unexpected message: ${error.message}", error.message!!.contains("names no APK"))
    }

    @Test
    fun unreadableListingFails() {
        val error = assertThrows(GradleException::class.java) {
            taskWith(listingOf(null)).verify()
        }
        assertTrue("unexpected message: ${error.message}", error.message!!.contains("Could not read the APK listing"))
    }

    private companion object {
        /** Builds a dynamic proxy for an AGP interface; only the methods the task calls are answered. */
        inline fun <reified T : Any> stub(crossinline answer: (String, Array<out Any?>?) -> Any?): T =
            Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
                answer(method.name, args)
            } as T

        fun unexpected(name: String): Nothing = throw UnsupportedOperationException("fake does not implement $name")
    }
}
