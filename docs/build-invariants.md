# Build invariants

Every gate the build enforces, the command that runs it, where its logic lives,
and, because a green build is easy to over-read, what it cannot see.

Read this before you try to work around a failing gate, and before you treat a
passing one as stronger evidence than it is. Every enforcement in this project
is a static analysis of declared inputs: a dependency graph, a merged manifest,
rule files, class-file constants, an artifact's byte count. Each gate is exactly
as strong as what it can see, and the "cannot see" part of each section below is
as much a part of the gate as its failure message.

## How the gates run

| Entry point | What it runs | When |
| --- | --- | --- |
| `./gradlew check` | the per-commit gates (everything except the release-artifact gates) | every push and pull request, and locally before you commit |
| `./gradlew verifyRelease` | the release-artifact gates: `checkReleaseSize`, `verifyKeepRules`, `verifyReleaseUrlHosts` | every push and pull request, and locally before a release |

Both are run by `.github/workflows/check.yml`. The release gates are off `check`
on purpose: they force a full release build and an App Bundle, which is too slow
for the edit loop.

The whole build runs with the Gradle configuration cache enabled and
`--configuration-cache-problems=fail` (`gradle.properties`, AGENTS.md section
10). A configuration-cache break is invisible to a single run, because the first
run simply stores a new entry, so the workflow runs `check` twice and fails if
the second run's output lacks `Configuration cache entry reused`. If a change
breaks the cache, fix the cause. Do not disable the cache.

The Gradle build needs a JDK 25: `gradle/gradle-daemon-jvm.properties` pins
`toolchainVersion=25`. The CI workflow installs Temurin 25 for that reason.
Compilation of the JVM modules is pinned separately to the JDK 17 API level
(next section), so the daemon JDK and the API level are different things.

Status when this page was written, at the end of Phase 1 (2026-10-26): the
workflow had never run on GitHub Actions. Every command in it was run locally,
but the workflow file itself is unexercised until its first push, and that first
run is also the first measurement of the release-size tolerance on a machine other
than the one that recorded the baseline (section 6).

Every gate has the same shape, which is also the rule for adding one (see
"Canary drills" below): a pure Kotlin policy in the included `build-logic`
build, unit-tested against synthetic inputs including a failing one, wrapped by a
thin Gradle task that declares its inputs.

## 1. JDK 17 API pin

**What it enforces.** `:core:invoicing` and `:core:testing` can only use the
JDK 17 API surface. A call to a newer JDK API is a compile error.

**Command.** `./gradlew :core:invoicing:compileKotlin` (reached by `check`).

**Where the logic lives.** `core/invoicing/build.gradle.kts` and
`core/testing/build.gradle.kts`: `options.release = 17` for Java and
`-Xjdk-release=17` for Kotlin. There is deliberately no Java toolchain; the
daemon runs on JDK 25 and the compilers are told to behave as JDK 17.

**What it cannot see.** The JDK 17 API is a superset of what Android API 31
offers, so this pin does not prove a call exists on the device; the Animal Sniffer
gate below covers that. It checks compile-time API surface only, so it says
nothing about runtime behaviour differences between ART and the JDK. Test source
is compiled at the same API level but runs on the JVM only, so a passing test
proves nothing about the device.

## 2. Portability boundary scanner: `verifyPortableBoundary`

**What it enforces.** The compiled main output of `:core:invoicing` and
`:core:testing` references none of `javax/xml/crypto/`, `javax/xml/stream/`,
`org/xmlpull/`, `android/`, `androidx/`, `dalvik/`, `libcore/`: not in a type
descriptor, not as a class name, and not as a `Class.forName`-style string
literal. The XML-DSig and StAX APIs are in the JDK and absent from Android, so
they may be used in test source as a verification oracle and never in main code
(AGENTS.md section 4).

**Command.** `./gradlew :core:invoicing:verifyPortableBoundary
:core:testing:verifyPortableBoundary` (reached by `check`).

**Where the logic lives.** `build-logic/src/main/kotlin/easyfact/ClassFileScan.kt`
(pure constant-pool scan, no Gradle types), `VerifyPortableBoundaryTask.kt`,
`JvmBoundaryPlugin.kt`; failing fixtures in
`build-logic/src/test/kotlin/easyfact/ClassFileScanTest.kt`.

**What it cannot see.** It is a blocklist. It names the packages the boundary
rule lists and cannot know which other JDK APIs Android API 31 lacks; that is
the next gate's job. It reads only each module's own main output, never a
dependency jar, so it relies on the dependency policy (section 8) keeping those
modules to the Kotlin standard library. It cannot see a class name assembled at
runtime, and it cannot see test source, by design.

## 3. Android API 31 membership: `animalsnifferMain`

**What it enforces.** Every JDK API that main-source bytecode references exists
in the Android API 31 signature, so a JDK 17 API that Android lacks (for example
`java.util.HexFormat`) fails the build even though the JDK 17 pin compiles it.

**Command.** `./gradlew :core:invoicing:animalsnifferMain
:core:testing:animalsnifferMain` (reached by `check`).

**Where the logic lives.** The `ru.vyarus.animalsniffer` plugin and the
`net.sf.androidscents.signature:android-api-level-31` signature artifact, both
declared only in `gradle/libs.versions.toml` and used at build time only, wired
in each JVM module's `build.gradle.kts`. The check is restricted to the main
source set through the extension's `sourceSets` property, so the oracle stays
legal in test source. `animalsnifferTest` exists but is deliberately not on
`check`; running it by hand fails on the oracle, which is the proof that the
restriction is doing its job.

**What it cannot see.** Animal Sniffer resolves class and method references only.
It does not see reflection string literals (the scanner in section 2 covers
those, as a blocklist), and it does not see behavioural differences between
Conscrypt and the JDK security providers, which is exactly where a signing
implementation can differ between a JVM test and a device. Its API 31 signature
data has never been diffed against a real API 31 `android.jar`; the artifact's
provenance is a published third-party build. The on-device golden-byte signature
test on API 31, planned for the signing phase, is the runtime backstop and must
not be dropped on the strength of this gate.

## 4. Release minify and resource-shrink assertion

**What it enforces.** The release variant must have code minification and
resource shrinking on. The build fails at configuration time if either is off.

**Command.** Any Gradle invocation that configures `:app`, for example
`./gradlew :app:assembleRelease`. There is no separate task: the assertion is a
configuration-time failure.

**Where the logic lives.** `build-logic/src/main/kotlin/easyfact/AppGatesPlugin.kt`
reads `isMinifyEnabled` and `shrinkResources` from the release variant rather
than trusting that the DSL block was applied. `app/build.gradle.kts` carries the
written answer to why the optimization-scoping entry from the project skeleton
was removed (it left app code and libraries unshrunk, measured).

**What it cannot see.** It reads the two flags. It cannot see whether R8 actually
removed anything, which keep rules widen what is kept, or whether R8 full mode is
in effect. The app has no classes of its own yet, so `mapping.txt` lists no
application class today; the property was observed once with a temporary probe
class that was then reverted, not by an assertion that remains in the build.

## 5. Keep-rule hygiene: `verifyKeepRules`

**What it enforces.** Every project-owned keep rule has a `# Reason:` comment
directly above it, and none is blanket: no `-dontshrink`, `-dontoptimize`,
`-dontobfuscate` or `-ignorewarnings`, no keep-family rule over a bare `*` or
`**`, no `-dontwarn **`. The task fails if its input collection is empty, so a
mis-wired glob cannot certify nothing.

**Command.** `./gradlew :app:verifyKeepRules` (reached by both `check` and
`verifyRelease`).

**Where the logic lives.** `build-logic/src/main/kotlin/easyfact/KeepRuleLint.kt`,
`VerifyKeepRulesTask.kt`; failing fixtures in `KeepRuleLintTest.kt`. It reads
`app/src/<variant>/keepRules/<name>.keep` only.

**What it cannot see.** It covers only the keep rules this project writes. AGP's
own default rules and every AAR's consumer rules are merged into
`app/build/outputs/mapping/release/configuration.txt`, each section labelled with
its source, so a library that starts contributing a blanket rule is attributable
by hand but is not failed automatically. AGP's R8 configuration analysis report
is a review aid and is deliberately not wired to any gate.

## 6. Release size: `checkReleaseSize`

**What it enforces.** The release APK and the App Bundle may not exceed the
committed baseline in `app/size-baseline.properties` plus a fixed tolerance of
1024 bytes. Sizes come from AGP's own `SingleArtifact.APK` and
`SingleArtifact.BUNDLE`, and the task prints a `MEASURED apk.bytes=...` line on
every run.

**Command.** `./gradlew :app:checkReleaseSize` (reached by `verifyRelease`, not
by `check`). To move the baseline see "Raising the size baseline" below;
`recordReleaseSizeBaseline` is the only sanctioned way.

**Where the logic lives.** `build-logic/src/main/kotlin/easyfact/SizeBaseline.kt`,
`CheckReleaseSizeTask.kt`, `RecordReleaseSizeBaselineTask.kt`, the tolerance
constant in `AppGatesPlugin.kt`; failing fixtures in `SizeBaselineTest.kt`.

**What it cannot see.** The comparison is one-sided: a smaller artifact is never
a violation, so a sudden drop (for example, R8 removing something it should not)
goes unnoticed. The baseline was shown byte-comparable across reruns on one
machine only, never across machines and JDKs, which is why the gate carries an
explicit tolerance. The 1024-byte value is a judgment, not a measurement, and
the first cross-machine run is the first real test of it. If the gate fails on a
machine other than the one that recorded the baseline, treat that as a finding
about the tolerance and record it; do not widen the tolerance quietly to make a
build green. It sees total size, not what contributed to it.

## 7. Backup rules: `verify<Variant>BackupRules`

Concrete tasks today: `verifyDebugBackupRules` and `verifyReleaseBackupRules`.
One is registered per variant with no build-type selector, so a future flavour or
build type is covered automatically.

**What it enforces.** On every variant's merged manifest, `android:allowBackup`
is the literal `true` (kept explicit so the rules stay exercisable on a device),
and the data extraction rules and the legacy backup rules it references exclude
every documented domain with `path="."` and contain no `<include>` element. A
rule file that shadows another in a resource overlay is checked as well, and an
unresolvable reference is itself a violation.

**Command.** `./gradlew verifyDebugBackupRules verifyReleaseBackupRules`
(reached by `check`). The on-device companion is `scripts/verify-backup.sh
--serial emulator-<port>`, which needs an emulator you are willing to wipe and is
not run in CI.

**Where the logic lives.** `build-logic/src/main/kotlin/easyfact/BackupRulesCheck.kt`,
`VerifyBackupRulesTask.kt`; failing fixtures in `BackupRulesCheckTest.kt`; the
rules in `app/src/main/res/xml/data_extraction_rules.xml` and `backup_rules.xml`.

**What it cannot see.** It asserts what the rules say, not that the platform
honours them. `scripts/verify-backup.sh` closes part of that gap on a device:
a mandatory include-all positive control must restore its markers before the
real case is reported, and the real case then restores nothing. That proof was
measured on API 37 only, with marker files standing in for data, because no real
database or certificate directory exists yet. Backup behaviour on API 31 through
36 and on manufacturer builds is assumed, not shown. A never-launched app is in
the "stopped" state and `bmgr backupnow` refuses it, so any bmgr-based test of
this app must run `cmd package unstop` first. See "Device-to-device transfer is
UNVERIFIED" below for the part no proof exists for.

## 8. Dependency policy: `verifyDependencyPolicy`

**What it enforces.** Every shipping runtime classpath (each `:app` variant, and
both JVM modules) resolves only to reviewed dependencies. A telemetry, analytics,
crash-reporting, APM, attribution or remote-logging group is reported as
`DENIED`; any module that is neither denied nor on the reviewed allowlist is
`NOT ALLOWLISTED`. BouncyCastle and `:core:testing` are denied on every shipping
classpath, so a test-only helper cannot leak into the APK. The JVM modules are
held to the Kotlin standard library only.

**Command.** `./gradlew :app:verifyDependencyPolicy
:core:invoicing:verifyDependencyPolicy :core:testing:verifyDependencyPolicy`
(reached by `check`).

**Where the logic lives.** `build-logic/src/main/kotlin/easyfact/DependencyPolicy.kt`
(denylist and allowlists as reviewed lines), `VerifyDependencyPolicyTask.kt`
(reads `variant.runtimeConfiguration` through `resolutionResult.rootComponent`),
`AppGatesPlugin.kt`, `JvmBoundaryPlugin.kt`; failing fixtures in
`DependencyPolicyTest.kt`.

**What it cannot see.** The denylist is a blocklist of known group prefixes. A
telemetry library under a group nobody listed is caught only by the allowlist
half, and only until someone allowlists its group; an allowlisted library that
itself phones home passes. It reads the resolved dependency graph, so it cannot
see native libraries, vendored source, jars copied into the build, or code
reached by reflection. See "The no-telemetry invariant is a blocklist" below.

## 9. Merged-manifest policy: `verify<Variant>ManifestPolicy`

Concrete tasks today: `verifyDebugManifestPolicy` and `verifyReleaseManifestPolicy`.

**What it enforces.** Every variant's merged manifest declares only reviewed
permissions, exposes only reviewed exported components, and declares only
reviewed services. The reviewed lists are seeded with what the merged manifest
already carries (one signature-level permission and one permission-guarded
profile-install receiver from AndroidX); services start empty.

**Command.** `./gradlew verifyDebugManifestPolicy verifyReleaseManifestPolicy`
(reached by `check`).

**Where the logic lives.** `build-logic/src/main/kotlin/easyfact/ManifestPolicy.kt`
(the reviewed lists, with their justification in comments),
`VerifyManifestPolicyTask.kt`; failing fixtures in `ManifestPolicyTest.kt`.

**What it cannot see.** It sees the merged manifest, not runtime behaviour: a
component that is declared and reviewed can still do something unreviewed. It
does not judge whether an allowlisted entry is wise, only that a human added it.
It cannot see an intent that hands data to another app, which needs no
permission and appears nowhere in the manifest.

## 10. Release-dex URL hosts: `verifyReleaseUrlHosts`

**What it enforces.** Every `http` or `https` host that appears as a string in
the release APK's dex files is on the reviewed host list. The task prints the
host set it found and the residual-gap statement on every run, success or
failure, and fails if it read no dex file at all. That last branch is in the task
but no canary drill has ever exercised it; the host-list failure is the one that
was observed.

**Command.** `./gradlew :app:verifyReleaseUrlHosts` (reached by `verifyRelease`,
not by `check`).

**Where the logic lives.** `build-logic/src/main/kotlin/easyfact/UrlHostScan.kt`
(the reviewed hosts and the `RESIDUAL_GAP` text as constants),
`VerifyReleaseUrlHostsTask.kt`; failing fixtures in `UrlHostScanTest.kt`.

**What it cannot see.** It sees only what R8 kept: a string R8 eliminated is
invisible, and a host that is only ever assembled at runtime from fragments is
invisible even when the class that assembles it is in the dex (a canary drill
showed both). It also cannot see reflection, a WebView, a native library, or an
allowlisted library that itself phones home. The scan is a byte-level search for
URL-shaped strings, not a dex parse and not a network monitor.

## Device-to-device transfer is UNVERIFIED

Nothing in this repository proves that device-to-device transfer excludes app
data. The research restore did not materialise even under an include-all positive
control, and only API 37 was tested, so there is no known automated or reliable
manual proof, and this is recorded as UNVERIFIED rather than passed.

What ships is the structural assertion: a default-deny `<device-transfer>` block
with no `<include>`, checked against every variant's merged manifest by
`verify<Variant>BackupRules` (section 7). Alongside it, the `bmgr` cloud-backup
probe in `scripts/verify-backup.sh` was proven in both directions (control
restores, real case does not). Behaviour on API 31 through 36 and on manufacturer
builds is assumed, and one smoke run per supported major Android version is owed
before the first release. A structural pass must not be read as a device-transfer
pass.

Two further protections do not depend on the rule files and are the contract for
the persistence work: the certificate directory belongs under
`Context.getNoBackupFilesDir()` (whose exclusion the platform guarantees
independently of the rules; AGENTS.md section 6.4), and the startup invariant
that compares the next sequential with the highest issued document is the second
line of defence against a stale restore.

## The no-telemetry invariant is a blocklist, not a proof of absence

Three layers narrow the gap: the dependency policy (section 8), the
merged-manifest policy (section 9) and the release-dex host scan (section 10).
They narrow it and do not close it. None of them can see a URL assembled at
runtime from parts, a host reached through reflection, a WebView, a native
library, or an allowlisted library that itself phones home. The text printed by
`verifyReleaseUrlHosts` is the same statement, in the same words, kept as a code
constant so it cannot drift from the code that reports the result.

The final assurance is runtime, and it is manual: capture the app's traffic on a
real device during a complete issue flow and confirm only allowlisted hosts
appear. That traffic capture is owed before the production-release gate, and
nothing built so far substitutes for it. Do not record the telemetry invariant as
proven on the strength of these gates.

## Manual-only verifications

These four are not covered by CI and must not be mistaken for things it covers.

| Behaviour | Why it is manual |
| --- | --- |
| The platform honours the backup rules | Needs a running emulator and `bmgr`; Gradle cannot exercise the backup framework. Run `scripts/verify-backup.sh --serial emulator-<port>` against a throwaway emulator. Measured on API 37 only. |
| Device-to-device transfer excludes app data | UNVERIFIED, see above. No known proof exists. |
| No egress to a non-allowlisted host at runtime | A blocklist is not a proof; needs a traffic capture on a real device during a full issue flow, before the production-release gate. |
| Legitimacy of the two Animal Sniffer Maven coordinates | The package-legitimacy tooling covers npm, PyPI and crates, not Maven. The coordinates and their licences were checked against Maven Central and the published POMs by an automated session, not by a maintainer, so a maintainer should re-audit them. The signature data provenance (section 3) is separate and still open. |

## Raising the size baseline

The number moves only through:

```bash
./gradlew :app:recordReleaseSizeBaseline -Preason="<why the size changed>"
```

The task refuses to run without a non-blank `-Preason`, rewrites
`app/size-baseline.properties` atomically, and appends a dated history line that
records the old and new sizes and the reason. Editing the file by hand is a
defect: the history exists so that every move is explained. Expect the number to
change when the UI stack is replaced, and re-measure it at the production-release
gate.

## Adding a dependency, a permission or a host

Each of these fails its gate until a human adds it to the relevant list, and
that is the intended friction: it is where the AGENTS.md section 2.4 review
happens.

| You are adding | It fails | Add it to |
| --- | --- | --- |
| a library from a new group | `verifyDependencyPolicy` (`NOT ALLOWLISTED`) | `DependencyPolicy.APP_ALLOWED`, after the dependency review |
| a permission | `verify<Variant>ManifestPolicy` | `ManifestPolicy.PERMISSIONS`, with a comment saying why |
| an exported component | `verify<Variant>ManifestPolicy` | `ManifestPolicy.EXPORTED_COMPONENTS`, with a comment saying why |
| a service | `verify<Variant>ManifestPolicy` | `ManifestPolicy.SERVICES`, and prefer not to |
| a network host | `verifyReleaseUrlHosts` | `UrlHostScan.ALLOWED_HOSTS`, when the feature lands and not before |

All of these files are under `build-logic/src/main/kotlin/easyfact/`. A
denylisted group is never added to an allowlist to get a build through; the
denylist is a product invariant, and trimming it is not a fix.

## Canary drills

Every gate here has a committed failing fixture in `build-logic`'s unit tests and
was observed failing on the real tree before it was trusted. A gate added later
without both is not evidence: a boundary check that cannot fail is a documented
anti-pattern. When you add or change a gate, make it fail on purpose against the
real files, record the failure output, revert, and confirm it is green again.
