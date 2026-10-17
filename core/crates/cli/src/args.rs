//! Command-line parsing. Hand-written on purpose: the grammar is tiny and this keeps
//! the dependency list short.

use std::collections::{BTreeMap, BTreeSet};
use std::net::SocketAddr;
use std::path::PathBuf;
use std::str::FromStr;

use crate::error::CliError;

/// The text printed by `crisis help`.
pub const HELP: &str = "\
crisis: command-line tools for the Crisis Network core

USAGE:
    crisis simulate [OPTIONS]    run the network simulator
    crisis identity [OPTIONS]    create or show an identity
    crisis node [OPTIONS]        run an interactive UDP test node
    crisis help                  show this text

SIMULATE OPTIONS:
    --scenario NAME     line, ring, star, grid, crowd or partition   [crowd]
    --nodes N           number of nodes                              [20]
    --messages N        number of messages                           [10]
    --seed N            seed of the whole run                        [1]
    --loss N            packet loss, per thousand                    [0]
    --latency MS        one-way link delay                           [20]
    --jitter MS         extra random delay                           [10]
    --mtu BYTES         link packet size                             [247]
    --settle MS         time to keep running after the last message  [60000]
    --direct N          share of direct messages, per thousand       [0]
    --resync MS         how often neighbours re-offer their log      [mesh default]
    --json              print the full report as JSON

IDENTITY OPTIONS:
    --file PATH         key file, created when missing               [crisis-identity.key]

NODE OPTIONS:
    --listen ADDR       UDP address to listen on                     [127.0.0.1:0]
    --peer ADDR         a neighbour to talk to (repeatable)
    --identity PATH     key file, created when missing               [crisis-identity.key]
    --db PATH           database file                                [in memory]
    --mtu BYTES         link packet size                             [200]

NODE COMMANDS (type them on standard input):
    broadcast TEXT      announce TEXT to everyone
    send NODE TEXT      send TEXT to a contact (NODE is a hex prefix of its id)
    card                show this node's contact card
    contact CARD        remember a contact card (hex)
    contacts            list known contacts
    history             show the conversation history
    peers               list connected neighbours
    stats               show mesh counters
    help                show the command list
    quit                stop the node

The identity key file is stored unprotected: it is meant for testing only.
";

/// A parsed command line.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Command {
    /// Print the usage text.
    Help,
    /// Run the simulator.
    Simulate(SimulateOptions),
    /// Create or show an identity.
    Identity(IdentityOptions),
    /// Run an interactive UDP node.
    Node(NodeOptions),
}

/// Options of `crisis simulate`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SimulateOptions {
    /// Scenario name.
    pub scenario: String,
    /// Number of nodes.
    pub nodes: usize,
    /// Number of messages.
    pub messages: usize,
    /// Seed of the run.
    pub seed: u64,
    /// Packet loss, per thousand.
    pub loss_permille: u32,
    /// One-way link delay in milliseconds.
    pub latency_ms: u64,
    /// Extra random delay in milliseconds.
    pub jitter_ms: u64,
    /// Link packet size in bytes.
    pub mtu: usize,
    /// Time to keep running after the last message, in milliseconds.
    pub settle_ms: u64,
    /// Share of direct messages, per thousand.
    pub direct_permille: u32,
    /// Override of the mesh re-offer interval, in milliseconds.
    pub resync_ms: Option<u64>,
    /// Print JSON instead of a summary.
    pub json: bool,
}

/// Options of `crisis identity`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct IdentityOptions {
    /// The key file.
    pub file: PathBuf,
}

/// Options of `crisis node`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NodeOptions {
    /// UDP address to listen on.
    pub listen: SocketAddr,
    /// Neighbours to talk to.
    pub peers: Vec<SocketAddr>,
    /// The key file.
    pub identity: PathBuf,
    /// Database file; in memory when absent.
    pub db: Option<PathBuf>,
    /// Link packet size in bytes.
    pub mtu: usize,
}

fn usage(message: String) -> CliError {
    CliError::Usage(message)
}

/// Options found on a command line.
#[derive(Default)]
struct Flags {
    values: BTreeMap<String, Vec<String>>,
    switches: BTreeSet<String>,
}

impl Flags {
    fn last(&self, name: &str) -> Option<&str> {
        self.values
            .get(name)
            .and_then(|v| v.last())
            .map(String::as_str)
    }

    fn all(&self, name: &str) -> &[String] {
        match self.values.get(name) {
            Some(values) => values.as_slice(),
            None => &[],
        }
    }

    fn has(&self, name: &str) -> bool {
        self.switches.contains(name)
    }

    fn parsed<T: FromStr>(&self, name: &str, default: T) -> Result<T, CliError> {
        match self.last(name) {
            None => Ok(default),
            Some(text) => text
                .parse::<T>()
                .map_err(|_| usage(format!("invalid value `{text}` for --{name}"))),
        }
    }

    fn parsed_opt<T: FromStr>(&self, name: &str) -> Result<Option<T>, CliError> {
        self.last(name)
            .map(|text| {
                text.parse::<T>()
                    .map_err(|_| usage(format!("invalid value `{text}` for --{name}")))
            })
            .transpose()
    }
}

fn split_flags(
    args: &[String],
    value_flags: &[&str],
    switch_flags: &[&str],
) -> Result<Flags, CliError> {
    let mut flags = Flags::default();
    let mut iter = args.iter();
    while let Some(arg) = iter.next() {
        let Some(body) = arg.strip_prefix("--") else {
            return Err(usage(format!("unexpected argument `{arg}`")));
        };
        let (name, inline) = match body.split_once('=') {
            Some((name, value)) => (name, Some(value.to_string())),
            None => (body, None),
        };
        if switch_flags.contains(&name) {
            if inline.is_some() {
                return Err(usage(format!("--{name} does not take a value")));
            }
            flags.switches.insert(name.to_string());
        } else if value_flags.contains(&name) {
            let value = match inline {
                Some(value) => value,
                None => iter
                    .next()
                    .cloned()
                    .ok_or_else(|| usage(format!("--{name} needs a value")))?,
            };
            flags
                .values
                .entry(name.to_string())
                .or_default()
                .push(value);
        } else {
            return Err(usage(format!("unknown option `--{name}`")));
        }
    }
    Ok(flags)
}

fn parse_simulate(args: &[String]) -> Result<SimulateOptions, CliError> {
    let flags = split_flags(
        args,
        &[
            "scenario", "nodes", "messages", "seed", "loss", "latency", "jitter", "mtu", "settle",
            "direct", "resync",
        ],
        &["json"],
    )?;
    Ok(SimulateOptions {
        scenario: flags.parsed("scenario", "crowd".to_string())?,
        nodes: flags.parsed("nodes", 20)?,
        messages: flags.parsed("messages", 10)?,
        seed: flags.parsed("seed", 1)?,
        loss_permille: flags.parsed("loss", 0)?,
        latency_ms: flags.parsed("latency", 20)?,
        jitter_ms: flags.parsed("jitter", 10)?,
        mtu: flags.parsed("mtu", 247)?,
        settle_ms: flags.parsed("settle", 60_000)?,
        direct_permille: flags.parsed("direct", 0)?,
        resync_ms: flags.parsed_opt("resync")?,
        json: flags.has("json"),
    })
}

fn parse_identity(args: &[String]) -> Result<IdentityOptions, CliError> {
    let flags = split_flags(args, &["file"], &[])?;
    Ok(IdentityOptions {
        file: flags.parsed("file", PathBuf::from("crisis-identity.key"))?,
    })
}

fn parse_node(args: &[String]) -> Result<NodeOptions, CliError> {
    let flags = split_flags(args, &["listen", "peer", "identity", "db", "mtu"], &[])?;
    let mut peers = Vec::new();
    for text in flags.all("peer") {
        let addr = text
            .parse::<SocketAddr>()
            .map_err(|_| usage(format!("invalid peer address `{text}`")))?;
        peers.push(addr);
    }
    Ok(NodeOptions {
        listen: flags.parsed("listen", SocketAddr::from(([127, 0, 0, 1], 0)))?,
        peers,
        identity: flags.parsed("identity", PathBuf::from("crisis-identity.key"))?,
        db: flags.parsed_opt("db")?,
        mtu: flags.parsed("mtu", 200)?,
    })
}

/// Parses a command line (program name excluded).
pub fn parse(args: &[String]) -> Result<Command, CliError> {
    let Some((command, rest)) = args.split_first() else {
        return Ok(Command::Help);
    };
    match command.as_str() {
        "help" | "--help" | "-h" => Ok(Command::Help),
        "simulate" => parse_simulate(rest).map(Command::Simulate),
        "identity" => parse_identity(rest).map(Command::Identity),
        "node" => parse_node(rest).map(Command::Node),
        other => Err(usage(format!("unknown command `{other}`"))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn words(line: &str) -> Vec<String> {
        line.split_whitespace().map(str::to_string).collect()
    }

    #[test]
    fn no_arguments_and_help_show_the_help() {
        assert_eq!(parse(&[]).unwrap(), Command::Help);
        assert_eq!(parse(&words("help")).unwrap(), Command::Help);
        assert_eq!(parse(&words("--help")).unwrap(), Command::Help);
    }

    #[test]
    fn simulate_defaults_and_overrides() {
        let Command::Simulate(d) = parse(&words("simulate")).unwrap() else {
            panic!("expected simulate");
        };
        assert_eq!(
            (d.scenario.as_str(), d.nodes, d.messages, d.seed),
            ("crowd", 20, 10, 1)
        );
        assert_eq!(
            (d.loss_permille, d.mtu, d.json, d.resync_ms),
            (0, 247, false, None)
        );

        let line = "simulate --scenario ring --nodes=8 --loss 50 --seed 9 --resync 5000 --json";
        let Command::Simulate(o) = parse(&words(line)).unwrap() else {
            panic!("expected simulate");
        };
        assert_eq!(
            (o.scenario.as_str(), o.nodes, o.loss_permille, o.seed),
            ("ring", 8, 50, 9)
        );
        assert_eq!((o.resync_ms, o.json), (Some(5000), true));
    }

    #[test]
    fn node_options() {
        let line =
            "node --listen 127.0.0.1:9001 --peer 127.0.0.1:9002 --peer 127.0.0.1:9003 --db a.db";
        let Command::Node(n) = parse(&words(line)).unwrap() else {
            panic!("expected node");
        };
        assert_eq!(n.listen.port(), 9001);
        assert_eq!(n.peers.len(), 2);
        assert_eq!(n.db, Some(PathBuf::from("a.db")));
        assert_eq!(n.mtu, 200);
    }

    #[test]
    fn identity_options() {
        let Command::Identity(i) = parse(&words("identity --file k.key")).unwrap() else {
            panic!("expected identity");
        };
        assert_eq!(i.file, PathBuf::from("k.key"));
    }

    #[test]
    fn mistakes_are_usage_errors() {
        for line in [
            "bogus",
            "simulate --nodes",
            "simulate --nodes many",
            "simulate --nope 1",
            "simulate extra",
            "simulate --json=yes",
            "node --peer not-an-address",
        ] {
            assert!(
                matches!(parse(&words(line)), Err(CliError::Usage(_))),
                "`{line}` should be rejected"
            );
        }
    }
}
