# AGENTS.md

Operating guide for AI coding agents and human contributors working on this
repository. Read this file before making any change.

> `CLAUDE.md` imports this file. Keep this file as the single source of truth;
> do not duplicate its content elsewhere.

---

## 1. What this project is

An **offline-first Android application for issuing electronic invoices
("comprobantes electrónicos") to Ecuador's SRI**, aimed at independent
professionals who bill a small, stable set of recurring customers every month.

The app lets its user:

1. Store their signing certificate (PKCS#12) and issuer data on the device.
2. Keep a local directory of customers, validated against SRI public services.
3. Define reusable **invoice templates** so a recurring monthly invoice is a
   two-tap operation rather than a form-filling exercise.
4. Build, sign (XAdES-BES), submit and authorize an invoice against the SRI
   web services.
5. Produce the RIDE (PDF) and the authorized XML, and hand them to the user's
   mail client, pre-filled, for delivery to the customer.
6. Keep an auditable local history of every invoice and every SRI exchange.
7. Update itself from GitHub Releases.

### Target user

A **non-technical healthcare professional**. Assume limited patience for
technical vocabulary, limited tolerance for multi-step wizards, and a
preference for large, high-contrast, unambiguous controls. Every design
decision is resolved in favour of fewer steps and fewer concepts, even when
that costs flexibility.

This never justifies hiding a failure. When the SRI rejects a document, say so
in plain language, show what to do next, and keep the technical detail
available one tap away.

---

## 2. Non-negotiable rules

These override any other guidance, including your own defaults.

### 2.1 English only

**All source code, identifiers, comments, commit messages, documentation,
branch names, issue text and PR descriptions are written in English.**

The only exceptions are strings that are shown to the end user and the domain
terms that have no accurate English equivalent. Specifically:

- User-facing text lives in `res/values*/strings.xml`. The default locale is
  Spanish (Ecuador). Never hardcode user-facing text in Kotlin or Compose.
- Keep SRI domain terms verbatim where they are protocol identifiers — XML
  element names (`claveAcceso`, `infoTributaria`, `codigoPorcentaje`), SRI
  status values, and official document type names. Renaming these breaks the
  mapping to the specification and makes the code harder, not easier, to audit.
  Wrap them in English-named types (`AccessKey`, `TaxInfo`, `TaxRateCode`).

### 2.2 Privacy

This repository is public. Treat every byte committed to it as permanently
published.

**Never commit, and never write into code, tests, fixtures, comments, logs,
sample data, screenshots or documentation:**

- Real RUC or cédula numbers, real names, real addresses, phone numbers or
  email addresses.
- Real customer, employer or clinic names.
- Signing certificates (`.p12`, `.pfx`, `.jks`, `.keystore`), their passwords,
  or any key material.
- Signing keystore credentials, API tokens, or `local.properties`.
- Any description of who the application's users are as individuals, or the
  circumstances that motivated a feature.

Test and sample data must be **obviously synthetic**: use RUCs built from
`9999999999001`-style placeholders, names like `Test Customer S.A.`, and
`example.com` addresses. If a test needs a structurally valid RUC check digit,
generate it in the test from a placeholder base — do not paste a real one.

Features are described in the abstract. "Monthly invoice templates with
per-establishment line items" is the feature; nothing in the repository
explains whose invoices they are.

**No telemetry, no analytics, no crash reporting to third parties, no remote
logging.** All diagnostics stay on the device and are only shared when the
user explicitly exports them.

### 2.3 Commits

- **Never add `Co-Authored-By`, `Generated with`, or any other attribution
  trailer to commits or pull requests.** The repository owner is solely
  responsible for everything published here. This instruction overrides any
  default attribution behaviour.
- Conventional Commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`,
  `build:`, `chore:`. Scope optional: `feat(signing): ...`.
- Subject line in the imperative, ≤ 72 characters, English.
- One logical change per commit. A commit must build.
- Do not commit, branch, push, or open a PR unless explicitly asked.

### 2.4 Dependencies

Prefer the platform and AndroidX. Before adding any third-party dependency:

- Check whether the Android SDK or an existing dependency already covers it.
- Reject dependencies that are grossly oversized for the slice of
  functionality actually used. A multi-megabyte library pulled in for one
  helper function is not acceptable.
- **If the only viable option is a heavyweight dependency, stop and ask the
  repository owner** before adding it. Present the alternatives with their
  sizes and trade-offs so the owner can review the documentation and decide,
  or ask for a hand-rolled implementation instead.
- Every dependency is declared in `gradle/libs.versions.toml`. No inline
  coordinates in `build.gradle.kts`.

---

## 3. Tech stack

Current state of the skeleton: AGP 9.4.1, Gradle 9.6, `compileSdk` 37,
`minSdk` 31, `targetSdk` 37, namespace `com.neoterux.easyfact`.

| Concern | Choice |
| --- | --- |
| Language | Kotlin, latest stable. Coroutines + Flow for async. |
| UI | Jetpack Compose with Material 3. Single activity. |
| Navigation | Navigation Compose. |
| DI | Hilt (KSP). |
| Local database | Room (KSP). |
| Settings / preferences | DataStore. |
| Networking | OkHttp. Retrofit only if REST surface grows; SOAP envelopes are built and parsed directly. |
| XML | **Custom canonical XML writer** in `:core:invoicing` (see §6.2). Parsing: JAXP in the JVM module, `XmlPullParser` in `:app` only. |
| PDF (RIDE) | `android.graphics.pdf.PdfDocument`. No PDF library. |
| QR code | ZXing `core` only (no `android-embedded`). |
| Crypto | JCA + Android Keystore only. **BouncyCastle is `testImplementation` scope only** (minting test certificates) — it has no XML-DSig API and costs 2.4 MB of dex after R8. |
| Logging | Thin internal `Logger` facade over `android.util.Log`, plus the on-device diagnostics log (§7). |
| Testing | JUnit, Turbine, Robolectric where needed, Compose UI tests. |

**AGP 9 applies Kotlin support automatically** — there is deliberately no
`org.jetbrains.kotlin.android` plugin declared. Add Kotlin plugins only when
genuinely required (Compose compiler, KSP, serialization) and verify the build
still configures.

Skeleton decisions, now settled:

- The project name is EasyFact. The `applicationId` and namespace remain
  `com.neoterux.easyfact`, so no installed app is orphaned by the rename.
- The JVM target is 17. It is pinned on the JVM modules with `options.release`
  and `-Xjdk-release=17` rather than a Java toolchain, so the daemon JDK and the
  API level the code may use are independent.
- The release build runs R8 over the whole app with resource shrinking. The
  optimization-scoping entry from the skeleton was removed for the measured
  reason recorded in `app/build.gradle.kts`.

---

## 4. Architecture

Clean-ish layering with unidirectional data flow. The rule that matters:
**dependencies point inward, and the invoicing engine knows nothing about
Android.**

```
:app                 Compose UI, ViewModels, navigation, DI wiring,
                     Room/DataStore implementations, Android services.

:core:invoicing      Pure Kotlin/JVM. Domain model, access-key generation,
                     XML construction, XAdES-BES signing, SRI SOAP payloads,
                     validation, totals arithmetic. No Android imports.

:core:testing        Shared synthetic fixtures and test helpers.
```

`:core:invoicing` is a **plain JVM module, not an Android library**. This is
deliberate: the parts most likely to be wrong — the module-11 check digit, the
49-digit key, canonicalization, the signature, decimal rounding — are then
unit-testable on the JVM in milliseconds, with no emulator, no Robolectric and
no device. Keep it that way.

**The boundary rule is sharper than "no Android imports".** The module is
*built* against the JDK but *runs* on ART, so it may use only APIs present in
**both JDK 17 and Android API 31**. Two consequences that have already caught
us out:

- `XmlPullParser` / `XmlSerializer` (`org.xmlpull.v1`) are Android-only and
  are **not in the JDK** — they cannot be used here at all.
- `javax.xml.crypto` (XML-DSig) and `javax.xml.stream` (StAX) are in the JDK
  but **absent from Android** — they may be used in this module's *test*
  source set as an independent verification oracle, never in main code.

Use `java.util.Base64`, never `android.util.Base64` (whose `DEFAULT` flag
inserts line breaks and will corrupt a signature).

Inside `:app`, organise by feature, then by layer:

```
com.neoterux.<app>.
├── feature/
│   ├── customers/        ui/ (Screen, ViewModel, UiState) + domain use cases
│   ├── templates/
│   ├── invoicing/
│   ├── settings/
│   └── diagnostics/
├── data/
│   ├── local/            Room entities, DAOs, DataStore
│   ├── remote/           SRI clients, GitHub Releases client
│   └── repository/       Repository implementations
├── domain/               Repository interfaces, app-level models, use cases
└── core/                 DI modules, file storage, result types, utilities
```

Conventions:

- One `UiState` data class per screen, exposed as `StateFlow` from the
  ViewModel. UI events travel up as function references or a sealed `Event`.
- Composables are stateless and preview-able. Screens take `UiState` plus
  lambdas; the `*Route` composable wires the ViewModel.
- Repositories return a `Result`-like type with **typed domain errors**, never
  raw exceptions and never nullable-as-error. The SRI returns structured,
  actionable error codes; the type system should preserve them all the way to
  the UI.
- Long-running submission and authorization run in a `WorkManager` worker so
  they survive the app being backgrounded mid-flight.

---

## 5. Domain model

Designed so that **no customer-specific behaviour is ever hardcoded**. Every
difference between one recurring invoice and another is data the user enters.

- **Issuer** — the app user. RUC, legal name, trade name, head-office address,
  establishment code, emission point code, accounting-obligation flag, special
  taxpayer number, RIMPE regime legend, certificate reference. One per install.

- **Customer** — identification type and number, legal name, address, phone,
  one or more billing email addresses, notes. Optionally enriched from the SRI
  public lookup (§6.1).

- **InvoiceTemplate** — a named, reusable recipe bound to a customer. Carries
  an **item strategy**:

  - `ITEMISED` — several lines, each a fixed unit price multiplied by a
    quantity the user supplies at issue time. Suited to billing per site,
    per session or per unit of service.
  - `LUMP_SUM` — a single line whose total the user supplies at issue time.
    Suited to customers who provide the consolidated amount themselves.

  Each line definition holds a **code pattern** and a **description pattern**
  with placeholder tokens resolved against the billing period, for example
  `{PREFIX}-{YY}{MM}-{SUFFIX}` and `... {MONTH_NAME} {YYYY} ...`. This is what
  makes the per-customer coding schemes data rather than code.

- **Invoice** — issue date, billing period, sequential number, access key,
  resolved line items, tax breakdown, totals, lifecycle status, file
  references, and the full SRI exchange log.

  SRI status: `DRAFT → SIGNED → SUBMITTED → RECEIVED → AUTHORIZED`.
  `RETURNED` (DEVUELTA) and `NOT_AUTHORIZED` are **not terminal** — the SRI
  requires resubmission with the *same* access key and sequential, so model
  them as a blocker plus a `resubmit` transition, never as a dead end.

  **Delivery is a second, independent status axis.** The issuer is obliged to
  email the document to the buyer, but the app hands off to an external mail
  client and cannot observe whether the message was sent. Track a
  user-confirmed delivery state with a resend action, separately from SRI
  status.

- **Sequence** — monotonic per `(establishment, emissionPoint, documentType)`.
  Allocation must be transactional; a gap or a reuse is a compliance problem.

Money is **never** `Double` or `Float`. Use `BigDecimal` with explicit scale
and `RoundingMode.HALF_UP`, matching the SRI's two-decimal rules. Write a test
for every rounding boundary you touch.

Professional health services are generally zero-rated for IVA in Ecuador, so
the sensible default tax rate code is 0% — but this is **configurable per
template**, never assumed in code, and the user can change it.

---

## 6. SRI integration

Full technical notes: [`docs/sri-electronic-invoicing.md`](docs/sri-electronic-invoicing.md).

Summary of what the code must get right:

### 6.1 Taxpayer lookup

SRI exposes public REST endpoints used by its own web console to resolve a RUC
to a taxpayer record and its establishments. **These endpoints are not
officially documented or guaranteed stable.** Therefore:

- Treat the lookup as a *convenience*, never a precondition. Manual entry must
  always work, and the app must stay fully usable offline.
- Isolate it behind an interface with a single implementation, so a change in
  the endpoint is a one-file fix.
- Never block invoice issuance on a lookup failure.

### 6.2 Document pipeline

1. **Build** the XML for the current `factura` schema version.
2. **Access key** — 49 digits:
   `ddmmyyyy(8) + docType(2) + issuerRuc(13) + environment(1) + series(6) +
   sequential(9) + numericCode(8) + emissionType(1) + checkDigit(1)`,
   check digit by modulus 11. The key is also the document's filename stem.
3. **Sign** with XAdES-BES using the user's PKCS#12 certificate.
   **Mandatory and counter-intuitive:** RSA-SHA1, SHA-1 digests, 2048-bit key,
   **inclusive** C14N (`REC-xml-c14n-20010315`), lowercase `id="comprobante"`
   on the root, three references. SHA-256 and exclusive C14N are the modern
   defaults and both are **wrong here** — SRI rejects them.
4. **Submit** to `RecepcionComprobantesOffline` (`validarComprobante`).
   `RECIBIDA` means accepted for processing, not valid: errors 39, 52 and 58
   surface later as `NO AUTORIZADO`.
5. **Authorize** via `AutorizacionComprobantesOffline`
   (`autorizacionComprobante`). **This can take up to 24 hours** — design for
   a day, not a spinner. Parse the state leniently (`RECHAZADO` occurs),
   handle multiple `<autorizacion>` elements, and treat `ADVERTENCIA`
   messages as warnings, not failures.
6. **Render** the RIDE PDF from the authorized document.
7. **Persist** request and response XML for every step.

Environments: `1` = test (`celcer.sri.gob.ec`), `2` = production
(`cel.sri.gob.ec`). The environment is a user-visible setting and the UI must
make the current one unmistakable. Documents issued in the test environment
have no legal validity — label them as such everywhere they appear.

**Signing correctness is byte-sensitive.** The approach is *write it already
canonical, then sign what you wrote* — the XML writer emits inclusive-C14N
form directly, so canonicalization is the identity transform on those bytes.
This is why a general-purpose serializer cannot be used: Android's
`XmlSerializer` emits single-quoted attributes, self-closing empty elements,
numeric character references and raw CR, none of which are canonical.

**The bytes signed must be the bytes transmitted.** Never serialize, parse and
re-serialize between signing and sending. Inclusive C14N also makes inherited
namespaces part of the signed octets — a frequent cause of silent failure.
Do not "tidy up" the XML writer or reformat a signed payload without running
the signature tests.

### 6.3 Delivery by email

Files are named `<accessKey>.xml` and `<accessKey>.pdf`. This naming is a
convention, not an SRI rule.

**The XML delivered to the customer is the `<autorizacion>` envelope** — which
wraps the signed comprobante together with the authorization number, date and
state — not the bare signed document.

A `mailto:` URI **cannot carry attachments**. Use it only for a no-attachment
compose intent. To send the RIDE and XML, use `ACTION_SEND_MULTIPLE` with
`EXTRA_EMAIL`, `EXTRA_SUBJECT`, `EXTRA_TEXT` and `EXTRA_STREAM` URIs from a
`FileProvider`, granting read permission to the receiving app. Subject and
body come from a user-editable template with the same placeholder tokens used
elsewhere.

The app hands off to the mail client; it does not send mail itself and holds
no mail credentials.

### 6.4 Certificate handling

- The PKCS#12 file is copied into app-internal storage. It is never written to
  shared storage, never backed up, and never logged.
- The password is encrypted with an Android Keystore-backed key before being
  persisted, or not persisted at all — prompting per use is an acceptable and
  safer default for a low-volume app.
- `androidx.security:security-crypto` is deprecated; use the Keystore API
  directly.
- Exclude app-internal key material from auto-backup in
  `backup_rules.xml` / `data_extraction_rules.xml`.
- Keep the certificate directory under `Context.getNoBackupFilesDir()`. The
  platform guarantees that location's exclusion independently of the rule
  files, which is the belt-and-braces half of the bullet above; consider the
  Room database file for the same location.
- Surface certificate expiry prominently and warn ahead of time.

---

## 7. Debuggability

Debugging a signing-and-submission pipeline on someone else's phone is the
hardest part of operating this app, so observability is a product feature, not
an afterthought.

- **Per-invoice audit trail.** Every generated XML, signed XML, SOAP request
  and SRI response is written to app-internal storage, keyed by access key, and
  viewable in the app.
- **Diagnostics screen.** A plain-language event log with a "share diagnostics"
  action that produces a redacted bundle. Redaction strips identification
  numbers, names, addresses and emails by default.
- **No sensitive data in logcat, ever** — no certificate bytes, no passwords,
  no full identification numbers. Mask to the last three digits.
- **Named failure points.** Each pipeline stage reports a distinct, typed error
  so a user-reported "it didn't work" maps to one stage.
- Release builds keep crash-readable stack traces: keep mapping files as build
  artifacts.

---

## 8. In-app updates via GitHub Releases

- Poll the public `releases/latest` endpoint of the project's GitHub
  repository. Unauthenticated; respect rate limits; fail silently on network
  errors.
- Compare against `versionCode`; treat the release tag as the display version.
- Download the APK asset to app-internal storage, verify its size and digest
  against the release metadata, then install through `PackageInstaller`.
- Requires `REQUEST_INSTALL_PACKAGES`. Ask for it at the moment of updating,
  with a clear explanation, not at first launch.
- Checking for updates is **opt-in and interruption-free**: never block the
  invoicing flow behind an update prompt.
- Releases are signed with a consistent key; the keystore and its credentials
  live outside the repository and are injected from CI secrets or
  `local.properties`.

---

## 9. Working agreements for agents

1. **Read before writing.** Match the conventions already in the file you are
   editing; do not restyle surrounding code.
2. **Stay in scope.** Implement what was asked. Raise anything else as a note,
   do not fold it into the change.
3. **Verify your own work.** Run `./gradlew` builds and the relevant tests
   before reporting completion. Report failures with their output; never claim
   a green build you did not observe.
4. **Test the arithmetic and the strings.** Access key, check digit, rounding,
   canonical form, signature output and placeholder resolution get unit tests
   in `:core:invoicing`, where they run in milliseconds with no emulator.
   **Sequence allocation is the exception** — the allocator is Room-backed and
   lives in `:app`, so its concurrency and crash-interruption tests live there
   too. Signature output is additionally checked by an independent verifier,
   never only by the code that produced it.
5. **Ask when the answer changes the design.** A dependency that needs
   approval (§2.4), an irreversible data-model decision, or an ambiguous SRI
   rule is worth one question. Routine judgement calls are yours.
6. **Prefer deleting to adding.** The smallest app that issues a correct
   invoice is the goal.
7. Never introduce a hardcoded customer, template, amount or code scheme to
   make something "work" — see §2.2.

---

## 10. Build and test

```bash
./gradlew assembleDebug          # build the debug APK
./gradlew :core:invoicing:test   # fast JVM tests for the invoicing engine
./gradlew test                   # all unit tests
./gradlew connectedAndroidTest   # instrumented tests (device or emulator)
./gradlew lint                   # Android Lint
./gradlew verifyRelease          # release-artifact gates: size baseline, keep rules, release-dex URL hosts
./gradlew --stop                 # stop daemons if the build misbehaves
```

Configuration cache is enabled in `gradle.properties`. If a change breaks
configuration caching, fix the cause rather than disabling the cache.

Every build gate attaches to `./gradlew check`, except the release-artifact
gates, which live on `verifyRelease`. [`docs/build-invariants.md`](docs/build-invariants.md)
says what each gate enforces and, as importantly, what it cannot see.

---

## 11. Licence

GNU General Public License v3.0 — see [`LICENSE`](LICENSE). New source files
carry the short GPLv3 header. Any dependency added must be licence-compatible
with GPLv3.
