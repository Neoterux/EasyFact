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
 * Committed failing fixtures for the keep-rule hygiene gate. `rules.keep` holds no active rule
 * today, so these unit tests are the only place the gate is ever seen red until a real rule exists.
 * All rule text is synthetic.
 */
class KeepRuleLintTest {

    @Test
    fun blanketKeepWithModifiersIsStillBlanket() {
        // The CR-02 regression. R8 accepts keep modifiers on the directive itself, and comparing
        // the raw first token against the keep family by equality missed every one of these — so
        // the broadest possible keep rule passed the gate.
        val modified = listOf(
            "-keep,allowobfuscation class * { *; }",
            "-keep,allowshrinking class ** { *; }",
            "-keep,allowobfuscation,allowshrinking class * { *; }",
            "-keep,includedescriptorclasses class ** { *; }",
            "-keepclassmembers,allowobfuscation class * { *; }",
            "-keepnames,allowshrinking class ** { *; }",
        )

        for (rule in modified) {
            val hits = lint(
                """
                # Reason: a justification does not make a blanket rule narrow.
                $rule
                """,
            )

            assertEquals("$rule should be blanket", listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
        }
    }

    @Test
    fun includeDirectiveIsRejected() {
        // Also CR-02: `-include` applies rules from a file this gate never reads, which defeats
        // both the reason rule and the blanket rule at once.
        val hits = lint(
            """
            # Reason: a reason does not make an unlinted rule file visible.
            -include extra-rules.pro
            """,
        )

        assertEquals(
            listOf("fixture.keep:2: -include/-basedirectory pulls in rules this gate never sees"),
            hits,
        )
    }

    @Test
    fun basedirectoryDirectiveIsRejected() {
        val hits = lint("-basedirectory /somewhere/else")

        assertEquals(
            listOf(
                "fixture.keep:1: no preceding '# Reason:'",
                "fixture.keep:1: -include/-basedirectory pulls in rules this gate never sees",
            ),
            hits,
        )
    }

    @Test
    fun aNarrowKeepWithModifiersStillPasses() {
        // The modifier strip must not turn every modified rule into a violation: a justified,
        // narrowly scoped rule is legal whether or not it carries modifiers.
        val hits = lint(
            """
            # Reason: reached only through a reflective lookup by fully qualified name.
            -keep,allowobfuscation class com.example.synthetic.Probe { <init>(); }
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    private fun lint(text: String) = KeepRuleLint.violations(text.trimIndent(), "fixture.keep")

    @Test
    fun reasonedNarrowKeepPasses() {
        val hits = lint(
            """
            # Reason: reached only through a reflective lookup by fully qualified name.
            -keep class com.example.synthetic.Probe { <init>(); }
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun reasonedPackageScopedDontwarnPasses() {
        val hits = lint(
            """
            # Reason: annotation-only references from a library, absent at runtime.
            -dontwarn javax.annotation.**
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun commentsAndBlankLinesAloneHaveNoStatements() {
        assertEquals(emptyList<String>(), lint("# only a comment\n\n#   -keep class ** { *; }"))
    }

    @Test
    fun unreasonedBlanketKeepYieldsBothViolationKinds() {
        val hits = lint("-keep class ** { *; }")

        assertEquals(2, hits.size)
        assertTrue("expected a missing-reason hit in $hits", hits.any { "no preceding '# Reason:'" in it })
        assertTrue("expected a blanket hit in $hits", hits.any { "blanket keep/dontwarn" in it })
        assertTrue("expected file and line in $hits", hits.all { it.startsWith("fixture.keep:1: ") })
    }

    @Test
    fun bareDontwarnIsBlanketEvenWithAReason() {
        val hits = lint(
            """
            # Reason: silence everything.
            -dontwarn **
            """,
        )

        assertEquals(listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
    }

    @Test
    fun narrowKeepWithoutReasonYieldsOnlyTheMissingReason() {
        val hits = lint("-keep class com.example.synthetic.Probe { <init>(); }")

        assertEquals(listOf("fixture.keep:1: no preceding '# Reason:'"), hits)
    }

    @Test
    fun phaseSwitchDirectivesAreRejectedOutright() {
        for (directive in listOf("-dontshrink", "-dontoptimize", "-dontobfuscate", "-ignorewarnings")) {
            val hits = lint("# Reason: x\n$directive")

            assertEquals("$directive should be blanket", listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
        }
    }

    @Test
    fun blanketPatternOnAContinuationLineIsStillCaught() {
        val hits = lint(
            """
            # Reason: x
            -keepclassmembers class
                ** {
              *;
            }
            """,
        )

        assertEquals(listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
    }

    @Test
    fun blankLineBetweenReasonAndStatementBreaksTheAssociation() {
        val hits = lint(
            """
            # Reason: detached from the rule below.

            -keep class com.example.synthetic.Probe
            """,
        )

        assertEquals(listOf("fixture.keep:3: no preceding '# Reason:'"), hits)
    }
}
