# Shared test data

`appcodec.json` pins the behaviour of the app conventions in `core/crates/appcodec`: text
cleaning, the announcement payload, the contact-card text, nicknames, lifetimes, byte budgets,
delivery status and short ids. Hexadecimal strings are bytes; texts are JSON strings (non-ASCII
characters are written as `\uXXXX` escapes).

The Rust tests read the file. A client that still carries its own implementation of one of
these rules (the Android `messaging` package, for example) should run the same file against it,
so that a client and the core can never disagree silently.

The expected values were produced by an independent implementation of the Android client's
rules, not by the Rust code under test.
