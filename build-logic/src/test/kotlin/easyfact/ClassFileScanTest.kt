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

package easyfact

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Committed proof that the portability gate can fail (a gate nobody has seen red proves nothing).
 *
 * Every case feeds the scanner real class bytes read from this test's own compiled output. The
 * two violating fixtures are private nested classes that exist only to be scanned.
 */
class ClassFileScanTest {

    private val forbidden = listOf(
        "javax/xml/crypto/",
        "javax/xml/stream/",
        "org/xmlpull/",
        "android/",
        "androidx/",
        "dalvik/",
        "libcore/",
    )

    @Test
    fun cleanClassHasNoViolations() {
        val bytes = classBytes("easyfact/ClassFileScan.class")

        assertEquals(emptyList<String>(), ClassFileScan.violations(bytes, forbidden))
    }

    @Test
    fun descriptorReferenceToForbiddenTypeIsReported() {
        val bytes = classBytes("easyfact/ClassFileScanTest\$DescriptorFixture.class")

        val hits = ClassFileScan.violations(bytes, forbidden)

        assertTrue("expected the StAX descriptor in $hits", hits.any { "javax/xml/stream/XMLInputFactory" in it })
    }

    @Test
    fun reflectionStringLiteralIsReported() {
        val bytes = classBytes("easyfact/ClassFileScanTest\$ReflectionFixture.class")

        val hits = ClassFileScan.violations(bytes, forbidden)

        assertTrue(
            "expected the dotted JSR-105 literal in $hits",
            hits.any { it == "javax.xml.crypto.dsig.XMLSignature" },
        )
    }

    private fun classBytes(resource: String): ByteArray {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
            "fixture class not found on the test classpath: $resource"
        }
        return stream.use { it.readBytes() }
    }

    /** Declares a field of a forbidden StAX type, so the constant pool holds its descriptor. */
    private class DescriptorFixture {
        @Suppress("unused")
        val factory: javax.xml.stream.XMLInputFactory? = null
    }

    /** Hands the dotted name of a JSR-105 class to reflection, which Animal Sniffer cannot see. */
    private class ReflectionFixture {
        @Suppress("unused")
        fun load(): Class<*> = Class.forName("javax.xml.crypto.dsig.XMLSignature")
    }
}
