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

package com.neoterux.easyfact.invoicing.signing

/**
 * Opting in to re-wrapping octets that were signed earlier.
 *
 * A caller that carries this annotation is stating three things:
 *
 * 1. the octets it supplies were produced by [Signer] on an earlier run and stored since;
 * 2. it is **not** producing a new signature — `docs/sri-electronic-invoicing.md` section 7 is
 *    explicit that a resend retransmits the stored signed bytes, because re-signing would change
 *    them and the SRI requires the same access key and sequential;
 * 3. it has the SHA-256 that was recorded when those octets were stored, and
 *    [SignedDocument.fromStoredOctets] will refuse the bytes if it does not match.
 *
 * The opt-in is `ERROR` level, so every call site is a compile-time decision and a `grep` for
 * this annotation lists every place in the codebase that re-admits bytes into the type the
 * submission path trusts. Kotlin's `internal` would not do: it is module-wide, and Phase 3's
 * codec lives in this same module, so it would protect nothing here.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "Re-wrapping stored signed octets. Opt in only where a resend transmits bytes " +
        "signed on an earlier run, and only with the digest recorded when they were stored.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class StoredOctetRehydration
