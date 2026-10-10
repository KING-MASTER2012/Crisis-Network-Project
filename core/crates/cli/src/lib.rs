//! # cli
//!
//! The library behind the `crisis` binary. It offers three tools:
//!
//! * `crisis simulate`: runs the deterministic network simulator and prints a summary
//!   or a JSON report;
//! * `crisis identity`: creates (or shows) an identity kept in a key file;
//! * `crisis node`: an interactive test node that talks to other nodes over UDP, a
//!   stand-in for Bluetooth that lets the core be exercised on a laptop.
//!
//! Everything writes to a caller-supplied [`std::io::Write`], so the commands can be
//! tested without a terminal.
#![forbid(unsafe_code)]

mod args;
mod error;
mod identity_cmd;
mod keystore;
mod node;
mod repl;
mod simulate_cmd;

use std::io::Write;

pub use args::{Command, IdentityOptions, NodeOptions, SimulateOptions, parse};
pub use error::CliError;
pub use keystore::FileKeyStore;
pub use node::UdpNode;
pub use repl::{NodeCommand, describe, parse_command};

/// Runs the command given by `args` (the program name excluded) and writes its output to `out`.
pub fn run(args: &[String], out: &mut dyn Write) -> Result<(), CliError> {
    match args::parse(args)? {
        Command::Help => {
            out.write_all(args::HELP.as_bytes())?;
            Ok(())
        }
        Command::Simulate(options) => simulate_cmd::run(&options, out),
        Command::Identity(options) => identity_cmd::run(&options, out),
        Command::Node(options) => repl::run_node(&options, out),
    }
}
