//! Scenarios: topology, link changes over time and the messages to send.

use std::collections::BTreeSet;

use crate::error::{Result, SimError};
use crate::rng::SimRng;

/// An undirected link between two node indices.
pub type Link = (usize, usize);

/// Smallest message the simulator sends: room for the message index.
const MIN_MESSAGE_SIZE: usize = 8;
/// Largest message the simulator sends (below every protocol limit).
const MAX_MESSAGE_SIZE: usize = 900;

/// Who a planned message is for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Target {
    /// Everyone.
    Broadcast,
    /// One node, by index.
    Direct(usize),
}

/// A message to send at a given moment.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlannedMessage {
    /// When to send it (milliseconds after the start of the run).
    pub at_ms: u64,
    /// The sending node.
    pub from: usize,
    /// Broadcast or direct.
    pub target: Target,
    /// Payload size in bytes.
    pub size: usize,
}

/// A change of the network while it runs.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Change {
    /// A link comes up.
    Connect(usize, usize),
    /// A link goes down; packets in flight on it are lost.
    Disconnect(usize, usize),
    /// The packet loss of every link changes (per thousand).
    SetLossPermille(u32),
}

/// A [`Change`] at a given moment.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct TimedChange {
    /// When it happens (milliseconds after the start of the run).
    pub at_ms: u64,
    /// What happens.
    pub change: Change,
}

/// Everything the simulator needs to know about the world.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Scenario {
    /// A short name, used in reports.
    pub name: String,
    /// Number of nodes (indices `0..nodes`).
    pub nodes: usize,
    /// Links that exist from the start.
    pub links: Vec<Link>,
    /// Messages to send.
    pub messages: Vec<PlannedMessage>,
    /// Link changes over time.
    pub changes: Vec<TimedChange>,
    /// How long the run lasts (milliseconds).
    pub duration_ms: u64,
}

impl Scenario {
    /// Checks that every index and size is in range.
    pub fn validate(&self) -> Result<()> {
        let bad = |what: &str| Err(SimError::InvalidScenario(what.to_string()));
        if self.nodes < 2 {
            return bad("at least two nodes are required");
        }
        if self.duration_ms == 0 {
            return bad("the duration must be positive");
        }
        let in_range = |n: usize| n < self.nodes;
        for &(a, b) in &self.links {
            if !in_range(a) || !in_range(b) || a == b {
                return bad("a link refers to a missing node or to itself");
            }
        }
        for change in &self.changes {
            if let Change::Connect(a, b) | Change::Disconnect(a, b) = change.change
                && (!in_range(a) || !in_range(b) || a == b)
            {
                return bad("a link change refers to a missing node or to itself");
            }
        }
        for message in &self.messages {
            if !in_range(message.from) {
                return bad("a message is sent by a missing node");
            }
            if let Target::Direct(to) = message.target
                && (!in_range(to) || to == message.from)
            {
                return bad("a direct message has an invalid recipient");
            }
            if !(MIN_MESSAGE_SIZE..=MAX_MESSAGE_SIZE).contains(&message.size) {
                return bad("a message size is out of range");
            }
        }
        Ok(())
    }
}

/// A chain: `0 - 1 - 2 - ...`.
pub fn line_links(nodes: usize) -> Vec<Link> {
    (1..nodes).map(|i| (i - 1, i)).collect()
}

/// A chain closed into a loop. Two nodes form a single link.
pub fn ring_links(nodes: usize) -> Vec<Link> {
    let mut links = line_links(nodes);
    if nodes > 2 {
        links.push((nodes - 1, 0));
    }
    links
}

/// Node 0 in the middle, everyone else connected to it.
pub fn star_links(nodes: usize) -> Vec<Link> {
    (1..nodes).map(|i| (0, i)).collect()
}

/// A rectangular grid with `width` columns that holds `nodes` nodes, row by row.
pub fn grid_links(nodes: usize, width: usize) -> Vec<Link> {
    let width = width.max(1);
    let mut links = Vec::new();
    for i in 0..nodes {
        if !(i + 1).is_multiple_of(width) && i + 1 < nodes {
            links.push((i, i + 1));
        }
        if i + width < nodes {
            links.push((i, i + width));
        }
    }
    links
}

/// A random connected graph with roughly `avg_degree` links per node: a random
/// spanning tree plus random extra links.
pub fn random_links(nodes: usize, avg_degree: usize, rng: &mut SimRng) -> Vec<Link> {
    if nodes < 2 {
        return Vec::new();
    }
    let mut set: BTreeSet<Link> = BTreeSet::new();
    for i in 1..nodes {
        set.insert((rng.below_usize(i), i));
    }
    let target = (nodes * avg_degree / 2).max(nodes - 1);
    let mut attempts = 0;
    while set.len() < target && attempts < target * 20 + 100 {
        attempts += 1;
        let a = rng.below_usize(nodes);
        let b = rng.below_usize(nodes);
        if a != b {
            set.insert((a.min(b), a.max(b)));
        }
    }
    set.into_iter().collect()
}

/// Shape of a generated message workload.
#[derive(Clone, Debug)]
pub struct Workload {
    /// Number of messages.
    pub count: usize,
    /// When the first one is sent (milliseconds).
    pub start_ms: u64,
    /// Time between two messages (milliseconds).
    pub interval_ms: u64,
    /// Payload size in bytes.
    pub size: usize,
    /// Share of direct messages, per thousand; the rest are broadcasts.
    pub direct_permille: u32,
}

/// Generates a workload: senders and recipients are drawn at random.
pub fn plan_messages(nodes: usize, workload: &Workload, rng: &mut SimRng) -> Vec<PlannedMessage> {
    (0..workload.count)
        .map(|i| {
            let from = rng.below_usize(nodes);
            let target = if nodes > 1 && rng.chance_permille(workload.direct_permille) {
                // Pick any node except the sender.
                let offset = 1 + rng.below_usize(nodes - 1);
                Target::Direct((from + offset) % nodes)
            } else {
                Target::Broadcast
            };
            PlannedMessage {
                at_ms: workload.start_ms + workload.interval_ms * i as u64,
                from,
                target,
                size: workload.size,
            }
        })
        .collect()
}

/// Inputs of [`builtin_scenario`].
#[derive(Clone, Debug)]
pub struct ScenarioParams {
    /// Number of nodes.
    pub nodes: usize,
    /// Number of messages.
    pub messages: usize,
    /// Seed for the random choices of the scenario (topology and workload).
    pub seed: u64,
    /// How long the run continues after the last message (and after healing, for
    /// `partition`), in milliseconds.
    pub settle_ms: u64,
    /// Share of direct messages, per thousand.
    pub direct_permille: u32,
}

impl Default for ScenarioParams {
    fn default() -> Self {
        Self {
            nodes: 20,
            messages: 10,
            seed: 1,
            settle_ms: 60_000,
            direct_permille: 0,
        }
    }
}

/// The names [`builtin_scenario`] understands.
pub const SCENARIO_NAMES: [&str; 6] = ["line", "ring", "star", "grid", "crowd", "partition"];

/// Builds one of the ready-made scenarios:
///
/// * `line`, `ring`, `star`, `grid`: regular topologies;
/// * `crowd`: a dense random graph (about four links per node);
/// * `partition`: two rings with no link between them; the halves are joined shortly
///   after the last message.
pub fn builtin_scenario(name: &str, params: &ScenarioParams) -> Result<Scenario> {
    let nodes = params.nodes;
    if nodes < 2 {
        return Err(SimError::InvalidScenario(
            "at least two nodes are required".to_string(),
        ));
    }
    let mut rng = SimRng::new(params.seed);
    let links = match name {
        "line" => line_links(nodes),
        "ring" => ring_links(nodes),
        "star" => star_links(nodes),
        "grid" => {
            let mut width = 1;
            while width * width < nodes {
                width += 1;
            }
            grid_links(nodes, width)
        }
        "crowd" => random_links(nodes, 4, &mut rng),
        "partition" => {
            let half = nodes / 2;
            let mut links = ring_links(half);
            links.extend(
                ring_links(nodes - half)
                    .into_iter()
                    .map(|(a, b)| (a + half, b + half)),
            );
            links
        }
        other => {
            return Err(SimError::InvalidScenario(format!(
                "unknown scenario `{other}` (expected one of: {})",
                SCENARIO_NAMES.join(", ")
            )));
        }
    };
    let workload = Workload {
        count: params.messages,
        start_ms: 2_000,
        interval_ms: 500,
        size: 120,
        direct_permille: params.direct_permille,
    };
    let messages = plan_messages(nodes, &workload, &mut rng);
    let last_message_ms = messages.last().map_or(workload.start_ms, |m| m.at_ms);

    let mut changes = Vec::new();
    let mut quiet_until = last_message_ms;
    if name == "partition" {
        let heal_at_ms = last_message_ms + 10_000;
        changes.push(TimedChange {
            at_ms: heal_at_ms,
            change: Change::Connect(0, nodes / 2),
        });
        quiet_until = heal_at_ms;
    }
    Ok(Scenario {
        name: name.to_string(),
        nodes,
        links,
        messages,
        changes,
        duration_ms: quiet_until + params.settle_ms,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Number of nodes reachable from node 0 over `links`.
    fn reachable(nodes: usize, links: &[Link]) -> usize {
        let mut seen = vec![false; nodes];
        let mut stack = vec![0usize];
        seen[0] = true;
        while let Some(n) = stack.pop() {
            for &(a, b) in links {
                for (from, to) in [(a, b), (b, a)] {
                    if from == n && !seen[to] {
                        seen[to] = true;
                        stack.push(to);
                    }
                }
            }
        }
        seen.into_iter().filter(|s| *s).count()
    }

    #[test]
    fn regular_topologies_have_the_expected_shape() {
        assert_eq!(line_links(5), vec![(0, 1), (1, 2), (2, 3), (3, 4)]);
        assert_eq!(ring_links(4).len(), 4);
        assert_eq!(ring_links(2), vec![(0, 1)]);
        assert_eq!(star_links(4), vec![(0, 1), (0, 2), (0, 3)]);
        // 3 x 3 grid: 6 horizontal + 6 vertical links.
        assert_eq!(grid_links(9, 3).len(), 12);
        // 7 nodes in rows of 3: the last row has a single node.
        let g = grid_links(7, 3);
        assert!(!g.contains(&(6, 7)));
        assert!(g.contains(&(3, 6)));
        assert_eq!(reachable(7, &g), 7);
    }

    #[test]
    fn random_graphs_are_connected_and_reproducible() {
        for seed in 0..20 {
            let links = random_links(25, 4, &mut SimRng::new(seed));
            assert_eq!(reachable(25, &links), 25, "seed {seed}");
            assert!(links.len() >= 24);
            assert_eq!(links, random_links(25, 4, &mut SimRng::new(seed)));
        }
        assert!(random_links(1, 4, &mut SimRng::new(0)).is_empty());
    }

    #[test]
    fn workloads_are_valid() {
        let w = Workload {
            count: 50,
            start_ms: 100,
            interval_ms: 10,
            size: 64,
            direct_permille: 500,
        };
        let msgs = plan_messages(6, &w, &mut SimRng::new(3));
        assert_eq!(msgs.len(), 50);
        assert!(msgs.windows(2).all(|p| p[0].at_ms < p[1].at_ms));
        let mut direct = 0;
        for m in &msgs {
            assert!(m.from < 6);
            if let Target::Direct(to) = m.target {
                assert!(to < 6 && to != m.from);
                direct += 1;
            }
        }
        assert!(direct > 0 && direct < 50);
    }

    #[test]
    fn every_builtin_scenario_is_valid_and_connected_when_it_should_be() {
        for name in SCENARIO_NAMES {
            let params = ScenarioParams {
                nodes: 12,
                ..ScenarioParams::default()
            };
            let s = builtin_scenario(name, &params).unwrap();
            s.validate().unwrap();
            assert_eq!(s.name, name);
            assert_eq!(s.messages.len(), params.messages);
            let connected = reachable(s.nodes, &s.links);
            if name == "partition" {
                assert_eq!(connected, 6);
                assert_eq!(s.changes.len(), 1);
            } else {
                assert_eq!(connected, s.nodes, "{name}");
            }
            assert_eq!(s, builtin_scenario(name, &params).unwrap());
        }
    }

    #[test]
    fn bad_input_is_reported() {
        assert!(builtin_scenario("nonsense", &ScenarioParams::default()).is_err());
        let one = ScenarioParams {
            nodes: 1,
            ..ScenarioParams::default()
        };
        assert!(builtin_scenario("line", &one).is_err());

        let mut s = builtin_scenario("line", &ScenarioParams::default()).unwrap();
        s.links.push((0, 99));
        assert!(s.validate().is_err());
        let mut s = builtin_scenario("line", &ScenarioParams::default()).unwrap();
        s.messages[0].size = 5_000;
        assert!(s.validate().is_err());
        let mut s = builtin_scenario("line", &ScenarioParams::default()).unwrap();
        s.messages[0].target = Target::Direct(s.messages[0].from);
        assert!(s.validate().is_err());
    }
}
