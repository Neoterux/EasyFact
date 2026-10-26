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

    @Test
    fun forbiddenTypeAfterAPrimitiveInADescriptorIsReported() {
        // The CR-01 regression. Every one of these is a real method descriptor in which the
        // forbidden type is not the first parameter, so its `L` follows a primitive code. The
        // original lookbehind rejected all of them and the gate reported clean.
        val descriptors = listOf(
            "(ILandroid/content/Context;)V",
            "(JLandroid/os/Bundle;)V",
            "(Ljava/lang/String;ZLandroid/os/Bundle;)V",
            "(DLandroidx/core/app/NotificationCompat;)V",
            "(BLdalvik/system/DexFile;)V",
            "(CLlibcore/io/Memory;)V",
            "(FLorg/xmlpull/v1/XmlPullParser;)V",
            "(SLjavax/xml/stream/XMLInputFactory;)V",
            "([Landroid/view/View;)V",
            "(Ljava/lang/String;Landroid/net/Uri;)V",
            "()Landroid/content/Context;",
        )

        for (descriptor in descriptors) {
            assertEquals(
                "expected $descriptor to be reported",
                listOf(descriptor),
                ClassFileScan.violations(classBytesWithConstants(descriptor), forbidden),
            )
        }
    }

    @Test
    fun aLongerPackageEndingInAPrimitiveCodeIsStillNotAFalseHit() {
        // The lookbehind's original purpose must survive the CR-01 fix: a forbidden prefix that is
        // only a substring of a longer package name is not a violation.
        val clean = listOf(
            "(Lcom/example/myandroid/Thing;)V",
            "(Lcom/example/notandroidx/Thing;)V",
            "com.example.myandroid.Thing",
            "Lcom/example/dalvikish/Thing;",
        )

        assertEquals(
            emptyList<String>(),
            ClassFileScan.violations(classBytesWithConstants(*clean.toTypedArray()), forbidden),
        )
    }

    @Test
    fun descriptorFixtureCarriesAPrimitivePrefixedParameter() {
        // The compiled counterpart of the synthesized case above: a method whose forbidden
        // parameter is never called, so the constant pool holds the descriptor and nothing else.
        val bytes = classBytes("easyfact/ClassFileScanTest\$PrimitivePrefixedFixture.class")

        val hits = ClassFileScan.violations(bytes, forbidden)

        assertTrue(
            "expected a primitive-prefixed StAX descriptor in $hits",
            hits.any { it.startsWith("(I") && "Ljavax/xml/stream/XMLInputFactory;" in it },
        )
    }

    @Test
    fun aScanThatVisitedNoClassFilesIsNotAPass() {
        // The WR-01 regression. Collecting no hits is also what a mis-wired classDirs or an
        // uncompiled module looks like from inside the gate, and that used to read as clean.
        assertEquals(
            listOf("no class files were scanned, so a pass would mean nothing was looked at"),
            ClassFileScan.preconditionViolations(forbidden, scannedClassCount = 0),
        )
    }

    @Test
    fun anEmptyForbiddenListIsNotAPass() {
        assertEquals(
            listOf("the forbidden-package list is empty, so the scan would pass on any input"),
            ClassFileScan.preconditionViolations(emptyList(), scannedClassCount = 12),
        )
    }

    @Test
    fun bothPreconditionsCanFailAtOnce() {
        assertEquals(
            listOf(
                "the forbidden-package list is empty, so the scan would pass on any input",
                "no class files were scanned, so a pass would mean nothing was looked at",
            ),
            ClassFileScan.preconditionViolations(emptyList(), scannedClassCount = 0),
        )
    }

    @Test
    fun aRealScanSatisfiesThePreconditions() {
        assertEquals(emptyList<String>(), ClassFileScan.preconditionViolations(forbidden, scannedClassCount = 1))
    }

    /**
     * Builds the smallest class file whose constant pool holds exactly [constants] as UTF8 entries.
     *
     * [ClassFileScan.utf8Constants] stops at the end of the pool, so nothing after it is needed.
     * Synthesizing the pool is what lets a descriptor shape be pinned exactly, instead of hoping a
     * particular compiler emits it.
     */
    private fun classBytesWithConstants(vararg constants: String): ByteArray {
        val bytes = java.io.ByteArrayOutputStream()
        java.io.DataOutputStream(bytes).use { out ->
            out.writeInt(0xCAFEBABE.toInt())
            out.writeShort(0) // minor version
            out.writeShort(65) // major version
            out.writeShort(constants.size + 1) // constant_pool_count is entries + 1
            for (constant in constants) {
                out.writeByte(1) // CONSTANT_Utf8
                out.writeUTF(constant)
            }
        }
        return bytes.toByteArray()
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

    /**
     * Takes a forbidden StAX type as a parameter that is never used, behind a primitive.
     *
     * This is the shape CR-01 let through: the descriptor is the only constant naming the type,
     * and its `L` follows the primitive code of the preceding parameter.
     */
    private class PrimitivePrefixedFixture {
        @Suppress("unused", "UNUSED_PARAMETER")
        fun configure(retries: Int, factory: javax.xml.stream.XMLInputFactory?) = Unit
    }

    /** Hands the dotted name of a JSR-105 class to reflection, which Animal Sniffer cannot see. */
    private class ReflectionFixture {
        @Suppress("unused")
        fun load(): Class<*> = Class.forName("javax.xml.crypto.dsig.XMLSignature")
    }
}
