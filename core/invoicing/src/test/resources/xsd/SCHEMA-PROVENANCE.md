# Vendored schema provenance

This directory holds the schema the XSD gate (`XsdGate`, `XsdGateTest`) validates against.
The vendored copy is the specification's, byte for byte. It is never edited and no facet is
relaxed to make this project's output validate; a mismatch is the document's problem.

## factura_V1.1.0.xsd

| Field | Value |
| --- | --- |
| Source | Downloaded by the repository owner from the SRI's electronic-invoicing page. URL not recorded. |
| Retrieved | 2026-10-27 |
| SHA-256 | `bd7d795f44cbbfecef9957444d3cc288e67cd28329454fff5540875c7d21288b` |
| Vendored as | `factura_V1.1.0.xsd` (the original is named `Factura_V1.1.0.xsd`) |

Check it with `shasum -a 256 core/invoicing/src/test/resources/xsd/factura_V1.1.0.xsd`.

### Differs from the copy research validated against

Phase 2 research validated a different file, recorded with the digest prefix `62db9bf0...eb8b`.
The owner supplied this copy instead and accepted it in its place. Findings from research that
were measured on the other copy do not automatically carry over to this one.

### No signature element inside `<factura>`

This revision declares no `ds:Signature` inside `<factura>`: its content model ends at
`infoAdicional`. A signed document therefore cannot validate against it. For that reason the gate
validates the UNSIGNED canonical document (`CanonicalDocument.octets()`), and the signature is
covered by the two independent verifiers (JSR-105 and xades4j) instead. `XsdGateTest` records the
consequence in a test of its own so that a change of scope is a deliberate act.

### Element set differs from the 2.x schemas

`otrosRubrosTerceros` is absent from this revision and exists only in the 2.x schemas, so the
writer does not emit it.

## xmldsig-core-schema.xsd: deliberately not vendored

The factura schema imports `http://www.w3.org/2000/09/xmldsig#` through the relative
`schemaLocation="xmldsig-core-schema.xsd"` but never references a `ds:` component. With the real
JAXP `SchemaFactory` the unresolved import is reported as a single warning
(`schema_reference.4`) and the schema compiles. `XsdGateTest` asserts that this is the only
warning, so if a later revision starts using `ds:` the gate fails at compilation instead of
silently validating less. The file was not supplied, and it is not taken from the network or from
a jar.

## Redistribution

Whether either schema may be redistributed in a public repository is an open question carried
forward to the first public release. The `docs/sri/` copies are gitignored for that reason; this
vendored test resource is the single tracked copy.
