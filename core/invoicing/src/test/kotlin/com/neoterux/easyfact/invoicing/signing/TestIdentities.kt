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

import com.neoterux.easyfact.testing.IssuerShape
import com.neoterux.easyfact.testing.SyntheticCertificates
import com.neoterux.easyfact.testing.SyntheticIdentity
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Identities for the signing tests, minted once per JVM because RSA key generation dominates the
 * run time, and a fixed signing time so no test depends on the wall clock.
 */
object TestIdentities {

    /** The signing time every test uses: a fixed instant at the SRI's UTC-05:00 offset. */
    val signingTime: OffsetDateTime = OffsetDateTime.of(2026, 10, 27, 12, 0, 0, 0, ZoneOffset.ofHours(-5))

    val fixedClock: () -> OffsetDateTime = { signingTime }

    private val validFrom = signingTime.toInstant().minus(30, ChronoUnit.DAYS)
    private val validUntil = signingTime.toInstant().plus(365, ChronoUnit.DAYS)

    val plain: SigningIdentity by lazy { mint(IssuerShape.PLAIN) }
    val organizationIdentifierUtf8: SigningIdentity by lazy { mint(IssuerShape.ORGANIZATION_IDENTIFIER_UTF8) }
    val organizationIdentifierPrintable: SigningIdentity by lazy { mint(IssuerShape.ORGANIZATION_IDENTIFIER_PRINTABLE) }
    val nonAscii: SigningIdentity by lazy { mint(IssuerShape.NON_ASCII) }

    /** A second, unrelated RSA-2048 identity: its key does not match [plain]'s certificate. */
    val other: SigningIdentity by lazy { mint(IssuerShape.PLAIN) }

    val rsa1024: SigningIdentity by lazy {
        SyntheticCertificates.rsa(1024, IssuerShape.PLAIN, validFrom, validUntil).toSigningIdentity()
    }

    val ecP256: SigningIdentity by lazy { SyntheticCertificates.ecP256(validFrom, validUntil).toSigningIdentity() }

    /** An RSA-2048 identity whose certificate expired a day before [signingTime]. */
    val expired: SigningIdentity by lazy {
        SyntheticCertificates.rsa2048(
            IssuerShape.PLAIN,
            signingTime.toInstant().minus(400, ChronoUnit.DAYS),
            signingTime.toInstant().minus(1, ChronoUnit.DAYS),
        ).toSigningIdentity()
    }

    private fun mint(shape: IssuerShape): SigningIdentity =
        SyntheticCertificates.rsa2048(shape, validFrom, validUntil).toSigningIdentity()

    private fun SyntheticIdentity.toSigningIdentity() = SigningIdentity(privateKey, certificate)
}
