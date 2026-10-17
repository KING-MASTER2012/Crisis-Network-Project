# ADR-0002: Identity and cryptography

**Status:** accepted

## Decision

* A node's identity is an Ed25519 key pair derived from a 32-byte seed; its public key is the node id.
* Primitives come from fixed, proven libraries: Ed25519, X25519, HKDF-SHA256, ChaCha20-Poly1305.
  We do not invent primitives.
* Direct messages use a sealed box to one recipient. Envelope signatures bind the sender.
* A contact card is a shareable, self-signed identity card, meant for QR codes.
* The seed is kept in the platform's secure store through the `KeyStore` interface; the core never
  writes it to disk itself.

## Consequences

No forward secrecy (see the [threat model](../threat-model.md)). Every signature, hash and KDF
output uses its own domain-separation tag.
