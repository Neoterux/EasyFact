# EasyFact

An offline-first Android app for issuing Ecuadorian electronic invoices
(*comprobantes electrónicos*) to the SRI, built for independent professionals
who bill a small, stable set of recurring customers every month.

> **Status: early development.** Nothing here is production-ready yet. The
> repository currently contains the project skeleton and its contributor
> guides.

## Why

Issuing a recurring monthly invoice through general-purpose billing software
means re-entering the same data every month, in a UI designed for businesses
with hundreds of customers. This app inverts that: you define a customer and a
template once, and each month's invoice becomes a couple of taps — issue,
sign, authorize, send.

## Planned features

- **Certificate management** — load a PKCS#12 signing certificate, stored in
  app-private storage and protected by the Android Keystore.
- **Customer directory** — identification number, legal name, address, phone
  and billing email addresses, optionally pre-filled from SRI's public
  taxpayer lookup.
- **Invoice templates** — reusable per-customer recipes with two line-item
  strategies: itemised lines (fixed unit price × quantity) or a single
  consolidated amount. Item codes and descriptions are generated from
  user-defined patterns with billing-period placeholders.
- **Full SRI pipeline** — XML generation, XAdES-BES signing, submission,
  authorization polling, and RIDE (PDF) rendering.
- **Pre-filled email delivery** — hands the RIDE and XML to your own mail
  client with a subject and body from an editable template. Files are named
  with the document's access key.
- **On-device diagnostics** — a complete, auditable record of every document
  and every SRI exchange, with redacted export for troubleshooting.
- **Self-update** — checks GitHub Releases and installs updates on request.

## Design goals

- **Offline-first.** Network access is required only to submit and authorize.
  Everything else works without it.
- **Accessible by default.** The intended user is a non-technical
  professional; large touch targets, high contrast and short flows take
  priority over configurability.
- **Private by construction.** No telemetry, no analytics, no crash
  reporting, no remote logging, no account. All data stays on the device.
- **Auditable.** Every generated and received document is retained locally and
  inspectable.

## Requirements

- Android 12 (API 31) or newer.
- A valid electronic signing certificate (`.p12`) from an accredited
  Ecuadorian certification authority.
- An SRI account with electronic invoicing enabled for the chosen
  environment.

## Building

```bash
./gradlew assembleDebug
./gradlew test
```

See [`AGENTS.md`](AGENTS.md) §10 for the full command list.

## Contributing

Read [`AGENTS.md`](AGENTS.md) before opening a pull request. It covers the
architecture, the coding and commit conventions, the dependency policy, and
the privacy rules that apply to everything committed to this public
repository — in particular: **no real taxpayer data, names, contact details or
key material in any file, including tests and examples.**

Technical notes on the SRI scheme itself are in
[`docs/sri-electronic-invoicing.md`](docs/sri-electronic-invoicing.md).

## Disclaimer

This project is not affiliated with, endorsed by, or supported by the Servicio
de Rentas Internas. Electronic invoices issued with it are the sole
responsibility of the person issuing them. Verify any generated document
against the SRI's official specification and your own tax obligations.

## Licence

[GNU General Public License v3.0](LICENSE).
