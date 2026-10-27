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

package com.neoterux.easyfact.invoicing.verify

import java.security.cert.X509Certificate
import xades4j.verification.XAdESForm

/**
 * The second independent oracle: xades4j judging bytes this project's signer produced.
 *
 * [Jsr105Verifier] proves the XML-DSig layer and cannot see the `QualifyingProperties` at all.
 * This verifier is the only check available here that the document is valid XAdES-**BES** rather
 * than merely a well-formed XML signature (NFR-05, D-21). It shares no code with the signer.
 *
 * Test source only. xades4j and its transitive Apache Santuario are denied on every shipping
 * classpath by `DependencyPolicy`; the dependency is declared `testImplementation`.
 *
 * **This is a structural check of the XAdES form, not a trust decision.** The SRI performs the
 * real trust evaluation, and what it says is observed by the `celcer` probe, not here.
 */
object Xades4jVerifier {

    /**
     * How xades4j judged a document. The four values are kept apart so that a test asserting
     * rejection cannot be satisfied by a crash: [INVALID] means xades4j rejected the signature,
     * while [ERROR] means something unrelated to signature validity went wrong.
     */
    enum class Verdict {
        /** xades4j verified the signature and resolved its form. */
        VALID,

        /** xades4j rejected the document: a digest, a signature value or the certificate path failed. */
        INVALID,

        /** The bytes are not a parseable XML document carrying exactly one `ds:Signature`. */
        MALFORMED,

        /** Anything else. A test that expects rejection must not accept this. */
        ERROR,
    }

    /**
     * What xades4j reported. [failure] is the simple class name of the exception xades4j raised,
     * never its message: a provider message can quote certificate detail (AGENTS.md section 2.2).
     */
    data class Report(
        val verdict: Verdict,
        val form: XAdESForm?,
        val references: Int,
        val signedDataObjects: Int,
        val failure: String?,
    )

    /** Verifies [octets] against [trustAnchor] as the single trusted certificate. */
    fun verify(octets: ByteArray, trustAnchor: X509Certificate): Report =
        TODO("Task 1 of plan 02-06")

    /** Whether [octets] verify. The boolean form the tamper matrix drives both verifiers through. */
    fun validate(octets: ByteArray, trustAnchor: X509Certificate): Boolean =
        verify(octets, trustAnchor).verdict == Verdict.VALID
}
