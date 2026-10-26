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

import java.time.LocalDate
import java.time.ZoneOffset
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * The only sanctioned way the committed size baseline moves (D-08, NFR-02).
 *
 * It measures the same two artifacts as [CheckReleaseSizeTask] and rewrites the baseline file
 * only when the `reason` Gradle property is present and non-blank, appending a dated history line
 * with the reason and the previous values. The write goes through [SizeBaseline.writeAtomically],
 * so an interrupted run leaves either the old baseline or the new one.
 */
@UntrackedTask(because = "Rewrites the committed baseline file on demand; it must run every time it is invoked")
abstract class RecordReleaseSizeBaselineTask : ReleaseArtifactTask() {

    /** `app/size-baseline.properties`. Both read and written, so it is not a tracked input. */
    @get:Internal
    abstract val baselineFile: RegularFileProperty

    /** Supplied as `-Preason="..."`. Absent or blank makes the task refuse. */
    @get:Input
    @get:Optional
    abstract val reason: Property<String>

    @TaskAction
    fun record() {
        val stated = reason.orNull?.trim().orEmpty()
        if (stated.isEmpty()) {
            throw GradleException(
                "Refusing to rewrite the size baseline without a stated reason. " +
                    "Re-run with -Preason=\"<why the size changed>\".",
            )
        }
        val measured = measure()
        val file = baselineFile.get().asFile
        val existing = if (file.exists()) file.readText() else ""
        val previous = if (existing.isNotEmpty()) SizeBaseline.parse(existing) else null
        val body = SizeBaseline.render(
            existing = existing,
            updated = measured,
            previous = previous,
            reason = stated,
            date = LocalDate.now(ZoneOffset.UTC).toString(),
        )
        SizeBaseline.writeAtomically(file, body)
        println("Recorded baseline: apk.bytes=${measured.apkBytes} aab.bytes=${measured.aabBytes}")
    }
}
