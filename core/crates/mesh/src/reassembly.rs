//! Fragment reassembly with a timeout and strict memory limits.

use std::collections::BTreeMap;

use proto::{Fragment, Frame, reassemble};

use crate::event::PeerId;

type Key = (PeerId, [u8; 8]);

/// Why a fragment was refused.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Rejection {
    /// Contradicts an earlier fragment of the same transfer: the peer misbehaves.
    Conflict,
    /// Structurally invalid, or the finished frame does not parse: the peer misbehaves.
    Invalid,
    /// A memory limit was hit. This is our limit, not necessarily the peer's fault.
    Memory,
}

/// Outcome of accepting a fragment.
#[derive(Debug)]
pub(crate) enum Accepted {
    /// More fragments are needed.
    Pending,
    /// The transfer is complete.
    Complete(Frame),
}

struct Partial {
    total: u8,
    parts: Vec<Option<Fragment>>,
    received: usize,
    bytes: usize,
    started_ms: u64,
}

pub(crate) struct Reassembler {
    timeout_ms: u64,
    max_transfers_per_peer: usize,
    max_bytes_per_peer: usize,
    max_bytes_total: usize,
    transfers: BTreeMap<Key, Partial>,
    total_bytes: usize,
}

fn peer_range(peer: PeerId) -> std::ops::RangeInclusive<Key> {
    (peer, [0u8; 8])..=(peer, [0xFFu8; 8])
}

impl Reassembler {
    pub(crate) fn new(
        timeout_ms: u64,
        max_transfers_per_peer: usize,
        max_bytes_per_peer: usize,
        max_bytes_total: usize,
    ) -> Self {
        Self {
            timeout_ms,
            max_transfers_per_peer,
            max_bytes_per_peer,
            max_bytes_total,
            transfers: BTreeMap::new(),
            total_bytes: 0,
        }
    }

    fn remove(&mut self, key: &Key) -> Option<Partial> {
        let removed = self.transfers.remove(key);
        if let Some(p) = &removed {
            self.total_bytes = self.total_bytes.saturating_sub(p.bytes);
        }
        removed
    }

    fn peer_bytes(&self, peer: PeerId) -> usize {
        self.transfers
            .range(peer_range(peer))
            .map(|(_, p)| p.bytes)
            .sum()
    }

    /// Evicts the oldest transfers of `peer` until a new one fits.
    fn make_room(&mut self, peer: PeerId) {
        loop {
            let mine: Vec<(Key, u64)> = self
                .transfers
                .range(peer_range(peer))
                .map(|(k, p)| (*k, p.started_ms))
                .collect();
            if mine.len() < self.max_transfers_per_peer {
                return;
            }
            let Some(oldest) = mine
                .iter()
                .min_by_key(|(_, started)| *started)
                .map(|(k, _)| *k)
            else {
                return;
            };
            self.remove(&oldest);
        }
    }

    pub(crate) fn accept(
        &mut self,
        peer: PeerId,
        fragment: Fragment,
        now_ms: u64,
    ) -> Result<Accepted, Rejection> {
        if fragment.total == 0 || fragment.index >= fragment.total {
            return Err(Rejection::Invalid);
        }
        let key: Key = (peer, fragment.transfer_id);
        let known = self.transfers.contains_key(&key);
        if !known {
            self.make_room(peer);
            self.transfers.insert(
                key,
                Partial {
                    total: fragment.total,
                    parts: vec![None; usize::from(fragment.total)],
                    received: 0,
                    bytes: 0,
                    started_ms: now_ms,
                },
            );
        }

        let peer_bytes = self.peer_bytes(peer);
        let total_bytes = self.total_bytes;
        let (max_peer, max_total) = (self.max_bytes_per_peer, self.max_bytes_total);

        let Some(part) = self.transfers.get_mut(&key) else {
            return Err(Rejection::Invalid);
        };
        if part.total != fragment.total {
            self.remove(&key);
            return Err(Rejection::Conflict);
        }
        let idx = usize::from(fragment.index);
        let same_as_stored = part
            .parts
            .get(idx)
            .and_then(Option::as_ref)
            .map(|f| f.data == fragment.data);
        match same_as_stored {
            Some(true) => return Ok(Accepted::Pending),
            Some(false) => {
                self.remove(&key);
                return Err(Rejection::Conflict);
            }
            None => {}
        }

        let add = fragment.data.len();
        if peer_bytes + add > max_peer || total_bytes + add > max_total {
            self.remove(&key);
            return Err(Rejection::Memory);
        }
        part.bytes += add;
        part.received += 1;
        part.parts[idx] = Some(fragment);
        let complete = part.received == usize::from(part.total);
        self.total_bytes += add;
        if !complete {
            return Ok(Accepted::Pending);
        }

        let Some(done) = self.remove(&key) else {
            return Err(Rejection::Invalid);
        };
        let fragments: Vec<Fragment> = done.parts.into_iter().flatten().collect();
        reassemble(&fragments)
            .map(Accepted::Complete)
            .map_err(|_| Rejection::Invalid)
    }

    /// Drops transfers older than the timeout. Returns how many were dropped.
    pub(crate) fn expire(&mut self, now_ms: u64) -> u64 {
        let stale: Vec<Key> = self
            .transfers
            .iter()
            .filter(|(_, p)| now_ms.saturating_sub(p.started_ms) >= self.timeout_ms)
            .map(|(k, _)| *k)
            .collect();
        for k in &stale {
            self.remove(k);
        }
        stale.len() as u64
    }

    /// The time at which the next transfer would expire, if any.
    pub(crate) fn next_expiry_ms(&self) -> Option<u64> {
        self.transfers
            .values()
            .map(|p| p.started_ms.saturating_add(self.timeout_ms))
            .min()
    }

    /// Forgets everything received from `peer`.
    pub(crate) fn drop_peer(&mut self, peer: PeerId) {
        let keys: Vec<Key> = self
            .transfers
            .range(peer_range(peer))
            .map(|(k, _)| *k)
            .collect();
        for k in &keys {
            self.remove(k);
        }
    }

    #[cfg(test)]
    fn transfer_count(&self) -> usize {
        self.transfers.len()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use proto::{Envelope, EnvelopeBody, Kind, NodeId, split};

    const P1: PeerId = PeerId(1);
    const P2: PeerId = PeerId(2);

    fn frame(payload_len: usize, tag: u8) -> Frame {
        Frame {
            hops_left: 3,
            envelope: Envelope {
                body: EnvelopeBody {
                    kind: Kind::Broadcast,
                    sender: NodeId::new([tag; 32]),
                    recipient: None,
                    created_at_ms: 1,
                    lifetime_secs: 60,
                    ext: vec![],
                    payload: vec![tag; payload_len],
                },
                signature: [7; 64],
            },
        }
    }

    fn reassembler() -> Reassembler {
        Reassembler::new(1_000, 2, 4_096, 8_192)
    }

    fn feed_all(r: &mut Reassembler, peer: PeerId, parts: Vec<Fragment>) -> Option<Frame> {
        let mut done = None;
        for p in parts {
            if let Ok(Accepted::Complete(f)) = r.accept(peer, p, 0) {
                done = Some(f);
            }
        }
        done
    }

    #[test]
    fn completes_out_of_order_with_duplicates() {
        let f = frame(300, 1);
        let mut parts = split(&f, 64).unwrap();
        parts.reverse();
        let dup = parts[1].clone();
        parts.insert(0, dup);
        let mut r = reassembler();
        assert_eq!(feed_all(&mut r, P1, parts), Some(f));
        assert_eq!(r.transfer_count(), 0);
        assert_eq!(r.total_bytes, 0);
    }

    #[test]
    fn conflicting_fragment_drops_the_transfer() {
        let parts = split(&frame(300, 1), 64).unwrap();
        let mut r = reassembler();
        assert!(matches!(
            r.accept(P1, parts[0].clone(), 0),
            Ok(Accepted::Pending)
        ));
        let mut evil = parts[0].clone();
        evil.data[0] ^= 1;
        assert_eq!(r.accept(P1, evil, 0).unwrap_err(), Rejection::Conflict);
        assert_eq!(r.transfer_count(), 0);
        assert_eq!(r.total_bytes, 0);
    }

    #[test]
    fn mismatched_total_is_a_conflict() {
        let parts = split(&frame(300, 1), 64).unwrap();
        let mut r = reassembler();
        r.accept(P1, parts[0].clone(), 0).unwrap();
        let mut odd = parts[1].clone();
        odd.total += 1;
        assert_eq!(r.accept(P1, odd, 0).unwrap_err(), Rejection::Conflict);
    }

    #[test]
    fn incomplete_transfers_expire() {
        let parts = split(&frame(300, 1), 64).unwrap();
        let mut r = reassembler();
        r.accept(P1, parts[0].clone(), 100).unwrap();
        assert_eq!(r.next_expiry_ms(), Some(1_100));
        assert_eq!(r.expire(1_099), 0);
        assert_eq!(r.expire(1_100), 1);
        assert_eq!(r.transfer_count(), 0);
        assert_eq!(r.total_bytes, 0);
        assert_eq!(r.next_expiry_ms(), None);
    }

    #[test]
    fn a_new_transfer_evicts_the_oldest_of_the_same_peer_only() {
        let mut r = reassembler(); // two transfers per peer
        let a = split(&frame(300, 1), 64).unwrap();
        let b = split(&frame(300, 2), 64).unwrap();
        let c = split(&frame(300, 3), 64).unwrap();
        let other = split(&frame(300, 4), 64).unwrap();
        r.accept(P1, a[0].clone(), 10).unwrap();
        r.accept(P1, b[0].clone(), 20).unwrap();
        r.accept(P2, other[0].clone(), 5).unwrap();
        r.accept(P1, c[0].clone(), 30).unwrap(); // evicts `a`
        assert_eq!(r.transfer_count(), 3);
        // `a` restarts from scratch: its remaining fragments alone cannot complete it.
        assert!(feed_all(&mut r, P1, a[1..].to_vec()).is_none());
        // P2's transfer was untouched.
        assert_eq!(
            feed_all(&mut r, P2, other[1..].to_vec()),
            Some(frame(300, 4))
        );
    }

    #[test]
    fn memory_limits_reject_and_free_the_transfer() {
        let mut r = Reassembler::new(1_000, 4, 100, 100);
        let parts = split(&frame(300, 1), 64).unwrap(); // 54 data bytes per fragment
        assert!(matches!(
            r.accept(P1, parts[0].clone(), 0),
            Ok(Accepted::Pending)
        ));
        assert_eq!(
            r.accept(P1, parts[1].clone(), 0).unwrap_err(),
            Rejection::Memory
        );
        assert_eq!(r.transfer_count(), 0);
        assert_eq!(r.total_bytes, 0);
    }

    #[test]
    fn dropping_a_peer_releases_its_memory() {
        let mut r = reassembler();
        let a = split(&frame(300, 1), 64).unwrap();
        r.accept(P1, a[0].clone(), 0).unwrap();
        r.accept(P2, a[0].clone(), 0).unwrap();
        r.drop_peer(P1);
        assert_eq!(r.transfer_count(), 1);
        assert!(r.total_bytes > 0);
        r.drop_peer(P2);
        assert_eq!(r.total_bytes, 0);
    }

    #[test]
    fn invalid_indices_are_rejected() {
        let mut r = reassembler();
        let bad = Fragment {
            transfer_id: [0; 8],
            index: 3,
            total: 3,
            data: vec![1],
        };
        assert_eq!(r.accept(P1, bad, 0).unwrap_err(), Rejection::Invalid);
    }
}
