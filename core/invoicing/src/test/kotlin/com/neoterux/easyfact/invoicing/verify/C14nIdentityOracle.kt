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

import java.io.ByteArrayInputStream
import javax.crypto.spec.SecretKeySpec
import javax.xml.crypto.OctetStreamData
import javax.xml.crypto.dsig.CanonicalizationMethod
import javax.xml.crypto.dsig.XMLSignatureFactory
import javax.xml.crypto.dsig.dom.DOMValidateContext
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The JDK's own inclusive canonicalizer (`REC-xml-c14n-20010315`), exposed as bytes in, bytes out.
 *
 * The claim under test is the one in AGENTS.md section 6.2: the writer emits already-canonical
 * form, so canonicalization must be the **identity** transform on those bytes. That is the
 * testable assertion, not "the writer produces canonical output", which has no oracle.
 *
 * A declaration at the front of the input is dropped by canonicalization, so for a document with
 * a prolog the result is compared with the root-element bytes.
 *
 * Test source only: `javax.xml.crypto` is in the JDK and absent from Android.
 */
object C14nIdentityOracle {

    fun canonicalize(octets: ByteArray): ByteArray {
        val factory = XMLSignatureFactory.getInstance("DOM")
        val method = factory.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE, null as C14NMethodParameterSpec?)
        // `DOMCryptoContext` is abstract and `DOMValidateContext` is the cheapest concrete one. The key and
        // the node are never used: canonicalizing an octet stream reads neither.
        val context = DOMValidateContext(
            SecretKeySpec(byteArrayOf(1), "HMAC"),
            DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument().createElement("unused"),
        )
        val result = method.transform(OctetStreamData(ByteArrayInputStream(octets)), context) as OctetStreamData
        return result.octetStream.readAllBytes()
    }
}
