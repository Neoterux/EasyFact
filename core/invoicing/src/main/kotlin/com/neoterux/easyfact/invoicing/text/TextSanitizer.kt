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
import java.text.Normalizer

/**
 * Why a piece of document text was refused (D-34).
 *
 * Every variant names the [field] (the SRI element the caller passed) and, for [TooLong], the two
 * lengths. None carries the text itself, because errors reach diagnostics and the text is customer
 * data (NFR-04).
 */
sealed interface SanitizerError {

    /**
     * What the refused text was destined for: an SRI element such as `razonSocialComprador`, or, where one
     * element holds several texts, a name that tells them apart (`Email`, `nombre[2]`).
     */
    val field: String

    /**
     * The text holds a control character that survived space folding (BEL, DEL, NEL and the like), or an
     * invisible format character (zero-width space, bidirectional override, byte-order mark and the like).
     */
    data class ControlCharacter(override val field: String) : SanitizerError

    /**
     * The text holds a character that cannot be written to a document: a surrogate (any code point outside
     * the BMP, an emoji, or a lone surrogate) or a private-use code point, which includes the preview marker
     * of an unassigned sequential.
     */
    data class UnsupportedCharacter(override val field: String) : SanitizerError

    /** A mandatory text is empty once cleaned. */
    data class Empty(override val field: String) : SanitizerError

    /** The cleaned text is [actualLength] UTF-16 code units, more than the facet's [maxLength]. */
    data class TooLong(override val field: String, val maxLength: Int, val actualLength: Int) : SanitizerError
}

/**
 * Cleans free text before it becomes document content: normalise silently, refuse the rest (D-34).
 *
 * The Ficha warns that a line break or stray spacing inside an alphanumeric field gets the document
 * rejected, so this runs before the mapping builds any such field. The caller's stored value is never
 * modified; only the returned copy is.
 *
 * Order of operations, which matters:
 * 1. Unicode NFC, so an accent typed as a base letter plus a combining mark becomes the single
 *    precomposed character and counts as one code unit.
 * 2. TAB, CR, LF and every `Character.isSpaceChar` character (no-break space, U+2028, U+3000 and the
 *    other Unicode space separators) become U+0020. Folding is preferred to refusing text the user
 *    typed with a no-break space (research assumption A9).
 * 3. Any ISO control character that is left, and any format character (general category Cf: zero-width
 *    space, bidirectional marks and overrides, byte-order mark, soft hyphen), is refused as
 *    [SanitizerError.ControlCharacter]. Format characters are invisible, so they would defeat the user's
 *    own length reasoning, and a bidirectional override can reorder a name on the printed document.
 * 4. Any surrogate is refused as [SanitizerError.UnsupportedCharacter]. This covers every non-BMP code
 *    point and every lone surrogate; the owner chose to refuse emoji outright rather than pass them. A
 *    private-use character is refused the same way: it has no portable meaning, and the preview marker of an
 *    unassigned sequential is one, so refusing the class guarantees user text never collides with it.
 * 5. Runs of spaces collapse to one and the ends are trimmed.
 * 6. Empty text is refused when mandatory and becomes absent when optional.
 * 7. The length is compared with `maxLength` in UTF-16 code units (D-13), which is how the schema
 *    validator counts, and it is measured on the cleaned text, not the raw input.
 *
 * Accents, enye, ampersand, quotes and `<` pass unchanged: the canonical writer escapes markup, and
 * accents travel as literal UTF-8 with no entity encoding (D-12). The writer's own `BlankField` and
 * `IllegalCharacter` checks remain as the backstop behind this one.
 */
object TextSanitizer {

    /**
     * Cleans mandatory text for [field]. [maxLength] is the facet maximum in UTF-16 code units, or
     * `null` when the element has none.
     */
    fun required(field: String, raw: String, maxLength: Int?): Outcome<String, SanitizerError> {
        val cleaned =
            when (val result = clean(field, raw)) {
                is Outcome.Err -> return result
                is Outcome.Ok -> result.value
            }
        if (cleaned.isEmpty()) return Outcome.Err(SanitizerError.Empty(field))
        return withinLimit(field, cleaned, maxLength)
    }

    /**
     * Cleans optional text for [field]. Absent input, and input that cleans to empty, both give
     * `Ok(null)`, so the mapping never emits a conditional element empty.
     */
    fun optional(field: String, raw: String?, maxLength: Int?): Outcome<String?, SanitizerError> {
        if (raw == null) return Outcome.Ok(null)
        val cleaned =
            when (val result = clean(field, raw)) {
                is Outcome.Err -> return result
                is Outcome.Ok -> result.value
            }
        if (cleaned.isEmpty()) return Outcome.Ok(null)
        return withinLimit(field, cleaned, maxLength)
    }

    private fun withinLimit(field: String, cleaned: String, maxLength: Int?): Outcome<String, SanitizerError> =
        if (maxLength != null && cleaned.length > maxLength) {
            Outcome.Err(SanitizerError.TooLong(field, maxLength, cleaned.length))
        } else {
            Outcome.Ok(cleaned)
        }

    /** Steps 1 to 5: normalise, fold spaces, refuse the unusable, collapse and trim. */
    private fun clean(field: String, raw: String): Outcome<String, SanitizerError> {
        val normalised = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val out = StringBuilder(normalised.length)
        var pendingSpace = false
        for (original in normalised) {
            val c = if (Character.isSpaceChar(original)) ' ' else original
            // TAB, CR and LF are ISO controls but are spacing, not content, so they fold first.
            val folded = if (c == '\t' || c == '\r' || c == '\n') ' ' else c
            if (Character.isISOControl(folded) || Character.getType(folded) == Character.FORMAT.toInt()) {
                return Outcome.Err(SanitizerError.ControlCharacter(field))
            }
            if (Character.isSurrogate(folded) || Character.getType(folded) == Character.PRIVATE_USE.toInt()) {
                return Outcome.Err(SanitizerError.UnsupportedCharacter(field))
            }
            if (folded == ' ') {
                pendingSpace = out.isNotEmpty()
            } else {
                if (pendingSpace) out.append(' ')
                pendingSpace = false
                out.append(folded)
            }
        }
        return Outcome.Ok(out.toString())
    }
}
