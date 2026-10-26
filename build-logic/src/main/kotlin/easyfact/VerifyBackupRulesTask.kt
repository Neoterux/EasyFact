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

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails the build when a variant's merged manifest or the backup rule files it references are
 * not default-deny, per [BackupRulesCheck].
 *
 * The merged manifest is read, not the source manifest: a variant overlay or a library can change
 * what the app actually ships. Each `@xml/<name>` reference is resolved against the variant's
 * resource directories, and every copy found there is checked, so an overlay that shadows a
 * default-deny file with a permissive one cannot hide behind the compliant copy. Nothing is
 * resolved from `Project` at execution time, so the task is configuration-cache safe.
 */
abstract class VerifyBackupRulesTask : DefaultTask() {

    /** The variant's merged manifest (`SingleArtifact.MERGED_MANIFEST`). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    /** The variant's resource directories (`variant.sources.res`), where `xml/<name>.xml` lives. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourceDirs: ConfigurableFileCollection

    /** The literal `android:allowBackup` must carry; `true` keeps the rules exercisable on a device. */
    @get:Input
    abstract val expectedAllowBackup: Property<String>

    @get:Input
    abstract val extractionDomains: ListProperty<String>

    @get:Input
    abstract val legacyDomains: ListProperty<String>

    @TaskAction
    fun verify() {
        val manifestText = mergedManifest.get().asFile.readText()
        val violations = ArrayList<String>(BackupRulesCheck.manifestViolations(manifestText, expectedAllowBackup.get()))

        val targets = listOf(
            Target("dataExtractionRules", BackupRulesCheck.EXTRACTION_ROOT),
            Target("fullBackupContent", BackupRulesCheck.LEGACY_ROOT),
        )
        for (target in targets) {
            val name = BackupRulesCheck.ruleResourceName(manifestText, target.attribute) ?: continue
            val copies = resourceDirs.files.map { File(it, "xml/$name.xml") }.filter { it.isFile }
            if (copies.isEmpty()) {
                violations += "@xml/$name (android:${target.attribute}) was not found in any resource directory of this variant"
            }
            for (copy in copies) {
                violations += BackupRulesCheck.ruleFileViolations(
                    ruleText = copy.readText(),
                    label = "${copy.parentFile.parentFile.parentFile.name}/xml/$name.xml",
                    rootName = target.rootName,
                    extractionDomains = extractionDomains.get(),
                    legacyDomains = legacyDomains.get(),
                )
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException("Backup rules are not default-deny:\n" + violations.joinToString("\n") { "  $it" })
        }
    }

    private class Target(val attribute: String, val rootName: String)
}
