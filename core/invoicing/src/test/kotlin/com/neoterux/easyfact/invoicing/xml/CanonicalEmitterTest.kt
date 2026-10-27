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

package com.neoterux.easyfact.invoicing.xml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the emitter writes, byte for byte. That the bytes are canonical is proven by `C14nIdentityTest`. */
class CanonicalEmitterTest {

    private fun root(build: CanonicalEmitter.() -> Unit): String {
        val emitter = CanonicalEmitter()
        emitter.build()
        return String(emitter.document(prolog = false).root, Charsets.UTF_8)
    }

    @Test
    fun elementsAreCompactAndAlwaysHaveAnExplicitEndTag() {
        val xml = root {
            open("a").open("b").close().open("c").text("x").close().close()
        }
        assertEquals("<a><b></b><c>x</c></a>", xml)
    }

    @Test
    fun textIsEscapedPerInclusiveC14n() {
        val xml = root { open("a").text("1 & 2 < 3 > 0 \r end").close() }
        assertEquals("<a>1 &amp; 2 &lt; 3 &gt; 0 &#xD; end</a>", xml)
    }

    @Test
    fun attributeValuesAreEscapedPerInclusiveC14n() {
        val xml = root { open("a", mapOf("v" to "q\" & < \t \n \r >")).close() }
        assertEquals("<a v=\"q&quot; &amp; &lt; &#x9; &#xA; &#xD; >\"></a>", xml)
    }

    @Test
    fun attributesAreWrittenInSortedOrderWhateverTheInputOrder() {
        val xml = root { open("a", linkedMapOf("version" to "1.1.0", "id" to "comprobante", "nombre" to "n")).close() }
        assertEquals("<a id=\"comprobante\" nombre=\"n\" version=\"1.1.0\"></a>", xml)
    }

    @Test
    fun nonAsciiIsEmittedRawAndEncodedToUtf8Once() {
        val document = CanonicalEmitter().open("a").text("Ñandú").close().document(prolog = false)
        assertArrayEquals("<a>Ñandú</a>".toByteArray(Charsets.UTF_8), document.root)
    }

    @Test
    fun declarationIsFollowedByExactlyOneLineFeedAndIsNotPartOfTheRoot() {
        val document = CanonicalEmitter().open("a").close().document()
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n", String(document.prolog, Charsets.UTF_8))
        assertEquals("<a></a>", String(document.root, Charsets.UTF_8))
        assertArrayEquals(document.prolog + document.root, document.octets())
    }

    @Test
    fun prologIsEmptyWhenNotRequested() {
        assertEquals(0, CanonicalEmitter().open("a").close().document(prolog = false).prolog.size)
    }

    @Test
    fun accessorsReturnCopiesSoACallerCannotChangeTheBytes() {
        val document = CanonicalEmitter().open("a").close().document()
        document.root[0] = 0
        document.prolog[0] = 0
        assertEquals('<'.code.toByte(), document.root[0])
        assertEquals('<'.code.toByte(), document.prolog[0])
    }

    @Test
    fun charactersIllegalInXml10AreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CanonicalEmitter().open("a").text("a\u0000b") }
        assertThrows(IllegalArgumentException::class.java) { CanonicalEmitter().open("a").text("a\u000Bb") }
        assertThrows(IllegalArgumentException::class.java) { CanonicalEmitter().open("a").text("a￾b") }
        assertThrows(IllegalArgumentException::class.java) { CanonicalEmitter().open("a").text("lone \uD800 surrogate") }
        assertThrows(IllegalArgumentException::class.java) { CanonicalEmitter().open("a", mapOf("v" to "x\u0001")) }
    }

    @Test
    fun aSupplementaryCharacterIsLegalAndRoundTrips() {
        val emoji = String(Character.toChars(0x1F600))
        val document = CanonicalEmitter().open("a").text(emoji).close().document(prolog = false)
        assertEquals("<a>$emoji</a>", String(document.root, Charsets.UTF_8))
    }

    @Test
    fun namesThatWouldNeedNamespaceHandlingAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { CanonicalEmitter().open("ds:Signature") }
        assertThrows(IllegalArgumentException::class.java) { CanonicalEmitter().open("a", mapOf("xmlns:ds" to "x")) }
        assertThrows(IllegalArgumentException::class.java) { CanonicalEmitter().open("1a") }
    }

    @Test
    fun misuseIsReportedAsAnIllegalState() {
        assertThrows(IllegalStateException::class.java) { CanonicalEmitter().text("outside") }
        assertThrows(IllegalStateException::class.java) { CanonicalEmitter().close() }
        assertThrows(IllegalStateException::class.java) { CanonicalEmitter().open("a").document() }
        assertThrows(IllegalStateException::class.java) { CanonicalEmitter().document() }
        assertThrows(IllegalStateException::class.java) { CanonicalEmitter().open("a").close().open("b") }
    }

    @Test
    fun legalityHelperAgreesWithTheEmitter() {
        assertTrue(CanonicalEmitter.isLegalXmlText("plain Ñ text\twith\ttabs\nand lines"))
        assertEquals(false, CanonicalEmitter.isLegalXmlText("a\u0000b"))
    }
}
