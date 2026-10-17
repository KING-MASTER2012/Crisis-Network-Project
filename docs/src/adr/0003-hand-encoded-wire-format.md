# ADR-0003: A hand-encoded wire format

**Status:** accepted

## Decision

The wire format is encoded by hand, with fixed layouts, instead of with a general serialisation
framework. Serde is used only in helper and CLI output layers.

## Consequences

* The encoding is canonical: whatever parses re-encodes to the same bytes, which signatures and
  message ids rely on.
* Strict, small decoders with explicit limits are easy to fuzz and to specify (see
  [Protocol v1](../protocol/v1.md)).
