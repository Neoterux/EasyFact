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

package com.neoterux.easyfact.invoicing.text

import com.neoterux.easyfact.invoicing.result.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * D-34: document text is normalised silently and refused only for the four typed reasons. Every
 * special character is written as a `\u` escape so the source stays reviewable.
 */
class TextSanitizerTest {

    private val field = "razonSocialComprador"

    private fun required(raw: String, maxLength: Int? = null): Outcome<String, SanitizerError> =
        TextSanitizer.required(field, raw, maxLength)

    private fun optional(raw: String?, maxLength: Int? = null): Outcome<String?, SanitizerError> =
        TextSanitizer.optional(field, raw, maxLength)

    // ---- silent normalisation -------------------------------------------------------------------

    @Test
    fun tabsAndLineBreaksBecomeSingleSpacesAndTheTextIsTrimmed() {
        assertEquals(Outcome.Ok("Juan Perez Lopez"), required("  Juan\tPerez\r\nLopez  "))
    }

    @Test
    fun repeatedSpacesCollapseToOne() {
        assertEquals(Outcome.Ok("a b c"), required("a    b \t\t c"))
    }

    @Test
    fun aDecomposedAccentIsComposedToNfc() {
        val decomposed = "Café"
        assertEquals(5, decomposed.length)
        assertEquals(Outcome.Ok("Café"), required(decomposed))
    }

    @Test
    fun aNoBreakSpaceFoldsToAPlainSpace() {
        assertEquals(Outcome.Ok("uno dos"), required("uno dos"))
    }

    @Test
    fun theLineSeparatorFoldsToAPlainSpace() {
        assertEquals(Outcome.Ok("uno dos"), required("uno dos"))
    }

    @Test
    fun anIdeographicSpaceFoldsToAPlainSpace() {
        assertEquals(Outcome.Ok("uno dos"), required("uno　dos"))
    }

    @Test
    fun leadingAndTrailingUnicodeSpacesAreTrimmed() {
        assertEquals(Outcome.Ok("uno"), required(" 　uno  "))
    }

    // ---- characters that pass unchanged ---------------------------------------------------------

    @Test
    fun accentsEnyeAmpersandQuotesAndLessThanPassUnchanged() {
        val text = "Ñandú & <Cía> \"S.A.\" it's"
        assertEquals(Outcome.Ok(text), required(text))
    }

    // ---- refusals -------------------------------------------------------------------------------

    @Test
    fun aBellCharacterIsAControlCharacter() {
        assertEquals(Outcome.Err(SanitizerError.ControlCharacter(field)), required("a\u0007b"))
    }

    @Test
    fun deleteIsAControlCharacter() {
        assertEquals(Outcome.Err(SanitizerError.ControlCharacter(field)), required("a\u007Fb"))
    }

    @Test
    fun nextLineIsAControlCharacter() {
        assertEquals(Outcome.Err(SanitizerError.ControlCharacter(field)), required("a\u0085b"))
    }

    @Test
    fun anEmojiIsAnUnsupportedCharacter() {
        assertEquals(Outcome.Err(SanitizerError.UnsupportedCharacter(field)), required("hola 😀"))
    }

    @Test
    fun aLoneHighSurrogateIsAnUnsupportedCharacter() {
        val loneHigh = Char(0xD83D).toString()
        assertEquals(Outcome.Err(SanitizerError.UnsupportedCharacter(field)), required("a${loneHigh}b"))
    }

    @Test
    fun aLoneLowSurrogateIsAnUnsupportedCharacter() {
        val loneLow = Char(0xDE00).toString()
        assertEquals(Outcome.Err(SanitizerError.UnsupportedCharacter(field)), required("a${loneLow}b"))
    }

    @Test
    fun whitespaceOnlyMandatoryTextIsEmpty() {
        assertEquals(Outcome.Err(SanitizerError.Empty(field)), required("   "))
    }

    @Test
    fun anEmptyMandatoryStringIsEmpty() {
        assertEquals(Outcome.Err(SanitizerError.Empty(field)), required(""))
    }

    @Test
    fun textThatCleansToNothingIsEmptyEvenWhenItHeldOnlyLineBreaks() {
        assertEquals(Outcome.Err(SanitizerError.Empty(field)), required("\r\n\t "))
    }

    // ---- optional text --------------------------------------------------------------------------

    @Test
    fun optionalTextThatCleansToEmptyIsAbsent() {
        assertEquals(Outcome.Ok(null), optional("  \n "))
    }

    @Test
    fun optionalNullIsAbsent() {
        assertEquals(Outcome.Ok(null), optional(null))
    }

    @Test
    fun optionalTextIsCleanedLikeRequiredText() {
        assertEquals(Outcome.Ok("uno dos"), optional(" uno\n dos "))
    }

    @Test
    fun optionalTextStillRefusesAControlCharacter() {
        assertEquals(Outcome.Err(SanitizerError.ControlCharacter(field)), optional("a\u0007"))
    }

    // ---- length ---------------------------------------------------------------------------------

    @Test
    fun aValueOverTheLimitIsTooLongWithBothLengths() {
        val text = "x".repeat(26)
        assertEquals(Outcome.Err(SanitizerError.TooLong(field, 25, 26)), required(text, maxLength = 25))
    }

    @Test
    fun aValueExactlyOnTheLimitPasses() {
        val text = "x".repeat(25)
        assertEquals(Outcome.Ok(text), required(text, maxLength = 25))
    }

    @Test
    fun theLimitAppliesToOptionalTextToo() {
        val text = "x".repeat(26)
        assertEquals(Outcome.Err(SanitizerError.TooLong(field, 25, 26)), optional(text, maxLength = 25))
    }

    @Test
    fun lengthIsMeasuredAfterNormalisation() {
        // Twenty-five decomposed e-acute pairs are 50 code units raw and 25 once composed.
        val raw = "é".repeat(25)
        assertEquals(50, raw.length)
        assertEquals(Outcome.Ok("é".repeat(25)), required(raw, maxLength = 25))
    }

    @Test
    fun lengthIsMeasuredAfterCollapsingAndTrimming() {
        val raw = "  " + "x".repeat(25) + "   "
        assertEquals(Outcome.Ok("x".repeat(25)), required(raw, maxLength = 25))
    }

    @Test
    fun withoutALimitAnyLengthPasses() {
        val text = "x".repeat(5000)
        assertEquals(Outcome.Ok(text), required(text, maxLength = null))
    }

    @Test
    fun theFirstProblemInScanOrderIsTheOneReported() {
        assertEquals(
            Outcome.Err(SanitizerError.ControlCharacter(field)),
            required("a\u0007😀"),
        )
        assertEquals(
            Outcome.Err(SanitizerError.UnsupportedCharacter(field)),
            required("a😀\u0007"),
        )
    }

    // ---- value-free errors ----------------------------------------------------------------------

    @Test
    fun noErrorVariantEchoesTheInputText() {
        val marker = "SyntheticMarkerQ"
        val errors =
            listOf(
                required("$marker\u0007"),
                required("$marker😀"),
                required("$marker${Char(0xD83D)}"),
                required(" \n \t"),
                required(marker.repeat(3), maxLength = 4),
                optional("$marker\u0007"),
            ).map { (it as Outcome.Err).error }

        assertEquals(
            listOf(
                SanitizerError.ControlCharacter::class,
                SanitizerError.UnsupportedCharacter::class,
                SanitizerError.UnsupportedCharacter::class,
                SanitizerError.Empty::class,
                SanitizerError.TooLong::class,
                SanitizerError.ControlCharacter::class,
            ),
            errors.map { it::class },
        )
        for (error in errors) {
            assertFalse("toString must not contain the input: $error", error.toString().contains(marker))
            assertEquals(field, error.field)
        }
    }
}
