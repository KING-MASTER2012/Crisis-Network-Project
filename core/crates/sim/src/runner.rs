//! The simulation loop.

use std::cmp::Ordering;
use std::collections::btree_map::Entry;
use std::collections::{BTreeMap, BTreeSet, BinaryHeap};

use crypto::{ContactCard, Identity};
use engine::{Engine, EngineConfig, EngineError, Output, TransportAction};
use mesh::{Event, MIN_LINK_MTU, MeshConfig, Notification, PeerId};
use proto::MessageId;

use crate::error::{Result, SimError};
use crate::report::{MessageReport, Report, Totals};
use crate::rng::SimRng;
use crate::scenario::{Change, PlannedMessage, Scenario, Target};

/// The virtual clock starts here, so that timestamps are never zero.
const START_MS: u64 = 1_000;

/// Quality of every link.
#[derive(Clone, Debug)]
pub struct LinkModel {
    /// Minimum one-way delay (milliseconds, at least 1).
    pub latency_ms: u64,
    /// Extra random delay, uniform in `0..=jitter_ms`.
    pub jitter_ms: u64,
    /// Chance that a packet is lost, per thousand.
    pub loss_permille: u32,
}

/// Parameters of a simulation run.
#[derive(Clone, Debug)]
pub struct SimConfig {
    /// Seed for identities, loss and jitter. The same seed gives the same run.
    pub seed: u64,
    /// Mesh tuning used by every node.
    pub mesh: MeshConfig,
    /// Link quality.
    pub link: LinkModel,
    /// Packet size every link supports, in bytes.
    pub mtu: usize,
    /// Virtual time between two steps; nodes are ticked once per step (milliseconds).
    pub tick_ms: u64,
}

impl Default for SimConfig {
    fn default() -> Self {
        Self {
            seed: 1,
            mesh: MeshConfig::default(),
            link: LinkModel {
                latency_ms: 20,
                jitter_ms: 10,
                loss_permille: 0,
            },
            mtu: 247,
            tick_ms: 50,
        }
    }
}

impl SimConfig {
    /// Checks that the values are consistent.
    pub fn validate(&self) -> Result<()> {
        if self.tick_ms == 0 {
            return Err(SimError::InvalidConfig("tick_ms must be positive"));
        }
        if self.link.latency_ms == 0 {
            return Err(SimError::InvalidConfig("latency_ms must be at least 1"));
        }
        if self.link.loss_permille > 1000 {
            return Err(SimError::InvalidConfig(
                "loss_permille must not exceed 1000",
            ));
        }
        if self.mtu < MIN_LINK_MTU {
            return Err(SimError::InvalidConfig("mtu is below the mesh minimum"));
        }
        self.mesh
            .validate()
            .map_err(|e| SimError::Engine(EngineError::from(e)))
    }
}

/// A packet on its way.
struct Flight {
    deliver_at: u64,
    seq: u64,
    to: usize,
    from: usize,
    bytes: Vec<u8>,
}

impl PartialEq for Flight {
    fn eq(&self, other: &Self) -> bool {
        self.deliver_at == other.deliver_at && self.seq == other.seq
    }
}

impl Eq for Flight {}

impl Ord for Flight {
    /// Reversed, so that the standard max-heap pops the earliest packet first.
    fn cmp(&self, other: &Self) -> Ordering {
        other
            .deliver_at
            .cmp(&self.deliver_at)
            .then_with(|| other.seq.cmp(&self.seq))
    }
}

impl PartialOrd for Flight {
    fn partial_cmp(&self, other: &Self) -> Option<Ordering> {
        Some(self.cmp(other))
    }
}

/// A message that was sent, remembered for the report.
struct Tracked {
    id: MessageId,
    from: usize,
    target: Target,
    sent_at_ms: u64,
}

fn link_key(a: usize, b: usize) -> (usize, usize) {
    (a.min(b), a.max(b))
}

fn identity_for(seed: u64, index: usize) -> Result<Identity> {
    let mut rng = SimRng::new(seed ^ (index as u64 + 1).wrapping_mul(0xD1B5_4A32_D192_ED03));
    let mut bytes = [0u8; 32];
    for chunk in bytes.chunks_mut(8) {
        chunk.copy_from_slice(&rng.next_u64().to_be_bytes());
    }
    let identity = Identity::from_seed(&bytes).map_err(EngineError::from)?;
    Ok(identity)
}

/// The ephemeral encryption key of the direct message with this index.
fn ephemeral_for(seed: u64, index: usize) -> [u8; 32] {
    let mut rng =
        SimRng::new(seed ^ (index as u64 + 1).wrapping_mul(0x9FB2_1C65_1E98_DF25) ^ 0xE9E5);
    let mut bytes = [0u8; 32];
    for chunk in bytes.chunks_mut(8) {
        chunk.copy_from_slice(&rng.next_u64().to_be_bytes());
    }
    bytes
}

/// A payload that is unique per message: the message index followed by filler.
fn make_payload(index: usize, size: usize) -> Vec<u8> {
    let mut payload = vec![0u8; size.max(8)];
    payload[..8].copy_from_slice(&(index as u64).to_be_bytes());
    for (i, byte) in payload.iter_mut().enumerate().skip(8) {
        *byte = (i % 251) as u8;
    }
    payload
}

/// The value at percentile `pct` of an ascending list.
fn percentile(sorted: &[u64], pct: usize) -> Option<u64> {
    if sorted.is_empty() {
        return None;
    }
    sorted.get((sorted.len() - 1) * pct / 100).copied()
}

struct World {
    seed: u64,
    engines: Vec<Engine>,
    cards: Vec<ContactCard>,
    rng: SimRng,
    now: u64,
    mtu: usize,
    link: LinkModel,
    loss_permille: u32,
    queue: BinaryHeap<Flight>,
    flight_seq: u64,
    links: BTreeSet<(usize, usize)>,
    tracked: Vec<Tracked>,
    first_seen: BTreeMap<(usize, MessageId), u64>,
    acked: BTreeMap<MessageId, u64>,
    totals: Totals,
}

impl World {
    fn new(config: &SimConfig, scenario: &Scenario) -> Result<Self> {
        let mut engines = Vec::with_capacity(scenario.nodes);
        let mut cards = Vec::with_capacity(scenario.nodes);
        for index in 0..scenario.nodes {
            let identity = identity_for(config.seed, index)?;
            let engine_config = EngineConfig {
                mesh: config.mesh.clone(),
                ..EngineConfig::default()
            };
            let engine = Engine::in_memory(engine_config, identity, START_MS)?;
            cards.push(engine.contact_card());
            engines.push(engine);
        }
        Ok(Self {
            seed: config.seed,
            engines,
            cards,
            rng: SimRng::new(config.seed ^ 0x5DEE_CE66_D1CE_4E5B),
            now: START_MS,
            mtu: config.mtu,
            link: config.link.clone(),
            loss_permille: config.link.loss_permille,
            queue: BinaryHeap::new(),
            flight_seq: 0,
            links: BTreeSet::new(),
            tracked: Vec::new(),
            first_seen: BTreeMap::new(),
            acked: BTreeMap::new(),
            totals: Totals::default(),
        })
    }

    /// Runs one event on one node and routes what comes out.
    fn feed(&mut self, node: usize, event: Event) -> Result<()> {
        let output = self.engines[node].handle(self.now, event)?;
        self.dispatch(node, output);
        Ok(())
    }

    fn dispatch(&mut self, node: usize, output: Output) {
        for action in output.transport {
            match action {
                TransportAction::SendPacket { peer, bytes } => self.transmit(node, peer, bytes),
                TransportAction::Disconnect { .. } => self.totals.disconnects += 1,
            }
        }
        for note in output.notifications {
            self.observe(node, note);
        }
    }

    fn transmit(&mut self, from: usize, peer: PeerId, bytes: Vec<u8>) {
        let Ok(to) = usize::try_from(peer.0) else {
            return;
        };
        if !self.links.contains(&link_key(from, to)) {
            self.totals.packets_undeliverable += 1;
            return;
        }
        self.totals.packets_sent += 1;
        self.totals.bytes_sent += bytes.len() as u64;
        if self.rng.chance_permille(self.loss_permille) {
            self.totals.packets_dropped += 1;
            return;
        }
        let jitter = self.rng.below(self.link.jitter_ms.saturating_add(1));
        self.flight_seq += 1;
        self.queue.push(Flight {
            deliver_at: self.now + self.link.latency_ms + jitter,
            seq: self.flight_seq,
            to,
            from,
            bytes,
        });
    }

    fn observe(&mut self, node: usize, note: Notification) {
        match note {
            Notification::MessageReceived { id, .. } => match self.first_seen.entry((node, id)) {
                Entry::Vacant(slot) => {
                    slot.insert(self.now);
                }
                Entry::Occupied(_) => self.totals.duplicate_notifications += 1,
            },
            Notification::Delivered { id } => {
                self.acked.entry(id).or_insert(self.now);
            }
            _ => {}
        }
    }

    fn connect(&mut self, a: usize, b: usize) -> Result<()> {
        if !self.links.insert(link_key(a, b)) {
            return Ok(());
        }
        let link_mtu = self.mtu;
        self.feed(
            a,
            Event::PeerSeen {
                peer: PeerId(b as u64),
                link_mtu,
            },
        )?;
        self.feed(
            b,
            Event::PeerSeen {
                peer: PeerId(a as u64),
                link_mtu,
            },
        )
    }

    fn disconnect(&mut self, a: usize, b: usize) -> Result<()> {
        let key = link_key(a, b);
        if !self.links.remove(&key) {
            return Ok(());
        }
        let before = self.queue.len();
        self.queue.retain(|f| link_key(f.from, f.to) != key);
        self.totals.packets_undeliverable += (before - self.queue.len()) as u64;
        self.feed(
            a,
            Event::PeerLost {
                peer: PeerId(b as u64),
            },
        )?;
        self.feed(
            b,
            Event::PeerLost {
                peer: PeerId(a as u64),
            },
        )
    }

    fn apply(&mut self, change: Change) -> Result<()> {
        match change {
            Change::Connect(a, b) => self.connect(a, b),
            Change::Disconnect(a, b) => self.disconnect(a, b),
            Change::SetLossPermille(permille) => {
                self.loss_permille = permille.min(1000);
                Ok(())
            }
        }
    }

    fn send_planned(&mut self, index: usize, plan: &PlannedMessage) -> Result<()> {
        let payload = make_payload(index, plan.size);
        let (id, output) = match plan.target {
            Target::Broadcast => self.engines[plan.from].publish_broadcast(self.now, &payload)?,
            Target::Direct(to) => {
                let card = self.cards[to].clone();
                // A fixed ephemeral key per message keeps the whole run reproducible.
                let ephemeral = ephemeral_for(self.seed, index);
                self.engines[plan.from]
                    .publish_direct_to_card_with_ephemeral(self.now, &card, &payload, &ephemeral)?
            }
        };
        self.tracked.push(Tracked {
            id,
            from: plan.from,
            target: plan.target,
            sent_at_ms: self.now,
        });
        self.dispatch(plan.from, output);
        Ok(())
    }

    /// Delivers every packet whose time has come.
    fn deliver_due(&mut self) -> Result<()> {
        while self.queue.peek().is_some_and(|f| f.deliver_at <= self.now) {
            let Some(flight) = self.queue.pop() else {
                break;
            };
            self.totals.packets_delivered += 1;
            self.feed(
                flight.to,
                Event::PacketReceived {
                    peer: PeerId(flight.from as u64),
                    bytes: flight.bytes,
                },
            )?;
        }
        Ok(())
    }

    fn tick_all(&mut self) -> Result<()> {
        for node in 0..self.engines.len() {
            self.feed(node, Event::Tick)?;
        }
        Ok(())
    }

    fn into_report(self, scenario: &Scenario, seed: u64) -> Report {
        let mut totals = self.totals.clone();
        for engine in &self.engines {
            let s = engine.stats();
            totals.mesh_frames_accepted += s.frames_accepted;
            totals.mesh_duplicate_frames += s.duplicates;
            totals.mesh_want_gave_up += s.want_gave_up;
            totals.mesh_rate_limited += s.rate_limited;
            totals.mesh_sender_rate_limited += s.sender_rate_limited;
            totals.mesh_peers_banned += s.peers_banned;
            totals.mesh_invalid_signatures += s.invalid_signatures;
            totals.mesh_malformed_packets += s.malformed_packets;
        }
        let nodes = self.engines.len();
        let messages = self
            .tracked
            .iter()
            .enumerate()
            .map(|(index, t)| self.message_report(index, t, nodes))
            .collect();
        Report {
            scenario: scenario.name.clone(),
            seed,
            nodes,
            duration_ms: scenario.duration_ms,
            messages,
            totals,
        }
    }

    fn message_report(&self, index: usize, t: &Tracked, nodes: usize) -> MessageReport {
        let (kind, to, expected, receivers): (&'static str, Option<usize>, usize, Vec<usize>) =
            match t.target {
                Target::Broadcast => (
                    "broadcast",
                    None,
                    nodes - 1,
                    (0..nodes).filter(|n| *n != t.from).collect(),
                ),
                Target::Direct(to) => ("direct", Some(to), 1, vec![to]),
            };
        let mut latencies: Vec<u64> = receivers
            .iter()
            .filter_map(|n| self.first_seen.get(&(*n, t.id)))
            .map(|at| at.saturating_sub(t.sent_at_ms))
            .collect();
        latencies.sort_unstable();
        let acked_after_ms = match t.target {
            Target::Direct(_) => self
                .acked
                .get(&t.id)
                .map(|at| at.saturating_sub(t.sent_at_ms)),
            Target::Broadcast => None,
        };
        MessageReport {
            index,
            kind,
            from: t.from,
            to,
            sent_at_ms: t.sent_at_ms - START_MS,
            id: t.id.to_string(),
            reached: latencies.len(),
            expected,
            latency_p50_ms: percentile(&latencies, 50),
            latency_p95_ms: percentile(&latencies, 95),
            latency_max_ms: latencies.last().copied(),
            acked_after_ms,
        }
    }
}

/// Plays a scenario and returns what was measured.
///
/// The run is deterministic: the same `config` and `scenario` always give the same
/// [`Report`].
pub fn run(config: &SimConfig, scenario: &Scenario) -> Result<Report> {
    config.validate()?;
    scenario.validate()?;
    let mut world = World::new(config, scenario)?;

    let mut messages = scenario.messages.clone();
    messages.sort_by_key(|m| m.at_ms);
    let mut changes = scenario.changes.clone();
    changes.sort_by_key(|c| c.at_ms);

    for &(a, b) in &scenario.links {
        world.connect(a, b)?;
    }

    let mut next_message = 0;
    let mut next_change = 0;
    let mut elapsed = 0;
    while elapsed < scenario.duration_ms {
        elapsed += config.tick_ms;
        world.now += config.tick_ms;
        while let Some(change) = changes.get(next_change).filter(|c| c.at_ms <= elapsed) {
            world.apply(change.change)?;
            next_change += 1;
        }
        while let Some(plan) = messages.get(next_message).filter(|m| m.at_ms <= elapsed) {
            world.send_planned(next_message, plan)?;
            next_message += 1;
        }
        world.deliver_due()?;
        world.tick_all()?;
    }
    Ok(world.into_report(scenario, config.seed))
}
