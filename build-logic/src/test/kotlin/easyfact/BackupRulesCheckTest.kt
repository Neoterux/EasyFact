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
 * Committed failing fixtures for the backup-rule gate. All XML is synthetic and inline, so these
 * tests are the permanent proof that the gate can be seen red independent of the real rule files.
 */
class BackupRulesCheckTest {

    private fun excludes(domains: List<String>) =
        domains.joinToString("\n") { """<exclude domain="$it" path="." />""" }

    private fun extractionRules(
        cloud: List<String> = BackupRulesCheck.EXTRACTION_DOMAINS,
        transfer: List<String> = BackupRulesCheck.EXTRACTION_DOMAINS,
        extra: String = "",
    ) = """
        <data-extraction-rules>
            <cloud-backup>
                ${excludes(cloud)}
                $extra
            </cloud-backup>
            <device-transfer>
                ${excludes(transfer)}
            </device-transfer>
        </data-extraction-rules>
    """.trimIndent().trim()

    private fun legacyRules(domains: List<String> = BackupRulesCheck.LEGACY_DOMAINS) = """
        <full-backup-content>
            ${excludes(domains)}
        </full-backup-content>
    """.trimIndent().trim()

    private fun manifest(attributes: String) = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.synthetic">
            <application $attributes />
        </manifest>
    """.trimIndent().trim()

    private val fullManifest = manifest(
        """android:allowBackup="true" android:dataExtractionRules="@xml/data_extraction_rules" android:fullBackupContent="@xml/backup_rules"""",
    )

    private fun extraction(text: String) =
        BackupRulesCheck.ruleFileViolations(text, "fixture.xml", BackupRulesCheck.EXTRACTION_ROOT)

    @Test
    fun defaultDenyExtractionRulesPass() {
        assertEquals(emptyList<String>(), extraction(extractionRules()))
    }

    @Test
    fun defaultDenyLegacyRulesPass() {
        val hits = BackupRulesCheck.ruleFileViolations(legacyRules(), "fixture.xml", BackupRulesCheck.LEGACY_ROOT)

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun oneMissingCloudBackupDomainIsNamedExactly() {
        val hits = extraction(extractionRules(cloud = BackupRulesCheck.EXTRACTION_DOMAINS - "database"))

        assertEquals(listOf("fixture.xml: <cloud-backup> does not exclude domains: [database]"), hits)
    }

    @Test
    fun oneMissingDeviceTransferDomainIsNamedExactly() {
        val hits = extraction(extractionRules(transfer = BackupRulesCheck.EXTRACTION_DOMAINS - "device_file"))

        assertEquals(listOf("fixture.xml: <device-transfer> does not exclude domains: [device_file]"), hits)
    }

    @Test
    fun aMissingDeviceTransferBlockExcludesNothing() {
        val text = """
            <data-extraction-rules>
                <cloud-backup>
                    ${excludes(BackupRulesCheck.EXTRACTION_DOMAINS)}
                </cloud-backup>
            </data-extraction-rules>
        """.trimIndent()

        val hits = extraction(text)

        assertEquals(1, hits.size)
        assertTrue(hits.single(), hits.single().contains("<device-transfer> does not exclude domains:"))
    }

    @Test
    fun aSingleIncludeFailsEvenWhenEveryExcludeIsPresent() {
        val hits = extraction(extractionRules(extra = """<include domain="file" path="certs" />"""))

        assertEquals(1, hits.size)
        assertTrue(hits.single(), hits.single().contains("<include>"))
    }

    @Test
    fun anExcludeThatIsNotWholeDomainDoesNotCount() {
        val text = extractionRules().replace(
            """<exclude domain="file" path="." />""",
            """<exclude domain="file" path="datastore" />""",
        )

        val hits = extraction(text)

        assertTrue(hits.toString(), hits.all { it.contains("does not exclude domains: [file]") })
        assertEquals(2, hits.size)
    }

    @Test
    fun aDevicePrefixedDomainDoesNotSatisfyALegacyDomain() {
        val hits = BackupRulesCheck.ruleFileViolations(
            legacyRules(BackupRulesCheck.LEGACY_DOMAINS - "root" + "device_root"),
            "fixture.xml",
            BackupRulesCheck.LEGACY_ROOT,
        )

        assertEquals(listOf("fixture.xml: <full-backup-content> does not exclude domains: [root]"), hits)
    }

    @Test
    fun theWrongRootElementIsRejected() {
        val hits = BackupRulesCheck.ruleFileViolations(legacyRules(), "fixture.xml", BackupRulesCheck.EXTRACTION_ROOT)

        assertEquals(listOf("fixture.xml: root element is <full-backup-content>, expected <data-extraction-rules>"), hits)
    }

    @Test
    fun aDoctypeDeclarationIsRefusedNotExpanded() {
        val text = """
            <?xml version="1.0"?>
            <!DOCTYPE data-extraction-rules [<!ENTITY x "y">]>
            <data-extraction-rules/>
        """.trimIndent()

        val hits = extraction(text)

        assertEquals(1, hits.size)
        assertTrue(hits.single(), hits.single().contains("DOCTYPE"))
    }

    @Test
    fun aManifestPointingAtBothRuleFilesWithTheExpectedLiteralPasses() {
        assertEquals(emptyList<String>(), BackupRulesCheck.manifestViolations(fullManifest, "true"))
        assertEquals("data_extraction_rules", BackupRulesCheck.ruleResourceName(fullManifest, "dataExtractionRules"))
        assertEquals("backup_rules", BackupRulesCheck.ruleResourceName(fullManifest, "fullBackupContent"))
    }

    @Test
    fun aManifestWithoutDataExtractionRulesFails() {
        val text = manifest("""android:allowBackup="true" android:fullBackupContent="@xml/backup_rules"""")

        val hits = BackupRulesCheck.manifestViolations(text, "true")

        assertEquals(listOf("manifest does not set android:dataExtractionRules to an @xml/ rule file"), hits)
    }

    @Test
    fun aManifestWithAnUnexpectedAllowBackupFails() {
        val text = manifest(
            """android:allowBackup="false" android:dataExtractionRules="@xml/a" android:fullBackupContent="@xml/b"""",
        )

        val hits = BackupRulesCheck.manifestViolations(text, "true")

        assertEquals(listOf("android:allowBackup is 'false', expected 'true'"), hits)
    }
}
