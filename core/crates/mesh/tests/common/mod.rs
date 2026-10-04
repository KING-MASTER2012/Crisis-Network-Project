//! A deterministic in-memory network for exercising several meshes together.
//!
//! Packets are delivered instantly and in order (optionally with deterministic
//! loss); simulated time only moves when a test advances it.

#![allow(dead_code, missing_docs)]

use std::collections::{BTreeSet, VecDeque};

use crypto::{ContactCard, Identity};
use mesh::{Action, Event, Mesh, MeshConfig, Notification, PeerId};
use proto::{Kind, MessageId};
use storage::{Config as StoreConfig, Storage};

pub const START_MS: u64 = 1_000;

pub struct Node {
    pub mesh: Mesh,
    pub notes: Vec<Notification>,
    pub disconnects: Vec<PeerId>,
}

pub struct Sim {
    pub nodes: Vec<Node>,
    /// A second copy of each node's identity, so tests can sign messages for it.
    pub signers: Vec<Identity>,
    pub cards: Vec<ContactCard>,
    pub now: u64,
    pub mtu: usize,
    pub loss_permille: u64,
    links: BTreeSet<(usize, usize)>,
    queue: VecDeque<(usize, usize, Vec<u8>)>,
    rng: u64,
}

fn seed(i: usize) -> [u8; 32] {
    [u8::try_from(i).unwrap() + 1; 32]
}

pub fn new_mesh(index: usize, config: MeshConfig, now: u64) -> Mesh {
    let identity = Identity::from_seed(&seed(index)).unwrap();
    let storage = Storage::open_in_memory(StoreConfig {
        local_node: Some(identity.node_id()),
        ..StoreConfig::default()
    })
    .unwrap();
    Mesh::new(config, identity, storage, now).unwrap()
}

impl Sim {
    pub fn new(n: usize, config: MeshConfig, mtu: usize) -> Self {
        let mut nodes = Vec::new();
        let mut signers = Vec::new();
        let mut cards = Vec::new();
        for i in 0..n {
            nodes.push(Node {
                mesh: new_mesh(i, config.clone(), START_MS),
                notes: Vec::new(),
                disconnects: Vec::new(),
            });
            let signer = Identity::from_seed(&seed(i)).unwrap();
            cards.push(signer.contact_card());
            signers.push(signer);
        }
        Self {
            nodes,
            signers,
            cards,
            now: START_MS,
            mtu,
            loss_permille: 0,
            links: BTreeSet::new(),
            queue: VecDeque::new(),
            rng: 0x9E37_79B9_7F4A_7C15,
        }
    }

    fn lost(&mut self) -> bool {
        if self.loss_permille == 0 {
            return false;
        }
        self.rng = self
            .rng
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        (self.rng >> 33) % 1000 < self.loss_permille
    }

    fn feed(&mut self, node: usize, event: Event) {
        let actions = self.nodes[node].mesh.handle(self.now, event).unwrap();
        for action in actions {
            match action {
                Action::SendPacket { peer, bytes } => {
                    let to = usize::try_from(peer.0).unwrap();
                    if self.links.contains(&(node.min(to), node.max(to))) && !self.lost() {
                        self.queue.push_back((to, node, bytes));
                    }
                }
                Action::Notify(note) => self.nodes[node].notes.push(note),
                Action::Disconnect { peer, .. } => self.nodes[node].disconnects.push(peer),
                Action::SetTimer { .. } => {}
            }
        }
    }

    pub fn connect(&mut self, a: usize, b: usize) {
        self.links.insert((a.min(b), a.max(b)));
        let mtu = self.mtu;
        self.feed(
            a,
            Event::PeerSeen {
                peer: PeerId(b as u64),
                link_mtu: mtu,
            },
        );
        self.feed(
            b,
            Event::PeerSeen {
                peer: PeerId(a as u64),
                link_mtu: mtu,
            },
        );
        self.drain();
    }

    /// Delivers queued packets until the network is quiet.
    pub fn drain(&mut self) {
        while let Some((to, from, bytes)) = self.queue.pop_front() {
            self.feed(
                to,
                Event::PacketReceived {
                    peer: PeerId(from as u64),
                    bytes,
                },
            );
        }
    }

    pub fn tick_all(&mut self) {
        for i in 0..self.nodes.len() {
            self.feed(i, Event::Tick);
        }
        self.drain();
    }

    /// Advances simulated time, ticking every node each `step_ms`.
    pub fn run_for(&mut self, duration_ms: u64, step_ms: u64) {
        let end = self.now + duration_ms;
        while self.now < end {
            self.now += step_ms;
            self.tick_all();
        }
    }

    pub fn publish(&mut self, node: usize, envelope: proto::Envelope) {
        self.feed(
            node,
            Event::Publish {
                envelope: Box::new(envelope),
            },
        );
        self.drain();
    }

    pub fn has_message(&self, node: usize, id: &MessageId) -> bool {
        self.nodes[node]
            .mesh
            .storage()
            .contains(id, self.now)
            .unwrap()
    }

    /// Bodies of the messages the node was told about, in order.
    pub fn received_bodies(&self, node: usize) -> Vec<Vec<u8>> {
        self.nodes[node]
            .notes
            .iter()
            .filter_map(|n| match n {
                Notification::MessageReceived { body, .. } => Some(body.clone()),
                _ => None,
            })
            .collect()
    }

    pub fn delivered(&self, node: usize) -> Vec<MessageId> {
        self.nodes[node]
            .notes
            .iter()
            .filter_map(|n| match n {
                Notification::Delivered { id } => Some(*id),
                _ => None,
            })
            .collect()
    }

    pub fn broadcast(&self, from: usize, text: &[u8]) -> proto::Envelope {
        self.signers[from].broadcast(text, self.now, 3600).unwrap()
    }
}

pub fn is_broadcast(kind: Kind) -> bool {
    kind == Kind::Broadcast
}
