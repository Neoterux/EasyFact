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

package com.neoterux.easyfact.invoicing.catalogue

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of the catalogue (SRI-13, D-01, D-05, D-09): the 36 codes of Ficha v2.34 section 11, the two
 * Unknown entries and the engine's own entries, all in one enum with a resource name each.
 */
class CatalogueEntryTest {

    private val ficha = setOf(
        2, 10, 26, 27, 28, 34, 35, 36, 37, 39, 40, 42, 43, 45, 46, 47, 48, 49, 50, 52, 56, 57, 58, 59, 60, 62, 63,
        64, 65, 67, 68, 69, 70, 80, 82, 92,
    )

    private val coded = CatalogueEntry.entries.filter { it.source == Source.SRI && it.sriCode != null }

    @Test
    fun sriEntriesCarryExactlyTheThirtySixCodesOfTheFicha() {
        assertEquals(36, coded.size)
        assertEquals(ficha, coded.map { it.sriCode }.toSet())
    }

    @Test
    fun everyCodedEntryIsNamedAfterItsCode() {
        for (entry in coded) {
            assertEquals("SRI_${entry.sriCode}", entry.name)
        }
    }

    @Test
    fun aCataloguedCodeIsFoundAndCode99IsNot() {
        assertEquals(CatalogueEntry.SRI_39, CatalogueEntry.forSriCode(39))
        assertNull(CatalogueEntry.forSriCode(99))
        assertNull(CatalogueEntry.forSriCode(0))
        assertNull(CatalogueEntry.forSriCode(-39))
        for (entry in coded) {
            assertEquals(entry, CatalogueEntry.forSriCode(entry.sriCode!!))
        }
    }

    @Test
    fun onlyCodes59And60And62And68AreWarningsAndTheyNeverAskForAction() {
        val warnings = coded.filter { it.category == Category.WARNING }
        assertEquals(setOf(59, 60, 62, 68), warnings.map { it.sriCode }.toSet())
        for (entry in warnings) {
            assertEquals(NextAction.NONE, entry.nextAction)
        }
    }

    @Test
    fun codes52And65AreCheckedAtTwoPlaces() {
        assertEquals(setOf(Stage.AUTHORIZATION, Stage.ISSUER_CHECK), CatalogueEntry.SRI_52.stages)
        assertEquals(setOf(Stage.RECEPTION, Stage.ISSUER_CHECK), CatalogueEntry.SRI_65.stages)
    }

    @Test
    fun theFourIssuerOnlyCodesAreCheckedOnlyByTheIssuerTable() {
        for (code in listOf(34, 42, 64, 69)) {
            assertEquals("code $code", setOf(Stage.ISSUER_CHECK), CatalogueEntry.forSriCode(code)!!.stages)
        }
    }

    @Test
    fun everyCodedEntryCitesItsFichaRowAndTheUnknownEntriesCiteNone() {
        for (entry in coded) {
            val reference = entry.fichaReference
            assertNotNull("code ${entry.sriCode}", reference)
            assertTrue("code ${entry.sriCode}: $reference", reference!!.startsWith("Ficha v2.34 section 11, p."))
        }
        assertNull(CatalogueEntry.SRI_UNKNOWN_ERROR.fichaReference)
        assertNull(CatalogueEntry.SRI_UNKNOWN_WARNING.fichaReference)
    }

    @Test
    fun theCitedPagesAreThoseOfTheFichaTables() {
        assertTrue(CatalogueEntry.SRI_2.fichaReference!!.contains("p.36"))
        assertTrue(CatalogueEntry.SRI_39.fichaReference!!.contains("p.37"))
        assertTrue(CatalogueEntry.SRI_80.fichaReference!!.contains("p.38"))
        assertTrue(CatalogueEntry.SRI_60.fichaReference!!.contains("p.38"))
        assertTrue(CatalogueEntry.SRI_34.fichaReference!!.contains("p.39"))
        assertTrue(CatalogueEntry.SRI_52.fichaReference!!.let { it.contains("p.37") && it.contains("p.39") })
        assertTrue(CatalogueEntry.SRI_65.fichaReference!!.let { it.contains("p.37") && it.contains("p.39") })
    }

    @Test
    fun resourceNamesAreUniqueAndWellFormed() {
        val names = CatalogueEntry.entries.map { it.resourceName }
        assertEquals(names.size, names.toSet().size)
        val shape = Regex("[a-z][a-z0-9_]*")
        for (name in names) {
            assertTrue(name, shape.matches(name))
        }
        assertEquals("catalogue_sri_39", CatalogueEntry.SRI_39.resourceName)
        assertEquals("catalogue_engine_bug", CatalogueEntry.ENGINE_BUG.resourceName)
    }

    @Test
    fun theTwoUnknownEntriesHaveNoCodeAndNoCitation() {
        assertEquals(Source.SRI, CatalogueEntry.SRI_UNKNOWN_ERROR.source)
        assertNull(CatalogueEntry.SRI_UNKNOWN_ERROR.sriCode)
        assertEquals(Category.UNKNOWN_OUTCOME, CatalogueEntry.SRI_UNKNOWN_ERROR.category)
        assertEquals(Source.SRI, CatalogueEntry.SRI_UNKNOWN_WARNING.source)
        assertNull(CatalogueEntry.SRI_UNKNOWN_WARNING.sriCode)
        assertEquals(Category.WARNING, CatalogueEntry.SRI_UNKNOWN_WARNING.category)
        assertEquals(NextAction.NONE, CatalogueEntry.SRI_UNKNOWN_WARNING.nextAction)
    }

    @Test
    fun theSequenceNoteIsAnInformationalEngineWarning() {
        val entry = CatalogueEntry.WARNING_SEQUENCE_ASSIGNED_AT_ISSUE
        assertEquals(Source.ENGINE, entry.source)
        assertEquals(Category.WARNING, entry.category)
        assertEquals(NextAction.NONE, entry.nextAction)
    }

    @Test
    fun theRefinementsOfThePlanOverrideTheResearchSeed() {
        val expected = mapOf(
            2 to (Category.SETUP to NextAction.FIX_IN_SRI_PORTAL),
            10 to (Category.SETUP to NextAction.FIX_IN_SRI_PORTAL),
            26 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            27 to (Category.SETUP to NextAction.CONTACT_SRI),
            28 to (Category.SETUP to NextAction.FIX_IN_SRI_PORTAL),
            34 to (Category.SETUP to NextAction.FIX_IN_SRI_PORTAL),
            35 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            36 to (Category.ENGINE_BUG to NextAction.UPDATE_APP),
            37 to (Category.SETUP to NextAction.FIX_IN_SRI_PORTAL),
            39 to (Category.SETUP to NextAction.FIX_AND_RESUBMIT),
            40 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            42 to (Category.SETUP to NextAction.RENEW_CERTIFICATE),
            43 to (Category.RECONCILE to NextAction.CHECK_STATUS),
            45 to (Category.SETUP to NextAction.REVIEW_SEQUENTIAL),
            46 to (Category.CORRECTABLE to NextAction.FIX_AND_RESUBMIT),
            47 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            48 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            49 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            50 to (Category.TRANSIENT to NextAction.WAIT),
            52 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            56 to (Category.SETUP to NextAction.FIX_IN_SRI_PORTAL),
            57 to (Category.SETUP to NextAction.CONTACT_SRI),
            58 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            59 to (Category.WARNING to NextAction.NONE),
            60 to (Category.WARNING to NextAction.NONE),
            62 to (Category.WARNING to NextAction.NONE),
            63 to (Category.SETUP to NextAction.CONTACT_SRI),
            64 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            65 to (Category.CORRECTABLE to NextAction.FIX_AND_RESUBMIT),
            67 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            68 to (Category.WARNING to NextAction.NONE),
            69 to (Category.CORRECTABLE to NextAction.FIX_AND_RESUBMIT),
            70 to (Category.WAIT to NextAction.WAIT),
            80 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            82 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
            92 to (Category.ENGINE_BUG to NextAction.EXPORT_DIAGNOSTICS),
        )
        assertEquals(ficha, expected.keys)
        for ((code, pair) in expected) {
            val entry = CatalogueEntry.forSriCode(code)!!
            assertEquals("category of $code", pair.first, entry.category)
            assertEquals("next action of $code", pair.second, entry.nextAction)
        }
    }

    @Test
    fun theSriStagesFollowTheValidationPointColumnOfTheFicha() {
        val reception = setOf(26, 28, 35, 36, 43, 45, 47, 48, 49, 50, 67, 70, 82, 92)
        val authorization = setOf(2, 10, 27, 37, 39, 40, 46, 56, 57, 58, 63, 80)
        for (code in reception) {
            assertEquals("code $code", setOf(Stage.RECEPTION), CatalogueEntry.forSriCode(code)!!.stages)
        }
        for (code in authorization) {
            assertEquals("code $code", setOf(Stage.AUTHORIZATION), CatalogueEntry.forSriCode(code)!!.stages)
        }
    }

    @Test
    fun theEngineEntriesFollowThePlan() {
        val fixInput = Category.CORRECTABLE to NextAction.FIX_INPUT
        val setupCertificate = Category.SETUP to NextAction.RENEW_CERTIFICATE
        val unknownOutcome = Category.UNKNOWN_OUTCOME to NextAction.CHECK_STATUS
        val warningConfirm = Category.WARNING to NextAction.CONFIRM_TO_CONTINUE
        val expected: Map<CatalogueEntry, Triple<Category, NextAction, Stage>> = mapOf(
            CatalogueEntry.ENGINE_BUG to Triple(Category.ENGINE_BUG, NextAction.EXPORT_DIAGNOSTICS, Stage.WRITING),
            CatalogueEntry.TEXT_CONTROL_CHARACTER to triple(fixInput, Stage.TEXT),
            CatalogueEntry.TEXT_UNSUPPORTED_CHARACTER to triple(fixInput, Stage.TEXT),
            CatalogueEntry.TEXT_EMPTY to triple(fixInput, Stage.TEXT),
            CatalogueEntry.TEXT_TOO_LONG to triple(fixInput, Stage.TEXT),
            CatalogueEntry.FIELD_INVALID_FORMAT to triple(fixInput, Stage.WRITING),
            CatalogueEntry.AMOUNT_OUT_OF_RANGE to triple(fixInput, Stage.WRITING),
            CatalogueEntry.NO_LINES to triple(fixInput, Stage.TOTALS),
            CatalogueEntry.NEGATIVE_QUANTITY to triple(fixInput, Stage.TOTALS),
            CatalogueEntry.NEGATIVE_UNIT_PRICE to triple(fixInput, Stage.TOTALS),
            CatalogueEntry.UNKNOWN_IVA_CODE to triple(fixInput, Stage.TOTALS),
            CatalogueEntry.IVA_CODE_NOT_COMPUTABLE to triple(fixInput, Stage.TOTALS),
            CatalogueEntry.CONSUMIDOR_FINAL_CAP_EXCEEDED to triple(fixInput, Stage.MAPPING),
            CatalogueEntry.CONSUMIDOR_FINAL_IDENTIFICATION_MISMATCH to triple(fixInput, Stage.MAPPING),
            CatalogueEntry.TOO_MANY_ADDITIONAL_FIELDS to triple(fixInput, Stage.MAPPING),
            CatalogueEntry.PROVIDER_RUC_MALFORMED to triple(fixInput, Stage.MAPPING),
            CatalogueEntry.WITHHOLDING_AGENT_INVALID to triple(fixInput, Stage.MAPPING),
            CatalogueEntry.PREVIEW_MARKER_IN_DOCUMENT to
                Triple(Category.ENGINE_BUG, NextAction.EXPORT_DIAGNOSTICS, Stage.MAPPING),
            CatalogueEntry.IDENTIFICATION_MALFORMED to triple(fixInput, Stage.IDENTIFICATION),
            CatalogueEntry.IDENTIFICATION_CHECK_DIGIT to triple(fixInput, Stage.IDENTIFICATION),
            CatalogueEntry.IDENTIFICATION_NOT_CONSUMIDOR_FINAL to triple(fixInput, Stage.IDENTIFICATION),
            CatalogueEntry.CERTIFICATE_KEY_UNSUPPORTED to triple(setupCertificate, Stage.SIGNING),
            CatalogueEntry.CERTIFICATE_NOT_VALID_NOW to triple(setupCertificate, Stage.SIGNING),
            CatalogueEntry.CERTIFICATE_KEY_MISMATCH to triple(setupCertificate, Stage.SIGNING),
            CatalogueEntry.CERTIFICATE_UNLOCK_FAILED to triple(fixInput, Stage.SIGNING),
            CatalogueEntry.RESPONSE_SOAP_FAULT to triple(unknownOutcome, Stage.RESPONSE),
            CatalogueEntry.RESPONSE_UNEXPECTED to triple(unknownOutcome, Stage.RESPONSE),
            CatalogueEntry.RESPONSE_MALFORMED to triple(unknownOutcome, Stage.RESPONSE),
            CatalogueEntry.WARNING_ZERO_QUANTITY to triple(warningConfirm, Stage.TOTALS),
            CatalogueEntry.WARNING_ZERO_LINE_TOTAL to triple(warningConfirm, Stage.TOTALS),
            CatalogueEntry.WARNING_ZERO_INVOICE_TOTAL to triple(warningConfirm, Stage.TOTALS),
            CatalogueEntry.WARNING_UNRECOGNISED_TOKEN to triple(warningConfirm, Stage.TEMPLATE),
            CatalogueEntry.WARNING_SEQUENCE_ASSIGNED_AT_ISSUE to
                Triple(Category.WARNING, NextAction.NONE, Stage.TEMPLATE),
        )
        val engine = CatalogueEntry.entries.filter { it.source == Source.ENGINE }.toSet()
        assertEquals("the engine entries of the plan, and no others", expected.keys, engine)
        for ((entry, shape) in expected) {
            assertNull(entry.name, entry.sriCode)
            assertNull(entry.name, entry.fichaReference)
            assertEquals("category of ${entry.name}", shape.first, entry.category)
            assertEquals("next action of ${entry.name}", shape.second, entry.nextAction)
            assertTrue("${entry.name} is raised at ${shape.third}", shape.third in entry.stages)
        }
    }

    @Test
    fun theSharedEntriesAreReachableFromEveryStageThatRaisesThem() {
        assertEquals(setOf(Stage.WRITING, Stage.KEY, Stage.SIGNING, Stage.STORAGE), CatalogueEntry.ENGINE_BUG.stages)
        for (entry in listOf(CatalogueEntry.TEXT_TOO_LONG, CatalogueEntry.TEXT_EMPTY, CatalogueEntry.TEXT_CONTROL_CHARACTER)) {
            assertEquals(entry.name, setOf(Stage.TEXT, Stage.WRITING), entry.stages)
        }
    }

    @Test
    fun everyEntryIsRaisedAtLeastSomewhere() {
        for (entry in CatalogueEntry.entries) {
            assertTrue(entry.name, entry.stages.isNotEmpty())
        }
    }

    @Test
    fun nextActionsAreDeclaredMostSpecificFirst() {
        assertEquals(
            listOf(
                NextAction.FIX_AND_RESUBMIT,
                NextAction.FIX_INPUT,
                NextAction.RENEW_CERTIFICATE,
                NextAction.FIX_IN_SRI_PORTAL,
                NextAction.REVIEW_SEQUENTIAL,
                NextAction.CHECK_STATUS,
                NextAction.WAIT,
                NextAction.CONTACT_SRI,
                NextAction.UPDATE_APP,
                NextAction.EXPORT_DIAGNOSTICS,
                NextAction.CONFIRM_TO_CONTINUE,
                NextAction.NONE,
            ),
            NextAction.entries,
        )
    }

    @Test
    fun noSpanishTextLivesInTheSource() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/neoterux/easyfact/invoicing/catalogue/CatalogueEntry.kt"),
        )
        val nonAscii = source.lines().withIndex().filter { (_, line) -> line.any { it.code > 127 } }
        assertTrue("non-ASCII text on lines ${nonAscii.map { it.index + 1 }}", nonAscii.isEmpty())
    }

    @Test
    fun noCodeLineNamesCode99() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/neoterux/easyfact/invoicing/catalogue/CatalogueEntry.kt"),
        )
        val code = source.lines().filterNot { val t = it.trimStart(); t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") }
        assertTrue(code.none { it.contains("SRI_99") })
    }

    private fun triple(pair: Pair<Category, NextAction>, stage: Stage) = Triple(pair.first, pair.second, stage)
}
