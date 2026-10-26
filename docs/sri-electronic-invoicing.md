# SRI electronic invoicing — technical reference

Working notes on Ecuador's SRI electronic receipt ("comprobante electrónico")
scheme, as it applies to this project.

> **Status of this document.** Everything here is a working summary, not a
> specification. The authoritative source is the SRI's *Ficha Técnica de
> Comprobantes Electrónicos* and the published XSD schemas. Where this
> document and the SRI disagree, the SRI is right — fix this document.
>
> **Revision history.** First written from a mix of knowledge and third-party
> sources. Subsequently corrected against the **official Ficha Técnica
> Offline v2.34 (27/07/2026)** read in full, plus SRI Resolutions
> NAC-DGERCGC25-00000014 and NAC-DGERCGC25-00000017. Statements sourced from
> the Ficha are marked **[Ficha v2.34]**. Anything still unconfirmed is marked
> **UNVERIFIED** and must be verified before code depends on it.
>
> Where vendor blogs and the Ficha disagree, the Ficha wins. Several widely
> circulated third-party error tables were found to contradict it.

---

## 1. Document types

| Code | Document |
| --- | --- |
| `01` | Factura |
| `03` | Liquidación de compra de bienes y prestación de servicios |
| `04` | Nota de crédito |
| `05` | Nota de débito |
| `06` | Guía de remisión |
| `07` | Comprobante de retención |

This project issues `01` only. The model should not make that assumption
permanent — credit notes (`04`) are the first likely extension, since they are
how an issued invoice gets corrected.

## 2. Environments

| Value | Environment | Host |
| --- | --- | --- |
| `1` | Test / certification (*pruebas*) | `celcer.sri.gob.ec` |
| `2` | Production | `cel.sri.gob.ec` |

The environment code is embedded in the access key *and* sent in
`infoTributaria`. The two must agree, and both must match the host the
document is submitted to.

Documents issued in the test environment have **no legal validity**. The
environment must be activated in the issuer's SRI online account before
documents are accepted.

## 3. Emission type

| Value | Meaning |
| --- | --- |
| `1` | Normal — **the only value that exists** |

**[Ficha v2.34]** The offline scheme has only emission type `1`. Contingency
access keys were removed in specification v2.0. Any blog, tutorial or library
that describes contingency emission is describing a scheme that no longer
exists. Do not implement it.

## 4. Access key (`claveAcceso`)

49 numeric digits. It identifies the document, is printed on the RIDE, and is
the filename stem for the deliverables.

| Length | Field | Notes |
| --- | --- | --- |
| 8 | Issue date | `ddmmyyyy` |
| 2 | Document type | §1 |
| 13 | Issuer RUC | |
| 1 | Environment | §2 |
| 6 | Series | establishment (3) + emission point (3) |
| 9 | Sequential | zero-padded |
| 8 | Numeric code | issuer-defined — see below |
| 1 | Emission type | always `1` (§3) |
| 1 | Check digit | modulus 11 over the preceding 48 digits |

### Check digit — modulus 11

Weights `2,3,4,5,6,7` cycle right-to-left across the 48 digits. Sum the
products, take `11 - (sum mod 11)`. A result of `11` becomes `0`; a result of
`10` becomes `1`.

The `10 → 1` and `11 → 0` substitutions are the two most commonly
mis-implemented rules in the scheme. Both get an explicit unit test.

> **Trap.** The specification's own **Annex 3 sample access key carries an
> incorrect check digit** (verified by calculation). Spec samples cannot be
> used as golden test vectors without recomputing the digit first. Generate
> golden vectors by computation and cross-check with an independent
> implementation instead.

### Numeric code

The 8-digit numeric code is chosen by the issuer. Derive it deterministically
from **RUC + series + sequential + issue date only**.

**Never derive it from document content** (amounts, line items, customer).
After a rejection the SRI requires resubmission with the *same* access key and
sequential; if the numeric code depended on content, correcting the document
would change the key and make lawful resubmission impossible.

## 5. XML structure (factura)

**[Ficha v2.34]** Schema version **1.1.0** is appropriate for a plain invoice.

Top-level sections:

- `infoTributaria` — environment, emission type, legal name, trade name,
  issuer RUC, access key, document type code, establishment, emission point,
  sequential, head-office address, and regime legends where applicable.
- `infoFactura` — issue date, establishment address, buyer identification type
  and number, buyer legal name, totals, `totalConImpuestos`, and `pagos`
  (payment methods — **mandatory**).
- `detalles` — one `detalle` per line.
- `infoAdicional` — free-form name/value pairs. **Capped at 15 entries.**

### Mandatory fields that are easy to miss

- **`<contribuyenteRimpe>`** — the RIMPE regime legend. Exact text and
  position are specified in **Annex 22**. Required when the issuer is under
  that regime.
- **"RUC Proveedor" in `infoAdicional`** — introduced by **Annex 26**
  (Resolution NAC-DGERCGC26-00000027, July 2026) for issuers using
  third-party invoicing systems, mandatory 60 days after publication.
  **UNVERIFIED:** whether a self-distributed open-source app counts as a
  "proveedor" for this purpose. No source resolves it. Support the field as
  configurable and settle applicability before production.

### Decimals

Monetary values carry two decimals. Quantities and unit prices may carry up to
six **under schema 1.1.0 specifically**.

The Ficha documents error **52 "Error en diferencias"** for totals that do not
reconcile, but **publishes no tolerance**. An earlier claim in this document
that a one-cent discrepancy is rejected was not found in the Ficha and has
been removed. Compute exactly with `BigDecimal` and HALF_UP; do not rely on an
assumed tolerance in either direction.

### Tax codes

**[Ficha v2.34]** `codigo` `2` is IVA. Current `codigoPorcentaje` values
include **0% = code `0`** and **15% = code `4`**. Historical codes remain
valid for past periods. Read the catalogue from the spec rather than
hardcoding a rate.

Professional health services are generally zero-rated, which makes the 0% code
the sensible default for this project's users, but it is a configurable
template setting, not an assumption baked into code. Which code applies
(0%, Exento, or No objeto) is a **tax-advice question**, not an engineering
one — **UNVERIFIED**, and the user should confirm it with their accountant.

## 6. Signature — XAdES-BES

**[Ficha v2.34] — these are mandatory and non-obvious. Getting any of them
wrong costs days, because SRI returns only an opaque "Firma inválida".**

| Parameter | Required value |
| --- | --- |
| Signature algorithm | **RSA-SHA1** |
| Digest algorithm | **SHA-1** |
| Key size | **2048-bit** |
| Canonicalization | **Inclusive C14N** — `REC-xml-c14n-20010315` |
| Root element attribute | lowercase **`id="comprobante"`** |
| References | **three**: SignedProperties, the KeyInfo certificate, and `#comprobante` |

> **Do not modernise.** SHA-256 and exclusive C14N are the natural modern
> defaults and both are wrong here. SRI validates with its own library and
> will reject them.

Practical notes:

- Android does **not** ship `javax.xml.crypto.dsig`, and has no C14N
  implementation. The signature must be hand-built or come from a library
  that genuinely works on Android.
- **The bytes signed must be exactly the bytes transmitted.** Do not
  serialize, parse and re-serialize between signing and sending.
- Inclusive C14N makes **inherited namespaces** part of the signed octets —
  a frequent cause of silent signature failure.
- Watch the `BigInteger` modulus sign byte when encoding key material, and
  avoid `android.util.Base64.DEFAULT`, which inserts line breaks.
- `SigningTime` is checked against SRI's clock; device skew produces
  rejections (see §7).
- `java.security.KeyStore.getInstance("PKCS12")` may load the certificate
  without a third-party provider — **UNVERIFIED** for real Ecuadorian CA
  files on API 31+.
- **Certificate-dependent failures exist.** Issuer-DN encoding is one: a CA
  support page reports Java signers mishandling `organizationIdentifier`
  (OID 2.5.4.97) receiving error 39 (single-source, LOW-MEDIUM confidence).
  Key usage, the RUC embedded in the certificate, and OCSP/chain validation
  are also certificate-dependent. **A self-signed test certificate cannot
  surface any of these** — it remains the right tool for the inner
  development loop, but a spike with the real certificate against `celcer` is
  required to close the gap.

## 7. Web services

SOAP, same paths on both hosts:

```
https://{host}/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl
https://{host}/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl
```

| Service | Operation | Input | Output |
| --- | --- | --- | --- |
| Recepción | `validarComprobante` | base64 of the signed XML | `RECIBIDA` or `DEVUELTA` with messages |
| Autorización | `autorizacionComprobante` | access key | authorization state and messages |

### Response handling — more complex than it looks

**[Ficha v2.34]** The authorization state is **not** a closed set of
`AUTORIZADO` / `NO AUTORIZADO` / empty:

- **`RECHAZADO`** appears in the official sample even though the prose says
  `NO AUTORIZADO`. **Parse the state string leniently.**
- **One query can return several `<autorizacion>` elements.** Handle a list.
- **`ADVERTENCIA` messages accompany successful authorizations** (codes 60,
  59, 62, 68). In the test environment **every** AUTORIZADO carries warning
  60. A warning is not a failure — do not treat the presence of messages as
  rejection.
- **Errors 39, 52 and 58 arrive as `RECIBIDA` followed by `NO AUTORIZADO`**,
  not as `DEVUELTA`. Acceptance at reception proves nothing.
- `DEVUELTA` documents are **not stored** by SRI.

### Timing and retry

- **[Ficha v2.34]** Authorization can take **up to 24 hours** after
  `RECIBIDA`. This is not a few seconds. Design the UI and the polling
  strategy around a day, not a spinner.
- Key error codes: **43** = access key already registered; **45** = sequential
  already registered; **50** = internal server error; **70** = key is in
  processing — **the Ficha says do not resend for up to 24 hours**.
- Before any resend, query `autorizacionComprobante` by key. Resend the
  **stored signed bytes**; never re-sign.
- Device clock skew causes "firma posterior a la actual". SRI servers use NTP.
  Issue dates must use a fixed **`America/Guayaquil`** clock.
- Submit in **ascending sequential order** — the Ficha (§9.18) requires
  chronological order.

> **Several widely circulated vendor error tables contradict the Ficha** on
> codes 35, 39, 43, 45, 50, 52, 60 and 70. Use the Ficha.

A full SOAP stack is unnecessary for two operations with fixed shapes. Build
the envelope as a string template and parse with `XmlPullParser` (on Android)
or a JAXP parser (in the JVM module).

## 7b. Regulatory changes effective 2026

Both confirmed from the primary resolution texts:

- **The four-business-day transmission grace was removed**, effective
  **2026-01-01** (Res. NAC-DGERCGC25-00000014, reformatory provision; date set
  by Res. 17 Art. 1(f)). Transmission is now required at the moment of
  generation. **Offline sign-and-queue-for-days is no longer lawful.**
  Backdating `fechaEmision` to a period end also risks error 65. The
  enforcement tolerance behind error 65 is **UNVERIFIED** — test it in
  `celcer`.
- **Online annulment of an authorized factura is possible only until the 7th
  of the following month** (Res. 14 Art. 3, reformed from day 10 by Res. 17).
  After that a nota de crédito is the only route. The 12-month NC ceiling was
  removed. **Consumidor final invoices can never be corrected by NC.**

## 8. Taxpayer lookup (RUC)

SRI's online console resolves taxpayer data through a public REST endpoint
that is **not announced or documented** as an API, but is reachable without
authentication:

```
GET https://srienlinea.sri.gob.ec/sri-catastro-sujeto-servicio-internet/rest/ConsolidadoContribuyente/obtenerPorNumerosRuc?&ruc=<13-digit-RUC>
```

Note the literal `?&` before `ruc` — that is how the console issues the
request, and it is reproduced here verbatim rather than tidied, because an
undocumented endpoint may be sensitive to the exact query string.

A companion establishments lookup exists under the same service root.

### Response shape

Verified against the live endpoint. A **JSON array**, one element per RUC
queried. Field names below are real; all values are placeholders.

```json
[
  {
    "numeroRuc": "9999999999001",
    "razonSocial": "TEST TAXPAYER S.A.",
    "estadoContribuyenteRuc": "ACTIVO",
    "actividadEconomicaPrincipal": "...",
    "tipoContribuyente": "SOCIEDAD",
    "regimen": "GENERAL",
    "categoria": null,
    "obligadoLlevarContabilidad": "SI",
    "agenteRetencion": "...",
    "contribuyenteEspecial": "...",
    "informacionFechasContribuyente": {
      "fechaInicioActividades": "...",
      "fechaCese": "",
      "fechaReinicioActividades": "",
      "fechaActualizacion": "..."
    },
    "representantesLegales": [
      { "identificacion": "...", "nombre": "..." }
    ],
    "motivoCancelacionSuspension": null,
    "contribuyenteFantasma": "NO",
    "transaccionesInexistente": "NO"
  }
]
```

What this means for the app:

- **`razonSocial` and `estadoContribuyenteRuc` are the payoff.** They prefill
  the customer's legal name and answer "is this RUC active?" — the two things
  worth looking up.
- **There is no address, phone or email in this response.** Those fields are
  manual entry, or come from the establishments endpoint. Do not design the
  customer form around the lookup filling it completely.
- `categoria` and `motivoCancelacionSuspension` are `null` for an ordinary
  active taxpayer, and `fechaCese` / `fechaReinicioActividades` are empty
  strings rather than absent or null. **Three different representations of
  "no value" appear in one payload** — parse every field as optional and
  normalize empty strings to null.
- `obligadoLlevarContabilidad`, `contribuyenteEspecial` and `regimen` are
  useful when the lookup is used on the *issuer's own* RUC during setup: they
  feed the legends the document must carry.
- `contribuyenteFantasma` and `transaccionesInexistente` flag a taxpayer SRI
  considers non-existent or fraudulent. Worth surfacing as a warning when
  adding a customer.
- `estadoContribuyenteRuc` is a string, not a boolean. Do not hardcode a
  closed set of values; show whatever SRI returns and treat anything other
  than the active value as "verify this".

### Access note

Direct `curl` from a sandboxed shell may time out against this host while the
same request succeeds from a normal client. Treat a timeout in a dev
environment as an environment problem, not evidence that the endpoint is
down.

**Undocumented and unsupported.** SRI publishes no contract for this
endpoint: no versioning promise, no deprecation notice, no stable response
schema. It can change or disappear without warning. Consequences for this
project:

- The lookup is a *convenience* that pre-fills a form. It is never a
  precondition for anything.
- Manual entry of every customer field must always be available and must
  never be harder to reach than the lookup.
- Failures are non-blocking and quiet: a lookup that breaks must never
  prevent an invoice from being issued, and must never produce an error
  dialog the user has to dismiss before continuing.
- Keep it behind a single interface with one implementation, so replacing or
  removing it is a one-file change.
- Parse defensively. Treat every field as optional; never assume a key exists.
- Do not log the response — it contains taxpayer identity data.

Identification types used on the buyer side: RUC (`04`), cédula (`05`),
pasaporte (`06`), *consumidor final* (`07`), and others for foreign buyers.
`07` has a fixed identification number of thirteen `9`s and an amount ceiling.

## 9. RIDE

The human-readable PDF representation of the authorized document. It must
carry the access key, the authorization number and date, the issuer and buyer
data, the line items, and the totals.

`android.graphics.pdf.PdfDocument` with a `Canvas` is sufficient and adds no
dependency. The access key is additionally rendered as a QR or barcode —
ZXing `core` alone covers generation.

## 10. File naming and delivery

- `<accessKey>.xml`
- `<accessKey>.pdf` — the RIDE

**This naming is a widely used convention, not an SRI rule.**

**The XML delivered to the customer should be the `<autorizacion>` envelope**
returned by the authorization service — which wraps the signed comprobante
together with the authorization number, date and state — not the bare signed
document.

**[Ficha §4.7]** The issuer is **obliged to email the receipt to the buyer**,
and Res. 14 additionally requires informing the receiver of status changes
such as an annulment. Since the app hands off to an external mail client it
cannot observe whether the message was actually sent, so it must track
delivery as a **separate status axis** from SRI status, with a
user-confirmed "sent" state and a resend action.

A `mailto:` URI cannot carry attachments. Delivery uses `ACTION_SEND_MULTIPLE`
with `FileProvider` URIs, `ClipData` set for permission grants, and attention
to how the file name reaches the mail client.

## 11. Open questions

Resolved by this revision: schema version (1.1.0), IVA catalogue, emission
types (only `1`), decimal scales.

Still open — resolve in the `celcer` test environment before production:

1. SRI behaviour when resending an authorized or `NO AUTORIZADO` document
   with the same access key.
2. Whether error 43 precedes 45, and the meaning of an empty authorization
   result.
3. The real tolerance window behind error 65 (extemporaneous issue date).
4. Whether Annex 26 "RUC Proveedor" applies to a self-distributed
   open-source app.
5. Whether real CA-issued `.p12` files load through Android's PKCS12
   keystore on API 31+.
6. Any published rounding tolerance behind error 52.
7. Document retention obligations (7 years is reported; Art. 41 text
   unconfirmed).
8. Which IVA code applies to the issuer's services — a tax-advice question.

## 12. Sources

Authoritative, read directly:

- **SRI Ficha Técnica Offline v2.34 (27/07/2026)** — the specification.
- **Resolution NAC-DGERCGC25-00000014** and **NAC-DGERCGC25-00000017** —
  2026 transmission and annulment changes.
- **Resolution NAC-DGERCGC26-00000027** — Annex 26.
- SRI — *Comprobantes electrónicos*: <https://www.sri.gob.ec/facturacion-electronica>

Third-party references used for orientation only, **not** authoritative, and
found to contradict the Ficha on several error codes:

- [Open Factura — access keys](https://www.mintlify.com/miguelangarano/open-factura/concepts/access-keys)
- [Open Factura — SRI endpoints](https://www.mintlify.com/miguelangarano/open-factura/concepts/sri-endpoints)
- [Fonoa — Practical guide to e-invoicing in Ecuador](https://www.fonoa.com/resources/blog/practical-guide-to-e-invoicing-in-ecuador)
