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

    @Test
    fun aLeadingAtFileIsIndirection() {
        // Confirmed against R8: a bare `@other.pro` is ProGuard's shorthand for `-include other.pro`
        // and R8 honours it. The lint used to skip the line entirely — no reason requirement, no
        // blanket check, no indirection check — because it carries no leading `-`.
        val hits = lint(
            """
            # Reason: a reason does not make an unlinted rule file visible.
            @extra-rules.pro
            """,
        )

        assertEquals(
            listOf("fixture.keep:2: -include/-basedirectory pulls in rules this gate never sees"),
            hits,
        )
    }

    @Test
    fun anUnreasonedAtFileAlsoNeedsAReason() {
        // The `@` line is now a statement, so the reason rule reaches it too.
        val hits = lint("@extra-rules.pro")

        assertEquals(
            listOf(
                "fixture.keep:1: no preceding '# Reason:'",
                "fixture.keep:1: -include/-basedirectory pulls in rules this gate never sees",
            ),
            hits,
        )
    }

    @Test
    fun aSecondDirectiveOnTheSameLineIsEvaluated() {
        // Confirmed against R8: it reads a token stream, so a narrow rule does not shelter what
        // follows it on the same line. The lint judged the statement by its first token alone.
        val hits = lint(
            """
            # Reason: the narrow rule is fine; the include smuggled in beside it is not.
            -keep class com.example.synthetic.Probe { <init>(); } -include extra-rules.pro
            """,
        )

        assertEquals(
            listOf("fixture.keep:2: -include/-basedirectory pulls in rules this gate never sees"),
            hits,
        )
    }

    @Test
    fun aBlanketKeepHidingAfterANarrowOneIsStillBlanket() {
        val hits = lint(
            """
            # Reason: the narrow rule is fine; the blanket keep hiding behind it is not.
            -keep class com.example.synthetic.Probe { <init>(); } -keep class * { *; }
            """,
        )

        assertEquals(listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
    }

    @Test
    fun aBlanketDirectiveLaterOnTheSameLineIsStillBlanket() {
        val hits = lint(
            """
            # Reason: the narrow rule is fine; switching shrinking off beside it is not.
            -keep class com.example.synthetic.Probe { <init>(); } -dontshrink
            """,
        )

        assertEquals(listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
    }

    @Test
    fun anAnnotationFilterInsideAClassSpecificationIsNotIndirection() {
        // `@` is also R8's annotation filter. Flagging every `@` token would reject this legitimate
        // rule, so only a statement-leading `@file` counts — that is the whole of its grammar.
        val hits = lint(
            """
            # Reason: entry points are marked with the Keep annotation and found reflectively.
            -keep @androidx.annotation.Keep class com.example.synthetic.Probe { <init>(); }
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun oneDirectivesArgumentsAreNotReadAsAnothers() {
        // FW-05: the earlier version of this test passed with the argument bounding removed, so it
        // pinned nothing. This shape discriminates — without bounding, the `-keep`'s `class *` is
        // read as the `-dontwarn`'s arguments and the blanket keep goes unreported.
        val hits = lint(
            """
            # Reason: a package-scoped dontwarn does not excuse the blanket keep beside it.
            -dontwarn javax.annotation.** -keep class * { *; }
            """,
        )

        assertEquals(listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
    }

    @Test
    fun aLegalPairOnOneLineStaysClean() {
        // The counterpart: bounding must not invent a violation out of two legitimate rules.
        val hits = lint(
            """
            # Reason: narrow keep plus a package-scoped dontwarn, both legitimate.
            -keep class com.example.synthetic.Probe { <init>(); } -dontwarn javax.annotation.**
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun anAtFileAfterAClosingBraceIsIndirection() {
        // FR-01, confirmed against R8 9.5.0-dev: it reports "Included file ... not found" at the
        // `@`, so a rule file can begin right after a member block. Three line-oriented fixes let
        // this through.
        val hits = lint(
            """
            # Reason: the narrow rule is fine; the include smuggled in after it is not.
            -keep class com.example.synthetic.Probe { <init>(); } @extra-rules.pro
            """,
        )

        assertEquals(
            listOf("fixture.keep:2: -include/-basedirectory pulls in rules this gate never sees"),
            hits,
        )
    }

    @Test
    fun anAtFileAfterACompletedRuleWithNoBlockIsIndirection() {
        val hits = lint(
            """
            # Reason: a package-scoped dontwarn is fine; the include after it is not.
            -dontwarn javax.annotation.** @extra-rules.pro
            """,
        )

        assertEquals(
            listOf("fixture.keep:2: -include/-basedirectory pulls in rules this gate never sees"),
            hits,
        )
    }

    @Test
    fun aCommentLineInsideAnOpenBlockDoesNotHideWhatFollows() {
        // FW-02. Comments are whitespace to R8, so the blanket keep after the block is a real rule.
        val hits = lint(
            """
            # Reason: probe.
            -keep class com.example.synthetic.Probe {
            # a note in the middle of the member block
            <init>(); } -keep class * { *; }
            """,
        )

        assertTrue("expected the hidden blanket keep to be reported in $hits", hits.any { it.endsWith(": blanket keep/dontwarn") })
    }

    @Test
    fun aBlankLineInsideAnOpenBlockDoesNotHideWhatFollows() {
        val hits = lint(
            """
            # Reason: probe.
            -keep class com.example.synthetic.Probe {

            <init>(); } -include extra-rules.pro
            """,
        )

        assertTrue(
            "expected the hidden include to be reported in $hits",
            hits.any { it.endsWith(": -include/-basedirectory pulls in rules this gate never sees") },
        )
    }

    @Test
    fun aMemberAnnotationFilterOnItsOwnLineIsNotIndirection() {
        // FW-01 — a regression the previous fix introduced. Inside a member block an `@` is an
        // annotation filter on a member, and this is a common, legal rule shape.
        val hits = lint(
            """
            # Reason: the bridge methods are reached only from JavaScript.
            -keep class com.example.synthetic.Bridge {
            @android.webkit.JavascriptInterface <methods>;
            }
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun negatedClassKeywordsAreStillBlanket() {
        // FW-03. `-keep !interface * { *; }` keeps every class that is not an interface.
        for (keyword in listOf("!class", "!interface", "!enum")) {
            val hits = lint(
                """
                # Reason: a justification does not make a negated blanket rule narrow.
                -keep $keyword * { *; }
                """,
            )

            assertEquals("$keyword should be blanket", listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
        }
    }

    @Test
    fun anAnnotationScopedKeepIsNotBlanket() {
        // FI-02. `-keep @Keep class *` is scoped by the annotation, not blanket — and this is the
        // very example the policy's own KDoc gives, which the previous implementation flagged.
        val hits = lint(
            """
            # Reason: entry points are marked with the Keep annotation and found reflectively.
            -keep @androidx.annotation.Keep class *
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun anExtendsAnnotationFilterIsNotIndirection() {
        val hits = lint(
            """
            # Reason: subclasses of the annotated base are constructed reflectively.
            -keep class com.example.synthetic.Probe extends @androidx.annotation.Keep com.example.synthetic.Base
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun aDirectiveSplitAcrossLinesIsStillOneRule() {
        // The tokenizer must not turn a legal multi-line member block into several rules.
        val hits = lint(
            """
            # Reason: reached only through a reflective lookup by fully qualified name.
            -keep class com.example.synthetic.Probe {
                <init>();
                public void run();
            }
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun aHashInsideAFilenameDoesNotStartAComment() {
        // W1a. `#` opens a comment only at a token start. Treating it as one anywhere truncated
        // the line at the filename and hid every rule after it.
        val hits = lint(
            """
            # Reason: a mapping file does not excuse the blanket keep beside it.
            -printmapping out#x.txt -keep class * { *; }
            """,
        )

        assertEquals(listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
    }

    @Test
    fun aBraceInsideANonSpecArgumentDoesNotOpenAMemberBlock() {
        // W1b. Only a directive that takes a class specification can open a `{ ... }` block. A `{`
        // in a filename used to open a block that never closed, so everything after it was read as
        // member-block content and never evaluated.
        val hits = lint(
            """
            # Reason: a mapping file does not excuse the blanket keep beside it.
            -printmapping out{x.txt -keep class * { *; }
            """,
        )

        assertEquals(listOf("fixture.keep:2: blanket keep/dontwarn"), hits)
    }

    @Test
    fun aBraceInANonSpecArgumentDoesNotHideAnInclude() {
        val hits = lint(
            """
            # Reason: a mapping file does not excuse the include beside it.
            -printseeds seeds{1.txt -include extra-rules.pro
            """,
        )

        assertEquals(
            listOf("fixture.keep:2: -include/-basedirectory pulls in rules this gate never sees"),
            hits,
        )
    }

    @Test
    fun aTrailingCommentIsStillACommentAndAReasonLineStillCounts() {
        // The guard for W1a: `#` at a token start must still open a comment, or the reason rule
        // and every trailing note in the file stop working.
        val hits = lint(
            """
            # Reason: reached only through a reflective lookup by fully qualified name.
            -keep class com.example.synthetic.Probe { <init>(); } # keeps the no-arg constructor
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun aCommentedOutBlanketRuleIsStillIgnored() {
        val hits = lint(
            """
            # Reason: probe.
            -keep class com.example.synthetic.Probe { <init>(); }
            # -keep class * { *; }
            """,
        )

        assertEquals(emptyList<String>(), hits)
    }

    @Test
    fun aGenuineMemberBlockStillOpensAndCloses() {
        // The guard for W1b: a keep-family `{` must still open a block, or a member on its own
        // line is read as a new rule and the FW-01 regression comes back.
        val hits = lint(
            """
            # Reason: the bridge methods are reached only from JavaScript.
            -keep class com.example.synthetic.Bridge {
            @android.webkit.JavascriptInterface <methods>;
            }
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
