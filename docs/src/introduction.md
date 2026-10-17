# Introduction

Crisis Network is a messaging system for situations where the usual infrastructure is gone.
Phones exchange messages directly over Bluetooth Low Energy, keep them in a local SQLite
database, and synchronise with a server when internet access returns.

The shared Rust core lives in `../../core`. Platform apps (Android, Apple, Desktop) are built on
top of it through the UniFFI bindings of the `ffi` crate.

This book contains:

* the [architecture](architecture.md) of the core,
* the [protocol specification](protocol/v1.md),
* the [threat model](threat-model.md),
* the decision records (ADRs) explaining why things are the way they are.
