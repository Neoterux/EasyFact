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

package com.neoterux.easyfact.invoicing.result

import java.time.OffsetDateTime

/**
 * Why a document could not be signed.
 *
 * Each variant names one pipeline stage, so a user report of "it did not work" maps to a single
 * cause. These values are rendered into the on-device diagnostics log, so **no variant may carry
 * key material, a password or a full identification number**, and none does.
 */
sealed interface SigningError {

    /** The certificate's public key is not an RSA key of exactly 2048 bits, which the SRI requires. */
    data object KeyNotRsa2048 : SigningError

    /** The certificate's validity window does not include [signingTime]. */
    data class CertificateNotValidAtSigningTime(val signingTime: OffsetDateTime) : SigningError

    /** The certificate store could not be opened: the password was rejected or the file is corrupt. */
    data object KeystoreUnlockFailed : SigningError

    /** The private key is not the counterpart of the certificate's public key. */
    data object PrivateKeyDoesNotMatchCertificate : SigningError

    /** The document is not in the canonical form the signer relies on; [reason] names the precondition. */
    data class InputNotCanonical(val reason: String) : SigningError

    /** The platform crypto provider failed at the named [stage]; the cause is deliberately not carried. */
    data class SigningFailed(val stage: String) : SigningError
}
