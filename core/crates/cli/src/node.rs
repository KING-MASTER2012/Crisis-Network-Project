//! A node that talks to its neighbours over UDP.
//!
//! UDP stands in for Bluetooth: it lets the whole core be exercised between two terminals
//! or two machines. Each remote address is one neighbour, identified to the mesh by a
//! [`mesh::PeerId`] that this module assigns.

use std::collections::{BTreeMap, BTreeSet};
use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::time::Duration;

use engine::{Engine, Output, TransportAction};
use mesh::{Notification, PeerId};
use proto::{MessageId, NodeId};

use crate::error::CliError;

/// Largest datagram this node reads.
const MAX_DATAGRAM: usize = 2048;

/// Errors that only mean "nothing to read right now" or "that neighbour is not
/// listening yet". On Windows a datagram sent to a closed port makes the *next*
/// receive fail with `ConnectionReset`, so that one is routine as well.
fn is_transient(error: &io::Error) -> bool {
    matches!(
        error.kind(),
        io::ErrorKind::WouldBlock
            | io::ErrorKind::TimedOut
            | io::ErrorKind::ConnectionReset
            | io::ErrorKind::ConnectionRefused
            | io::ErrorKind::Interrupted
    )
}

/// An [`Engine`] attached to a UDP socket.
pub struct UdpNode {
    engine: Engine,
    socket: UdpSocket,
    by_addr: BTreeMap<SocketAddr, PeerId>,
    by_peer: BTreeMap<PeerId, SocketAddr>,
    banned: BTreeSet<SocketAddr>,
    next_peer: u64,
    mtu: usize,
    buffer: Vec<u8>,
}

impl UdpNode {
    /// Binds a socket on `listen` and wraps `engine`. `mtu` is the packet size the mesh
    /// is told each link supports.
    pub fn bind(engine: Engine, listen: SocketAddr, mtu: usize) -> Result<Self, CliError> {
        let socket = UdpSocket::bind(listen)?;
        Ok(Self {
            engine,
            socket,
            by_addr: BTreeMap::new(),
            by_peer: BTreeMap::new(),
            banned: BTreeSet::new(),
            next_peer: 1,
            mtu,
            buffer: vec![0u8; MAX_DATAGRAM],
        })
    }

    /// The address the socket is bound to (useful when port 0 was requested).
    pub fn local_addr(&self) -> Result<SocketAddr, CliError> {
        Ok(self.socket.local_addr()?)
    }

    /// The engine inside.
    pub fn engine(&self) -> &Engine {
        &self.engine
    }

    /// The engine inside, mutably.
    pub fn engine_mut(&mut self) -> &mut Engine {
        &mut self.engine
    }

    /// The neighbours currently known, with their addresses.
    pub fn peers(&self) -> Vec<(PeerId, SocketAddr)> {
        self.by_peer.iter().map(|(p, a)| (*p, *a)).collect()
    }

    /// Registers a neighbour. Does nothing if the address is already known or banned.
    pub fn add_peer(&mut self, now_ms: u64, addr: SocketAddr) -> Result<Vec<Notification>, CliError> {
        if self.by_addr.contains_key(&addr) || self.banned.contains(&addr) {
            return Ok(Vec::new());
        }
        let peer = PeerId(self.next_peer);
        self.next_peer += 1;
        self.by_addr.insert(addr, peer);
        self.by_peer.insert(peer, addr);
        let output = self.engine.peer_seen(now_ms, peer, self.mtu)?;
        self.apply(output)
    }

    /// Waits up to `wait` for one datagram and processes it.
    pub fn poll(&mut self, now_ms: u64, wait: Duration) -> Result<Vec<Notification>, CliError> {
        // A zero timeout is rejected by the operating system.
        self.socket
            .set_read_timeout(Some(wait.max(Duration::from_millis(1))))?;
        let (len, addr) = match self.socket.recv_from(&mut self.buffer) {
            Ok(received) => received,
            Err(e) if is_transient(&e) => return Ok(Vec::new()),
            Err(e) => return Err(e.into()),
        };
        if self.banned.contains(&addr) {
            return Ok(Vec::new());
        }
        let bytes = self.buffer[..len].to_vec();
        let mut notes = self.add_peer(now_ms, addr)?;
        let Some(peer) = self.by_addr.get(&addr).copied() else {
            return Ok(notes);
        };
        let output = self.engine.packet_received(now_ms, peer, bytes)?;
        notes.extend(self.apply(output)?);
        Ok(notes)
    }

    /// Lets time pass.
    pub fn tick(&mut self, now_ms: u64) -> Result<Vec<Notification>, CliError> {
        let output = self.engine.tick(now_ms)?;
        self.apply(output)
    }

    /// Publishes a broadcast.
    pub fn publish_broadcast(
        &mut self,
        now_ms: u64,
        payload: &[u8],
    ) -> Result<(MessageId, Vec<Notification>), CliError> {
        let (id, output) = self.engine.publish_broadcast(now_ms, payload)?;
        Ok((id, self.apply(output)?))
    }

    /// Publishes a message encrypted for a known contact.
    pub fn publish_direct(
        &mut self,
        now_ms: u64,
        to: &NodeId,
        plaintext: &[u8],
    ) -> Result<(MessageId, Vec<Notification>), CliError> {
        let (id, output) = self.engine.publish_direct(now_ms, to, plaintext)?;
        Ok((id, self.apply(output)?))
    }

    /// Sends what the engine asked to send and returns what it wants the user to see.
    fn apply(&mut self, output: Output) -> Result<Vec<Notification>, CliError> {
        for action in output.transport {
            match action {
                TransportAction::SendPacket { peer, bytes } => {
                    if let Some(addr) = self.by_peer.get(&peer) {
                        match self.socket.send_to(&bytes, addr) {
                            Ok(_) => {}
                            Err(e) if is_transient(&e) => {}
                            Err(e) => return Err(e.into()),
                        }
                    }
                }
                TransportAction::Disconnect { peer, .. } => {
                    if let Some(addr) = self.by_peer.remove(&peer) {
                        self.by_addr.remove(&addr);
                        self.banned.insert(addr);
                    }
                }
            }
        }
        Ok(output.notifications)
    }
}
