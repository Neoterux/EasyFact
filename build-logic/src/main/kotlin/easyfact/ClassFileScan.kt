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

import java.io.DataInputStream

/**
 * The whole portability-boundary policy as pure functions over class-file bytes.
 *
 * No Gradle types appear here on purpose: that is what lets [ClassFileScanTest] exercise the
 * policy against real compiled fixtures in milliseconds. The Gradle task is a thin wrapper.
 *
 * The scan reads the UTF8 entries of a class file's constant pool. That is where descriptors
 * (`Ljavax/xml/stream/XMLInputFactory;`), internal class names and string literals live, so a
 * forbidden package is found whether it is used as a type, as a call target or as a name handed
 * to reflection. Animal Sniffer resolves references only and cannot see the last case.
 */
object ClassFileScan {

    private const val MAGIC = 0xCAFEBABE.toInt()

    /** Returns every UTF8 constant-pool entry of [bytes], in pool order. */
    fun utf8Constants(bytes: ByteArray): List<String> {
        val input = DataInputStream(bytes.inputStream())
        require(input.readInt() == MAGIC) { "not a class file: bad magic number" }
        input.readUnsignedShort() // minor version
        input.readUnsignedShort() // major version
        val poolCount = input.readUnsignedShort()
        val constants = ArrayList<String>()
        var index = 1
        while (index < poolCount) {
            when (val tag = input.readUnsignedByte()) {
                1 -> constants += input.readUTF()
                // Integer, Float, Fieldref, Methodref, InterfaceMethodref, NameAndType, Dynamic,
                // InvokeDynamic.
                3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4)
                // Long and Double occupy two pool slots.
                5, 6 -> {
                    input.skipNBytes(8)
                    index++
                }
                // Class, String, MethodType, Module, Package.
                7, 8, 16, 19, 20 -> input.skipNBytes(2)
                // MethodHandle.
                15 -> input.skipNBytes(3)
                else -> error("unknown constant pool tag $tag")
            }
            index++
        }
        return constants
    }

    /**
     * Complaints about a scan whose result cannot mean anything, whatever it found.
     *
     * This is WR-01. The task reported a pass whenever it collected no hits, and collecting no
     * hits is also exactly what a mis-wired `classDirs`, an uncompiled module or an empty
     * forbidden list looks like from inside the gate. Under any of those, "no violations found"
     * means "nothing was looked at" — the one thing a boundary gate must never report as clean.
     *
     * Returns an empty list when the scan was genuinely performed, whatever its verdict then is.
     */
    fun preconditionViolations(forbiddenSlashPrefixes: List<String>, scannedClassCount: Int): List<String> {
        val result = ArrayList<String>()
        if (forbiddenSlashPrefixes.isEmpty()) {
            result += "the forbidden-package list is empty, so the scan would pass on any input"
        }
        if (scannedClassCount < 1) {
            result += "no class files were scanned, so a pass would mean nothing was looked at"
        }
        return result
    }

    /**
     * Returns the constants of [bytes] that name a package in [forbiddenSlashPrefixes].
     *
     * Each prefix is written in slash form (`javax/xml/stream/`) and is matched in three shapes:
     * the slash form with an optional leading `L` (descriptors and internal class names), the same
     * form where the descriptor's `L` follows another descriptor token, and the dotted form
     * (string literals handed to reflection). A negative lookbehind on a word, `$`, `/` or `.`
     * character stops a substring of a longer name from being a false hit.
     *
     * The second shape exists because the lookbehind alone is wrong inside a method descriptor.
     * `L` is itself a word character, so in `(ILandroid/content/Context;)V` the lookbehind rejects
     * the `L` (preceded by the primitive code `I`) and then rejects the bare prefix (preceded by
     * that `L`) — and a parameter the method never calls leaves no standalone `Class` constant to
     * catch it instead, so the whole violation passed the gate. The allowed predecessors are the
     * field-descriptor primitive codes, `[`, `(` and the `;` ending a preceding type. `V` is
     * deliberately absent: void is only ever a return type, so it never precedes a parameter.
     * A package that genuinely ends in one of those letters immediately before a forbidden
     * prefix would be a false positive here, which fails the build loudly rather than shipping a
     * violation silently — the correct direction for a boundary gate to err in.
     */
    fun violations(bytes: ByteArray, forbiddenSlashPrefixes: List<String>): List<String> {
        val patterns = forbiddenSlashPrefixes.flatMap { prefix ->
            val slash = Regex.escape(prefix)
            listOf(
                Regex("(?<![\\w$/.])L?$slash"),
                Regex("(?<=[(\\[;BCDFIJSZ])L$slash"),
                Regex("(?<![\\w$/.])" + Regex.escape(prefix.replace('/', '.'))),
            )
        }
        return utf8Constants(bytes).filter { constant -> patterns.any { it.containsMatchIn(constant) } }
    }
}
