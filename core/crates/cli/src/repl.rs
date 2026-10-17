//! The interactive side of `crisis node`: commands typed on standard input.

use std::io::{BufRead, Write};
use std::sync::mpsc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crypto::load_or_create;
use engine::{Direction, Engine, EngineConfig, hex};
use mesh::{Notification, PublishError};
use proto::{Kind, NodeId};
use storage::TrustState;

use crate::args::NodeOptions;
use crate::error::CliError;
use crate::keystore::FileKeyStore;
use crate::node::UdpNode;

/// A command typed by the user of an interactive node.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum NodeCommand {
    /// Show the command list.
    Help,
    /// Announce text to everyone.
    Broadcast(String),
    /// Send text to a contact, given as a hexadecimal prefix of its node id.
    Send {
        /// The node id prefix.
        to: String,
        /// The message text.
        text: String,
    },
    /// Show this node's contact card.
    Card,
    /// Remember a contact card given in hexadecimal.
    Contact(String),
    /// List contacts.
    Contacts,
    /// Show the conversation history.
    History,
    /// List connected neighbours.
    Peers,
    /// Show mesh counters.
    Stats,
    /// Stop the node.
    Quit,
}

/// What to do after a command ran.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Flow {
    Continue,
    Quit,
}

/// Parses one input line. Blank lines give `Ok(None)`; unknown commands an error message.
pub fn parse_command(line: &str) -> Result<Option<NodeCommand>, String> {
    let line = line.trim();
    if line.is_empty() {
        return Ok(None);
    }
    let (word, rest) = match line.split_once(char::is_whitespace) {
        Some((word, rest)) => (word, rest.trim()),
        None => (line, ""),
    };
    let need = |what: &str| -> Result<(), String> {
        if rest.is_empty() {
            Err(format!("`{word}` needs {what}"))
        } else {
            Ok(())
        }
    };
    let command = match word {
        "help" | "?" => NodeCommand::Help,
        "broadcast" | "b" => {
            need("some text")?;
            NodeCommand::Broadcast(rest.to_string())
        }
        "send" | "s" => {
            let Some((to, text)) = rest.split_once(char::is_whitespace) else {
                return Err("usage: send NODE TEXT".to_string());
            };
            if text.trim().is_empty() {
                return Err("usage: send NODE TEXT".to_string());
            }
            NodeCommand::Send {
                to: to.to_string(),
                text: text.trim().to_string(),
            }
        }
        "card" => NodeCommand::Card,
        "contact" => {
            need("a contact card in hexadecimal")?;
            NodeCommand::Contact(rest.to_string())
        }
        "contacts" => NodeCommand::Contacts,
        "history" => NodeCommand::History,
        "peers" => NodeCommand::Peers,
        "stats" => NodeCommand::Stats,
        "quit" | "exit" | "q" => NodeCommand::Quit,
        other => return Err(format!("unknown command `{other}` (try `help`)")),
    };
    Ok(Some(command))
}

fn short(node: &NodeId) -> String {
    node.to_string().chars().take(8).collect()
}

/// Characters that change how surrounding text is displayed without being visible themselves
/// (zero-width marks, bidirectional overrides, the byte order mark).
fn is_invisible_format(c: char) -> bool {
    matches!(
        c,
        '\u{200B}'..='\u{200F}' | '\u{202A}'..='\u{202E}' | '\u{2060}'..='\u{2064}' | '\u{2066}'..='\u{2069}' | '\u{FEFF}'
    )
}

/// Message text as shown on the terminal.
///
/// Text from the network is untrusted: raw control characters would let a sender clear the
/// screen, move the cursor, change the window title or fake extra lines (for example a
/// `[delivered]` line). Control and invisible formatting characters are shown as `\u{..}`
/// instead; ordinary text in any language is untouched.
fn text_of(bytes: &[u8]) -> String {
    use std::fmt::Write as _;

    let text = String::from_utf8_lossy(bytes);
    let mut out = String::with_capacity(text.len());
    for c in text.chars() {
        if c.is_control() || is_invisible_format(c) {
            let _ = write!(out, "\\u{{{:x}}}", u32::from(c));
        } else {
            out.push(c);
        }
    }
    out
}

/// A one-line description of a notification, as printed by the interactive node.
pub fn describe(note: &Notification) -> Option<String> {
    // Every notification currently has a description; `Option` leaves room to silence
    // noisy ones later without changing the signature.
    match note {
        Notification::MessageReceived {
            sender, kind, body, ..
        } => {
            let label = if *kind == Kind::Direct {
                "direct"
            } else {
                "broadcast"
            };
            Some(format!("[{label}] {}: {}", short(sender), text_of(body)))
        }
        Notification::Delivered { id } => Some(format!("[delivered] {id}")),
        Notification::Published { id } => Some(format!("[published] {id}")),
        Notification::PublishRejected { reason, .. } => {
            let why = match reason {
                PublishError::Invalid => "invalid message",
                PublishError::Duplicate => "already published",
                PublishError::Tombstoned => "it was deleted earlier",
                PublishError::QuotaExceeded => "the store is full",
            };
            Some(format!("[rejected] {why}"))
        }
        Notification::PeerConnected { peer } => Some(format!("[peer] #{} connected", peer.0)),
        Notification::PeerDisconnected { peer } => Some(format!("[peer] #{} left", peer.0)),
        Notification::PeerBanned { peer } => Some(format!("[peer] #{} banned", peer.0)),
    }
}

fn print_notes(out: &mut dyn Write, notes: &[Notification]) -> Result<(), CliError> {
    for note in notes {
        if let Some(line) = describe(note) {
            writeln!(out, "{line}")?;
        }
    }
    Ok(())
}

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| u64::try_from(d.as_millis()).unwrap_or(u64::MAX))
}

/// Finds the one contact whose node id starts with `prefix`.
fn resolve_contact(engine: &Engine, prefix: &str) -> Result<NodeId, String> {
    let prefix = prefix.to_lowercase();
    let contacts = engine
        .contacts(1000)
        .map_err(|e| format!("cannot read contacts: {e}"))?;
    let mut matches = contacts
        .into_iter()
        .filter(|c| c.node_id.to_string().starts_with(&prefix));
    match (matches.next(), matches.next()) {
        (Some(only), None) => Ok(only.node_id),
        (None, _) => Err(format!(
            "no contact starts with `{prefix}` (see `contacts`)"
        )),
        (Some(_), Some(_)) => Err(format!("`{prefix}` matches several contacts")),
    }
}

fn execute(
    node: &mut UdpNode,
    command: NodeCommand,
    out: &mut dyn Write,
) -> Result<Flow, CliError> {
    let now = now_ms();
    match command {
        NodeCommand::Help => out.write_all(crate::args::HELP.as_bytes())?,
        NodeCommand::Broadcast(text) => {
            let (_, notes) = node.publish_broadcast(now, text.as_bytes())?;
            print_notes(out, &notes)?;
        }
        NodeCommand::Send { to, text } => match resolve_contact(node.engine(), &to) {
            Ok(target) => match node.publish_direct(now, &target, text.as_bytes()) {
                Ok((_, notes)) => print_notes(out, &notes)?,
                Err(e) => writeln!(out, "error: {e}")?,
            },
            Err(message) => writeln!(out, "error: {message}")?,
        },
        NodeCommand::Card => {
            let card = node.engine().contact_card();
            writeln!(out, "{}", hex::encode(&card.to_bytes()))?;
            writeln!(out, "safety number: {}", card.safety_number())?;
        }
        NodeCommand::Contact(card_hex) => match hex::decode(&card_hex) {
            None => writeln!(out, "error: the card is not valid hexadecimal")?,
            Some(bytes) => match node.engine_mut().add_contact(now, &bytes) {
                Ok(card) => writeln!(
                    out,
                    "added {} (safety number {})",
                    card.node_id(),
                    card.safety_number()
                )?,
                Err(e) => writeln!(out, "error: {e}")?,
            },
        },
        NodeCommand::Contacts => {
            for contact in node.engine().contacts(100)? {
                let trust = match contact.trust {
                    TrustState::Unknown => "unverified",
                    TrustState::Verified => "verified",
                    TrustState::Blocked => "blocked",
                };
                let card = if contact.card.is_some() {
                    "card"
                } else {
                    "no card"
                };
                writeln!(out, "{}  {trust}  {card}", contact.node_id)?;
            }
        }
        NodeCommand::History => {
            let page = node.engine().history(0, 100, now)?;
            for entry in &page.entries {
                let arrow = match entry.direction {
                    Direction::Outgoing => "->",
                    Direction::Incoming => "<-",
                };
                let who = entry
                    .counterpart
                    .as_ref()
                    .map_or_else(|| "everyone".to_string(), short);
                let body = entry
                    .body
                    .as_deref()
                    .map_or_else(|| "(sealed)".to_string(), text_of);
                writeln!(out, "{arrow} {who}: {body}")?;
            }
        }
        NodeCommand::Peers => {
            for (peer, addr) in node.peers() {
                writeln!(out, "#{} {addr}", peer.0)?;
            }
        }
        NodeCommand::Stats => writeln!(out, "{:#?}", node.engine().stats())?,
        NodeCommand::Quit => return Ok(Flow::Quit),
    }
    Ok(Flow::Continue)
}

/// Runs an interactive node until `quit` or the process is stopped.
pub(crate) fn run_node(options: &NodeOptions, out: &mut dyn Write) -> Result<(), CliError> {
    let store = FileKeyStore::new(options.identity.clone());
    let identity = load_or_create(&store)?;
    let start = now_ms();
    let engine = match &options.db {
        Some(path) => Engine::open(EngineConfig::default(), identity, path, None, start)?,
        None => Engine::in_memory(EngineConfig::default(), identity, start)?,
    };
    let mut node = UdpNode::bind(engine, options.listen, options.mtu)?;

    writeln!(out, "listening on {}", node.local_addr()?)?;
    writeln!(out, "node id: {}", node.engine().node_id())?;
    writeln!(out, "type `help` for the commands")?;
    for addr in &options.peers {
        let notes = node.add_peer(now_ms(), *addr)?;
        print_notes(out, &notes)?;
    }
    out.flush()?;

    let (sender, receiver) = mpsc::channel::<String>();
    std::thread::spawn(move || {
        let stdin = std::io::stdin();
        for line in stdin.lock().lines().map_while(Result::ok) {
            if sender.send(line).is_err() {
                break;
            }
        }
    });

    let mut next_tick = start;
    loop {
        while let Ok(line) = receiver.try_recv() {
            match parse_command(&line) {
                Ok(None) => {}
                Ok(Some(command)) => {
                    if execute(&mut node, command, out)? == Flow::Quit {
                        return Ok(());
                    }
                }
                Err(message) => writeln!(out, "error: {message}")?,
            }
        }
        let now = now_ms();
        let notes = node.poll(now, Duration::from_millis(20))?;
        print_notes(out, &notes)?;
        if now >= next_tick {
            let notes = node.tick(now)?;
            print_notes(out, &notes)?;
            next_tick = now + 100;
        }
        out.flush()?;
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use mesh::PeerId;
    use proto::MessageId;

    #[test]
    fn commands_parse() {
        assert_eq!(parse_command("").unwrap(), None);
        assert_eq!(parse_command("   ").unwrap(), None);
        assert_eq!(parse_command("help").unwrap(), Some(NodeCommand::Help));
        assert_eq!(
            parse_command("broadcast hello  world").unwrap(),
            Some(NodeCommand::Broadcast("hello  world".to_string()))
        );
        assert_eq!(
            parse_command("b hi").unwrap(),
            Some(NodeCommand::Broadcast("hi".to_string()))
        );
        assert_eq!(
            parse_command("send ab12 see you soon").unwrap(),
            Some(NodeCommand::Send {
                to: "ab12".to_string(),
                text: "see you soon".to_string()
            })
        );
        assert_eq!(parse_command("quit").unwrap(), Some(NodeCommand::Quit));
        assert_eq!(parse_command("q").unwrap(), Some(NodeCommand::Quit));
        assert_eq!(
            parse_command("history").unwrap(),
            Some(NodeCommand::History)
        );
    }

    #[test]
    fn mistakes_explain_themselves() {
        assert!(parse_command("broadcast").unwrap_err().contains("needs"));
        assert!(parse_command("send").unwrap_err().contains("usage"));
        assert!(parse_command("send ab12").unwrap_err().contains("usage"));
        assert!(parse_command("contact").unwrap_err().contains("needs"));
        assert!(
            parse_command("dance")
                .unwrap_err()
                .contains("unknown command")
        );
    }

    #[test]
    fn terminal_control_sequences_in_messages_are_neutralised() {
        // Clear screen + a fake status line, as an attacker would send them.
        let hostile = b"hi\x1b[2J\x1b]0;owned\x07\n[delivered] 0000";
        let shown = text_of(hostile);
        assert!(!shown.chars().any(char::is_control), "{shown:?}");
        assert!(shown.contains("\\u{1b}[2J"));
        assert!(shown.contains("\\u{a}[delivered]"));
        // A right-to-left override cannot reorder what follows it.
        assert!(!text_of("a\u{202E}b".as_bytes()).contains('\u{202E}'));
        // Invalid UTF-8 is replaced, not passed through.
        assert!(text_of(&[0xff, 0xfe]).contains('\u{FFFD}'));
    }

    #[test]
    fn ordinary_text_in_any_language_is_unchanged() {
        for text in [
            "hello, world",
            "Merhaba, dünya: şğüöçı İ",
            "помощь здесь",
            "救命",
            "ok \u{1F198}",
        ] {
            assert_eq!(text_of(text.as_bytes()), text);
        }
    }

    #[test]
    fn notifications_are_described() {
        let sender = NodeId::new([0xab; 32]);
        let note = Notification::MessageReceived {
            id: MessageId::new([1; 32]),
            sender,
            kind: Kind::Broadcast,
            body: b"hello".to_vec(),
        };
        assert_eq!(describe(&note).unwrap(), "[broadcast] abababab: hello");
        let note = Notification::PeerBanned { peer: PeerId(4) };
        assert_eq!(describe(&note).unwrap(), "[peer] #4 banned");
        let note = Notification::PublishRejected {
            id: None,
            reason: PublishError::Duplicate,
        };
        assert!(describe(&note).unwrap().contains("already published"));
    }
}
