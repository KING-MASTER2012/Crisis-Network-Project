//! What a run measured, and how to print or export it.

/// Network-wide counters.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Totals {
    /// Packets handed to a link by a node.
    pub packets_sent: u64,
    /// Packets that reached their destination node.
    pub packets_delivered: u64,
    /// Packets lost by the link model.
    pub packets_dropped: u64,
    /// Packets sent over a link that did not exist or was cut while they travelled.
    pub packets_undeliverable: u64,
    /// Total size of the packets sent.
    pub bytes_sent: u64,
    /// Times a node was told about the same message more than once. Must be zero.
    pub duplicate_notifications: u64,
    /// Disconnect requests the nodes issued (bans).
    pub disconnects: u64,
    /// Messages stored from the network, summed over all nodes.
    pub mesh_frames_accepted: u64,
    /// Frames received for messages the node already had, summed over all nodes.
    pub mesh_duplicate_frames: u64,
    /// Requests abandoned after too many attempts, summed over all nodes.
    pub mesh_want_gave_up: u64,
    /// Packets dropped by per-peer rate limits, summed over all nodes.
    pub mesh_rate_limited: u64,
    /// Frames dropped by per-sender rate limits, summed over all nodes.
    pub mesh_sender_rate_limited: u64,
    /// Peers banned, summed over all nodes.
    pub mesh_peers_banned: u64,
    /// Frames with an invalid signature, summed over all nodes.
    pub mesh_invalid_signatures: u64,
    /// Packets that did not parse, summed over all nodes.
    pub mesh_malformed_packets: u64,
}

/// What happened to one message.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MessageReport {
    /// Position in the workload.
    pub index: usize,
    /// `"broadcast"` or `"direct"`.
    pub kind: &'static str,
    /// The sending node.
    pub from: usize,
    /// The recipient of a direct message.
    pub to: Option<usize>,
    /// When it was sent (milliseconds after the start of the run).
    pub sent_at_ms: u64,
    /// The message id in hexadecimal.
    pub id: String,
    /// How many of the intended recipients were told about it.
    pub reached: usize,
    /// How many recipients it was meant for (everyone else, or the one recipient).
    pub expected: usize,
    /// Median time until a recipient was told (milliseconds).
    pub latency_p50_ms: Option<u64>,
    /// 95th percentile of the same (milliseconds).
    pub latency_p95_ms: Option<u64>,
    /// Slowest recipient (milliseconds).
    pub latency_max_ms: Option<u64>,
    /// For direct messages: time until the sender learned of the delivery.
    pub acked_after_ms: Option<u64>,
}

/// The outcome of one simulation run.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Report {
    /// The scenario name.
    pub scenario: String,
    /// The seed that produced this run.
    pub seed: u64,
    /// Number of nodes.
    pub nodes: usize,
    /// Simulated duration (milliseconds).
    pub duration_ms: u64,
    /// One entry per message, in sending order.
    pub messages: Vec<MessageReport>,
    /// Network-wide counters.
    pub totals: Totals,
}

impl Report {
    /// Recipients reached divided by recipients intended, over all messages
    /// (1.0 when there were no messages).
    pub fn delivery_ratio(&self) -> f64 {
        let expected: usize = self.messages.iter().map(|m| m.expected).sum();
        if expected == 0 {
            return 1.0;
        }
        let reached: usize = self.messages.iter().map(|m| m.reached).sum();
        reached as f64 / expected as f64
    }

    /// Packets sent per recipient reached: how much traffic one delivery costs.
    pub fn packets_per_delivery(&self) -> f64 {
        let reached: usize = self.messages.iter().map(|m| m.reached).sum();
        if reached == 0 {
            return 0.0;
        }
        self.totals.packets_sent as f64 / reached as f64
    }

    /// A human-readable summary.
    pub fn summary(&self) -> String {
        let t = &self.totals;
        let ratio = self.delivery_ratio() * 100.0;
        let cost = self.packets_per_delivery();
        let mut out = String::new();
        out.push_str(&format!(
            "scenario {} | seed {} | {} nodes | {} s simulated\n",
            self.scenario,
            self.seed,
            self.nodes,
            self.duration_ms / 1000
        ));
        out.push_str(&format!(
            "messages: {} | delivery: {ratio:.2}% | packets per delivery: {cost:.1}\n",
            self.messages.len()
        ));
        out.push_str(&format!(
            "packets: {} sent, {} delivered, {} lost, {} undeliverable | {} bytes\n",
            t.packets_sent, t.packets_delivered, t.packets_dropped, t.packets_undeliverable, t.bytes_sent
        ));
        out.push_str(&format!(
            "duplicate notifications: {} | duplicate frames: {} | gave up: {}\n",
            t.duplicate_notifications, t.mesh_duplicate_frames, t.mesh_want_gave_up
        ));
        let slowest = self
            .messages
            .iter()
            .filter_map(|m| m.latency_max_ms)
            .max()
            .unwrap_or(0);
        out.push_str(&format!("slowest delivery: {slowest} ms\n"));
        out
    }

    /// The report as JSON, for analysis tools. The layout is stable and the output is
    /// byte-for-byte reproducible.
    pub fn to_json(&self) -> String {
        let mut root = JsonObject::new();
        root.text("scenario", &self.scenario);
        root.num("seed", self.seed);
        root.num("nodes", self.nodes as u64);
        root.num("duration_ms", self.duration_ms);
        root.float("delivery_ratio", self.delivery_ratio());
        root.float("packets_per_delivery", self.packets_per_delivery());
        root.raw("totals", &totals_json(&self.totals));
        let messages: Vec<String> = self.messages.iter().map(message_json).collect();
        root.raw("messages", &format!("[{}]", messages.join(",")));
        root.finish()
    }
}

fn totals_json(t: &Totals) -> String {
    let mut o = JsonObject::new();
    o.num("packets_sent", t.packets_sent);
    o.num("packets_delivered", t.packets_delivered);
    o.num("packets_dropped", t.packets_dropped);
    o.num("packets_undeliverable", t.packets_undeliverable);
    o.num("bytes_sent", t.bytes_sent);
    o.num("duplicate_notifications", t.duplicate_notifications);
    o.num("disconnects", t.disconnects);
    o.num("mesh_frames_accepted", t.mesh_frames_accepted);
    o.num("mesh_duplicate_frames", t.mesh_duplicate_frames);
    o.num("mesh_want_gave_up", t.mesh_want_gave_up);
    o.num("mesh_rate_limited", t.mesh_rate_limited);
    o.num("mesh_sender_rate_limited", t.mesh_sender_rate_limited);
    o.num("mesh_peers_banned", t.mesh_peers_banned);
    o.num("mesh_invalid_signatures", t.mesh_invalid_signatures);
    o.num("mesh_malformed_packets", t.mesh_malformed_packets);
    o.finish()
}

fn message_json(m: &MessageReport) -> String {
    let mut o = JsonObject::new();
    o.num("index", m.index as u64);
    o.text("kind", m.kind);
    o.num("from", m.from as u64);
    o.opt_num("to", m.to.map(|t| t as u64));
    o.num("sent_at_ms", m.sent_at_ms);
    o.text("id", &m.id);
    o.num("reached", m.reached as u64);
    o.num("expected", m.expected as u64);
    o.opt_num("latency_p50_ms", m.latency_p50_ms);
    o.opt_num("latency_p95_ms", m.latency_p95_ms);
    o.opt_num("latency_max_ms", m.latency_max_ms);
    o.opt_num("acked_after_ms", m.acked_after_ms);
    o.finish()
}

/// Appends `text` as a JSON string literal.
fn push_json_string(out: &mut String, text: &str) {
    out.push('"');
    for c in text.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if u32::from(c) < 0x20 => out.push_str(&format!("\\u{:04x}", u32::from(c))),
            c => out.push(c),
        }
    }
    out.push('"');
}

/// Builds one JSON object, key by key.
struct JsonObject {
    buf: String,
    first: bool,
}

impl JsonObject {
    fn new() -> Self {
        Self {
            buf: String::from("{"),
            first: true,
        }
    }

    fn key(&mut self, key: &str) {
        if !self.first {
            self.buf.push(',');
        }
        self.first = false;
        push_json_string(&mut self.buf, key);
        self.buf.push(':');
    }

    fn num(&mut self, key: &str, value: u64) {
        self.key(key);
        self.buf.push_str(&value.to_string());
    }

    fn opt_num(&mut self, key: &str, value: Option<u64>) {
        self.key(key);
        match value {
            Some(v) => self.buf.push_str(&v.to_string()),
            None => self.buf.push_str("null"),
        }
    }

    fn float(&mut self, key: &str, value: f64) {
        self.key(key);
        self.buf.push_str(&format!("{value:.4}"));
    }

    fn text(&mut self, key: &str, value: &str) {
        self.key(key);
        push_json_string(&mut self.buf, value);
    }

    /// Inserts an already-encoded JSON value.
    fn raw(&mut self, key: &str, json: &str) {
        self.key(key);
        self.buf.push_str(json);
    }

    fn finish(mut self) -> String {
        self.buf.push('}');
        self.buf
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> Report {
        Report {
            scenario: "demo \"quoted\"\n".to_string(),
            seed: 7,
            nodes: 3,
            duration_ms: 5_000,
            messages: vec![
                MessageReport {
                    index: 0,
                    kind: "broadcast",
                    from: 0,
                    to: None,
                    sent_at_ms: 100,
                    id: "ab".to_string(),
                    reached: 1,
                    expected: 2,
                    latency_p50_ms: Some(40),
                    latency_p95_ms: Some(40),
                    latency_max_ms: Some(40),
                    acked_after_ms: None,
                },
                MessageReport {
                    index: 1,
                    kind: "direct",
                    from: 1,
                    to: Some(2),
                    sent_at_ms: 200,
                    id: "cd".to_string(),
                    reached: 1,
                    expected: 1,
                    latency_p50_ms: Some(10),
                    latency_p95_ms: Some(10),
                    latency_max_ms: Some(10),
                    acked_after_ms: Some(25),
                },
            ],
            totals: Totals {
                packets_sent: 9,
                ..Totals::default()
            },
        }
    }

    #[test]
    fn ratios() {
        let r = sample();
        assert!((r.delivery_ratio() - 2.0 / 3.0).abs() < 1e-12);
        assert!((r.packets_per_delivery() - 4.5).abs() < 1e-12);
        let empty = Report {
            messages: vec![],
            ..sample()
        };
        assert!((empty.delivery_ratio() - 1.0).abs() < f64::EPSILON);
        assert!(empty.packets_per_delivery().abs() < f64::EPSILON);
    }

    #[test]
    fn json_is_escaped_and_stable() {
        let json = sample().to_json();
        assert!(json.starts_with("{\"scenario\":\"demo \\\"quoted\\\"\\n\",\"seed\":7,"));
        assert!(json.contains("\"to\":null"));
        assert!(json.contains("\"to\":2"));
        assert!(json.contains("\"acked_after_ms\":25"));
        assert!(json.contains("\"delivery_ratio\":0.6667"));
        assert!(json.ends_with("]}"));
        assert_eq!(json, sample().to_json());
        // Structurally balanced outside of string literals.
        let (mut depth, mut in_str, mut escaped) = (0i32, false, false);
        for c in json.chars() {
            if in_str {
                match (escaped, c) {
                    (true, _) => escaped = false,
                    (false, '\\') => escaped = true,
                    (false, '"') => in_str = false,
                    _ => {}
                }
            } else {
                match c {
                    '"' => in_str = true,
                    '{' | '[' => depth += 1,
                    '}' | ']' => depth -= 1,
                    _ => {}
                }
            }
            assert!(depth >= 0);
        }
        assert_eq!((depth, in_str), (0, false));
    }

    #[test]
    fn summary_mentions_the_essentials() {
        let text = sample().summary();
        assert!(text.contains("3 nodes"));
        assert!(text.contains("delivery: 66.67%"));
        assert!(text.contains("slowest delivery: 40 ms"));
    }
}
