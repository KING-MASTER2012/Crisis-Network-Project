# Threat model

## Assets

* The integrity and authorship of messages.
* The confidentiality of Direct messages.
* The availability of the mesh and of each device's storage.

## Attackers

An attacker may be a nearby peer, a relaying node, or anyone able to inject radio traffic.
Peers are untrusted by default.

## Mitigations

* **Forgery.** Every envelope is signed with Ed25519 and verified strictly before it is stored
  or forwarded. Keys of small order and non-reduced signature scalars are rejected.
* **Confidentiality.** Direct messages use a sealed box to a single recipient (X25519,
  HKDF-SHA256, ChaCha20-Poly1305). Tampering is detected.
* **Clock manipulation.** `created_at_ms` is untrusted. Expiry is counted from the receive time.
* **Flooding.** Per-peer rate limits, a penalty score with bans, a per-sender rate limit, local
  sender blocking, a hop cap, bounded tables and strict reassembly memory limits.
* **Storage exhaustion.** A byte budget with deterministic eviction. Inbound messages are pinned
  against eviction only up to a fixed budget, so strangers cannot fill the store with unevictable data.
* **Resurrection.** Tombstones keep a deleted or expired message from returning from another node.

## Known limitations

* `hops_left` is outside the signature, so a relaying node can *increase* it. This is a
  denial-of-service (flooding) risk, not an integrity risk; the mesh caps inflated hop counts.
* Direct messages have no forward secrecy: compromise of a recipient's key reveals past messages.
* Broadcast messages are public by design.
* Metadata (sender, recipient, timing) is visible to relaying nodes.
