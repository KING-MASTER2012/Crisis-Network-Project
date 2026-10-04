//! Small building blocks: token bucket, penalty score, bounded sets.

use std::collections::{HashMap, HashSet, VecDeque};

use proto::MessageId;

/// An integer token bucket. Tokens are tracked in thousandths so that sub-token
/// refills are not lost.
pub(crate) struct TokenBucket {
    capacity_milli: u64,
    tokens_milli: u64,
    rate_per_sec: u64,
    pub(crate) last_ms: u64,
}

impl TokenBucket {
    /// A full bucket holding `capacity` tokens that refills at `rate_per_sec` tokens per second.
    pub(crate) fn new(capacity: u64, rate_per_sec: u64, now_ms: u64) -> Self {
        let capacity_milli = capacity.saturating_mul(1000);
        Self {
            capacity_milli,
            tokens_milli: capacity_milli,
            rate_per_sec,
            last_ms: now_ms,
        }
    }

    /// Takes `amount` tokens if available.
    pub(crate) fn try_take(&mut self, now_ms: u64, amount: u64) -> bool {
        let elapsed = now_ms.saturating_sub(self.last_ms);
        self.tokens_milli = self
            .tokens_milli
            .saturating_add(elapsed.saturating_mul(self.rate_per_sec))
            .min(self.capacity_milli);
        self.last_ms = self.last_ms.max(now_ms);
        let need = amount.saturating_mul(1000);
        if self.tokens_milli >= need {
            self.tokens_milli -= need;
            true
        } else {
            false
        }
    }
}

/// A misbehaviour score that decays over time.
pub(crate) struct Penalty {
    points: u32,
    last_ms: u64,
}

impl Penalty {
    pub(crate) fn new(now_ms: u64) -> Self {
        Self {
            points: 0,
            last_ms: now_ms,
        }
    }

    /// Applies decay up to `now_ms`, adds `points`, and returns the new total.
    /// `decay_per_sec` must be positive.
    pub(crate) fn add(&mut self, now_ms: u64, points: u32, decay_per_sec: u32) -> u32 {
        let elapsed = now_ms.saturating_sub(self.last_ms);
        let decay = elapsed.saturating_mul(u64::from(decay_per_sec)) / 1000;
        if decay > 0 {
            self.points = self
                .points
                .saturating_sub(u32::try_from(decay).unwrap_or(u32::MAX));
            // Advance only by the time that was converted into whole points, so the
            // fractional remainder is kept for the next call.
            self.last_ms += decay * 1000 / u64::from(decay_per_sec);
        }
        self.points = self.points.saturating_add(points);
        self.points
    }
}

/// A set of message ids that forgets its oldest entry when it is full.
pub(crate) struct BoundedIdSet {
    set: HashSet<MessageId>,
    order: VecDeque<MessageId>,
    cap: usize,
}

impl BoundedIdSet {
    pub(crate) fn new(cap: usize) -> Self {
        Self {
            set: HashSet::new(),
            order: VecDeque::new(),
            cap,
        }
    }

    pub(crate) fn insert(&mut self, id: MessageId) {
        if self.set.insert(id) {
            self.order.push_back(id);
            if self.order.len() > self.cap
                && let Some(old) = self.order.pop_front()
            {
                self.set.remove(&old);
            }
        }
    }

    pub(crate) fn contains(&self, id: &MessageId) -> bool {
        self.set.contains(id)
    }
}

/// Remembers, per message, the hop budget it arrived with.
///
/// "Arrived hops" is the `hops_left` of the frame as received (after the local cap);
/// a message that originated here counts as having arrived with one more hop than
/// the default budget. A message may be forwarded only while this is above zero, and
/// it is forwarded with one hop less. Unknown messages (for example after a restart)
/// get the configured fallback.
pub(crate) struct HopTable {
    map: HashMap<MessageId, u8>,
    order: VecDeque<MessageId>,
    cap: usize,
    fallback: u8,
}

impl HopTable {
    pub(crate) fn new(cap: usize, fallback: u8) -> Self {
        Self {
            map: HashMap::new(),
            order: VecDeque::new(),
            cap,
            fallback,
        }
    }

    pub(crate) fn set(&mut self, id: MessageId, arrived_hops: u8) {
        if self.map.insert(id, arrived_hops).is_none() {
            self.order.push_back(id);
            if self.order.len() > self.cap
                && let Some(old) = self.order.pop_front()
            {
                self.map.remove(&old);
            }
        }
    }

    pub(crate) fn arrived(&self, id: &MessageId) -> u8 {
        self.map.get(id).copied().unwrap_or(self.fallback)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn id(b: u8) -> MessageId {
        MessageId::new([b; 32])
    }

    #[test]
    fn token_bucket_allows_burst_then_refills() {
        let mut b = TokenBucket::new(3, 2, 0);
        assert!(b.try_take(0, 1));
        assert!(b.try_take(0, 1));
        assert!(b.try_take(0, 1));
        assert!(!b.try_take(0, 1));
        // 2 tokens per second: after 500 ms there is exactly one token.
        assert!(!b.try_take(499, 1));
        assert!(b.try_take(500, 1));
        assert!(!b.try_take(500, 1));
        // Never exceeds capacity, however long we wait.
        assert!(b.try_take(1_000_000, 3));
        assert!(!b.try_take(1_000_000, 1));
    }

    #[test]
    fn token_bucket_rejects_amounts_above_capacity_and_ignores_clock_going_back() {
        let mut b = TokenBucket::new(5, 1, 1_000);
        assert!(!b.try_take(1_000, 6));
        assert!(b.try_take(500, 5)); // time moved backwards: no panic, no refill
        assert!(!b.try_take(500, 1));
    }

    #[test]
    fn penalty_decays_and_keeps_fractional_time() {
        let mut p = Penalty::new(0);
        assert_eq!(p.add(0, 10, 1), 10);
        // Calling every 100 ms must still decay one point per second overall.
        let mut total = 0;
        for step in 1..=20 {
            total = p.add(step * 100, 0, 1);
        }
        assert_eq!(total, 8); // 2 seconds elapsed -> 2 points decayed
        // Decay never goes below zero.
        assert_eq!(p.add(1_000_000, 0, 1), 0);
        assert_eq!(p.add(1_000_000, 7, 1), 7);
    }

    #[test]
    fn bounded_set_forgets_the_oldest() {
        let mut s = BoundedIdSet::new(2);
        s.insert(id(1));
        s.insert(id(2));
        s.insert(id(2)); // duplicate does not consume capacity
        assert!(s.contains(&id(1)) && s.contains(&id(2)));
        s.insert(id(3));
        assert!(!s.contains(&id(1)));
        assert!(s.contains(&id(2)) && s.contains(&id(3)));
    }

    #[test]
    fn hop_table_uses_fallback_and_evicts() {
        let mut t = HopTable::new(2, 4);
        assert_eq!(t.arrived(&id(1)), 4);
        t.set(id(1), 9);
        t.set(id(2), 8);
        t.set(id(1), 7); // update, no new slot
        assert_eq!(t.arrived(&id(1)), 7);
        t.set(id(3), 1); // evicts id(1), the oldest
        assert_eq!(t.arrived(&id(1)), 4);
        assert_eq!(t.arrived(&id(3)), 1);
    }
}
