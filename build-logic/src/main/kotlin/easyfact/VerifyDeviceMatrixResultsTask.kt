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

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * Fails unless every declared managed device left result files showing it executed tests and none failed.
 *
 * The managed-device task's own exit code is not trusted: an emulator that never booted, an empty group,
 * or a runner that filtered every test out can all finish green. The verdict comes from the JUnit XML AGP
 * writes to `<resultsDir>/<device>/TEST-*.xml`, judged by [DeviceMatrixResults]. The run depends on the
 * group task, so the files read here are the ones that run produced.
 *
 * The task is untracked so it re-reads the results and prints the per-device counts on every invocation:
 * a verdict that could be skipped as up to date would certify an earlier run.
 */
@UntrackedTask(because = "Re-reads the device results and prints the per-device counts on every run")
abstract class VerifyDeviceMatrixResultsTask : DefaultTask() {

    /** `outputs/androidTest-results/managedDevice/<variant>`: one subdirectory per device. */
    @get:Internal
    abstract val resultsDir: DirectoryProperty

    /** The devices the matrix declares; each must have executed tests. */
    @get:Input
    abstract val expectedDevices: ListProperty<String>

    @TaskAction
    fun verify() {
        val devices = expectedDevices.get()
        val root = resultsDir.get().asFile
        val resultsByDevice = devices.associateWith { device ->
            File(root, device).walkTopDown()
                .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
                .map { it.readText() }
                .toList()
        }
        val problems = DeviceMatrixResults.problems(devices, resultsByDevice)
        for (device in devices) {
            logger.lifecycle(DeviceMatrixResults.describe(device, resultsByDevice[device].orEmpty()))
        }
        if (problems.isNotEmpty()) {
            throw GradleException("The device matrix did not pass:\n" + problems.joinToString("\n") { "  $it" })
        }
        logger.lifecycle("Device matrix passed on ${devices.size} devices: ${devices.joinToString()}")
    }
}
