# ADR-0001: A sans-IO core

**Status:** accepted

## Context

The core runs on Android, iOS and desktop, over radios the core cannot control, and must be
testable without hardware.

## Decision

`proto`, `mesh`, `engine` and `storage` perform no network I/O and never read the clock. The
platform passes in events and the current time (`now_ms`) and executes the returned actions.

## Consequences

* The whole network can be simulated deterministically (`sim`), with one virtual clock.
* The platform layers stay thin.
* Every time-dependent operation takes an explicit time argument.
