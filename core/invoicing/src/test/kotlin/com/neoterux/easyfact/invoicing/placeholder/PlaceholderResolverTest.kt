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

package com.neoterux.easyfact.invoicing.placeholder

import com.neoterux.easyfact.invoicing.result.Computed
import com.neoterux.easyfact.invoicing.result.EngineWarning
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * D-35 to D-38: the token set resolves against the billing period and the customer, the `{SEQ}`
 * marker and its warning exist only in a preview, and unknown or malformed tokens stay literal with
 * one warning each. Expected month names are written out here, not read from the code under test.
 */
class PlaceholderResolverTest {

    private val october = BillingPeriod(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))

    private fun context(
        period: BillingPeriod = october,
        name: String = "Test Customer S.A.",
        id: String = "9999999999001",
    ) = PlaceholderContext(period, name, id)

    private val sequence = CommittedSequence("001", "002", 123L)

    private fun preview(pattern: String, context: PlaceholderContext = context()): Computed<String> =
        PlaceholderResolver.preview(pattern, context).let { Computed(it.value.text, it.warnings) }

    private fun resolve(pattern: String, context: PlaceholderContext = context()): Computed<String> =
        PlaceholderResolver.resolve(pattern, context, sequence)

    private fun unknown(token: String) = EngineWarning.UnrecognisedToken(token)

    // ---- date tokens ----------------------------------------------------------------------------

    @Test
    fun yearAndMonthTokensResolveAgainstThePeriodAndAnUnknownTokenStaysLiteral() {
        val result = preview("{PREFIX}-{YY}{MM}-X")
        assertEquals("{PREFIX}-2610-X", result.value)
        assertEquals(listOf<EngineWarning>(unknown("{PREFIX}")), result.warnings)
    }

    @Test
    fun theFourDigitYearAndTheMonthNameResolve() {
        assertEquals("OCTUBRE 2026", resolve("{MONTH_NAME} {YYYY}").value)
    }

    @Test
    fun theTitleCaseMonthNameResolves() {
        assertEquals("Octubre", resolve("{Month_name}").value)
    }

    @Test
    fun allTwelveMonthsResolveToTheirSpanishNamesInBothCases() {
        val upper =
            listOf(
                "ENERO", "FEBRERO", "MARZO", "ABRIL", "MAYO", "JUNIO",
                "JULIO", "AGOSTO", "SEPTIEMBRE", "OCTUBRE", "NOVIEMBRE", "DICIEMBRE",
            )
        val title =
            listOf(
                "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
                "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre",
            )
        for (month in 1..12) {
            val period = BillingPeriod(LocalDate.of(2026, month, 1), LocalDate.of(2026, month, 28))
            val result = resolve("{MONTH_NAME}|{Month_name}|{MM}", context(period))
            val number = month.toString().padStart(2, '0')
            assertEquals("${upper[month - 1]}|${title[month - 1]}|$number", result.value)
            assertEquals(emptyList<EngineWarning>(), result.warnings)
        }
    }

    @Test
    fun yearAndMonthArePaddedWithZeros() {
        val period = BillingPeriod(LocalDate.of(2005, 3, 1), LocalDate.of(2005, 3, 31))
        assertEquals("05|2005|03", resolve("{YY}|{YYYY}|{MM}", context(period)).value)
    }

    @Test
    fun dateTokensUseThePeriodStartNotItsEnd() {
        val period = BillingPeriod(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 31))
        assertEquals("2609 SEPTIEMBRE", resolve("{YY}{MM} {MONTH_NAME}", context(period)).value)
    }

    @Test
    fun thePeriodRangeUsesDayMonthYearWithZeroPadding() {
        val period = BillingPeriod(LocalDate.of(2026, 3, 5), LocalDate.of(2026, 10, 31))
        assertEquals("05/03/2026 al 31/10/2026", resolve("{PERIOD_START} al {PERIOD_END}", context(period)).value)
        assertEquals("01/10/2026 al 31/10/2026", resolve("{PERIOD_START} al {PERIOD_END}").value)
    }

    // ---- customer tokens ------------------------------------------------------------------------

    @Test
    fun customerTokensSubstituteTheContextValuesVerbatim() {
        val result = resolve("{CUSTOMER_NAME} / {CUSTOMER_ID}")
        assertEquals("Test Customer S.A. / 9999999999001", result.value)
        assertEquals(emptyList<EngineWarning>(), result.warnings)
    }

    @Test
    fun aSubstitutedValueIsNeverScannedAgain() {
        val result = resolve("{CUSTOMER_NAME}", context(name = "A {MM} {FOO} {SEQ} B"))
        assertEquals("A {MM} {FOO} {SEQ} B", result.value)
        assertEquals(emptyList<EngineWarning>(), result.warnings)
    }

    // ---- {SEQ} ----------------------------------------------------------------------------------

    @Test
    fun aPreviewOfSeqIsTheMarkerWithTheAssignedAtIssueNote() {
        val result = PlaceholderResolver.preview("{SEQ}", context())
        assertEquals(PreviewText("…"), result.value)
        assertEquals(listOf<EngineWarning>(EngineWarning.SequenceAssignedAtIssue), result.warnings)
    }

    @Test
    fun theMarkerConstantIsTheHorizontalEllipsis() {
        assertEquals("…", PlaceholderResolver.PENDING_SEQUENCE_MARKER)
    }

    @Test
    fun twoSeqTokensInAPreviewGiveOneNote() {
        val result = preview("{SEQ} {SEQ}")
        assertEquals("… …", result.value)
        assertEquals(listOf<EngineWarning>(EngineWarning.SequenceAssignedAtIssue), result.warnings)
    }

    @Test
    fun aPreviewWithoutSeqCarriesNoNote() {
        val result = preview("{YYYY}-{MM}")
        assertEquals(emptyList<EngineWarning>(), result.warnings)
    }

    @Test
    fun committedSeqIsTheThreeThreeNineDigitForm() {
        val result = resolve("{SEQ}")
        assertEquals("001-002-000000123", result.value)
        assertEquals(emptyList<EngineWarning>(), result.warnings)
    }

    @Test
    fun committedSeqPadsTheSequentialToNineDigits() {
        val first = PlaceholderResolver.resolve("{SEQ}", context(), CommittedSequence("010", "020", 1L))
        val last = PlaceholderResolver.resolve("{SEQ}", context(), CommittedSequence("010", "020", 999_999_999L))
        assertEquals("010-020-000000001", first.value)
        assertEquals("010-020-999999999", last.value)
    }

    @Test
    fun resolveNeverEmitsTheAssignedAtIssueNoteEvenWithSeveralSeqTokens() {
        val result = resolve("{SEQ} {SEQ}")
        assertEquals("001-002-000000123 001-002-000000123", result.value)
        assertFalse(result.warnings.contains(EngineWarning.SequenceAssignedAtIssue))
    }

    // ---- unknown and malformed tokens -----------------------------------------------------------

    @Test
    fun anUnknownTokenIsWarnedOncePerDistinctTokenInFirstOccurrenceOrder() {
        val result = resolve("{FOO} {FOO} {BAR}")
        assertEquals("{FOO} {FOO} {BAR}", result.value)
        assertEquals(listOf<EngineWarning>(unknown("{FOO}"), unknown("{BAR}")), result.warnings)
    }

    @Test
    fun anUnclosedTokenStaysLiteralWithAWarningNamingIt() {
        val result = resolve("{MM")
        assertEquals("{MM", result.value)
        assertEquals(listOf<EngineWarning>(unknown("{MM")), result.warnings)
    }

    @Test
    fun anUnclosedTokenBeforeAnotherTokenStopsAtTheNextOpeningBrace() {
        val result = resolve("{{MM}")
        assertEquals("{10", result.value)
        assertEquals(listOf<EngineWarning>(unknown("{")), result.warnings)
    }

    @Test
    fun aTokenWithTheWrongCaseIsUnknown() {
        val result = resolve("{month_name}")
        assertEquals("{month_name}", result.value)
        assertEquals(listOf<EngineWarning>(unknown("{month_name}")), result.warnings)
    }

    @Test
    fun anEmptyTokenIsUnknown() {
        val result = resolve("a{}b")
        assertEquals("a{}b", result.value)
        assertEquals(listOf<EngineWarning>(unknown("{}")), result.warnings)
    }

    @Test
    fun aStrayClosingBraceIsPlainText() {
        val result = resolve("a}b {MM}")
        assertEquals("a}b 10", result.value)
        assertEquals(emptyList<EngineWarning>(), result.warnings)
    }

    @Test
    fun unknownTokensAndSeqShareOneDistinctFirstOccurrenceList() {
        val result = PlaceholderResolver.preview("{FOO} {SEQ} {FOO} {SEQ}", context())
        assertEquals("{FOO} … {FOO} …", result.value.text)
        assertEquals(
            listOf<EngineWarning>(unknown("{FOO}"), EngineWarning.SequenceAssignedAtIssue),
            result.warnings,
        )
    }

    @Test
    fun theNoteComesInFirstOccurrenceOrderBeforeALaterUnknownToken() {
        val result = PlaceholderResolver.preview("{SEQ} {FOO}", context())
        assertEquals(
            listOf<EngineWarning>(EngineWarning.SequenceAssignedAtIssue, unknown("{FOO}")),
            result.warnings,
        )
    }

    @Test
    fun theAgentsExampleResolvesPrefixAndSuffixAsUnknownTokens() {
        val result = resolve("{PREFIX}-{YY}{MM}-{SUFFIX}")
        assertEquals("{PREFIX}-2610-{SUFFIX}", result.value)
        assertEquals(listOf<EngineWarning>(unknown("{PREFIX}"), unknown("{SUFFIX}")), result.warnings)
    }

    // ---- degenerate patterns --------------------------------------------------------------------

    @Test
    fun anEmptyPatternResolvesToNothingWithNoWarnings() {
        val result = resolve("")
        assertEquals("", result.value)
        assertEquals(emptyList<EngineWarning>(), result.warnings)
    }

    @Test
    fun aPatternWithoutBracesIsReturnedUnchanged() {
        val text = "Servicios profesionales de octubre"
        val result = resolve(text)
        assertEquals(text, result.value)
        assertEquals(emptyList<EngineWarning>(), result.warnings)
    }

    // ---- locale independence --------------------------------------------------------------------

    @Test
    fun theOutputIsIdenticalUnderATurkishDefaultLocale() {
        val september = BillingPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        val pattern = "{MONTH_NAME} {Month_name} {YY}{YYYY}{MM} {PERIOD_START} {PERIOD_END} {CUSTOMER_NAME} {SEQ} {FOO}"
        val ctx = context(september)
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.ENGLISH)
            val baseline = PlaceholderResolver.resolve(pattern, ctx, sequence)
            val baselinePreview = PlaceholderResolver.preview(pattern, ctx)

            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val turkish = PlaceholderResolver.resolve(pattern, ctx, sequence)
            val turkishPreview = PlaceholderResolver.preview(pattern, ctx)

            assertEquals(baseline, turkish)
            assertEquals(baselinePreview, turkishPreview)
            assertEquals(
                "SEPTIEMBRE Septiembre 26202609 01/09/2026 30/09/2026 Test Customer S.A. 001-002-000000123 {FOO}",
                turkish.value,
            )
        } finally {
            Locale.setDefault(original)
        }
    }

    // ---- value-free rendering -------------------------------------------------------------------

    @Test
    fun theContextAndThePreviewTextDoNotRenderCustomerData() {
        val ctx = context(name = "SyntheticMarkerQ", id = "SyntheticMarkerR")
        assertFalse(ctx.toString().contains("SyntheticMarker"))
        val preview = PlaceholderResolver.preview("{CUSTOMER_NAME}", ctx)
        assertEquals("SyntheticMarkerQ", preview.value.text)
        assertFalse(preview.value.toString().contains("SyntheticMarker"))
    }
}
