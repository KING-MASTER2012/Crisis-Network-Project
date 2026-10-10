//! A small, seedable random number generator (SplitMix64).
//!
//! The simulator must not depend on an external random source: the same seed has to
//! give the same run on every machine.

/// A deterministic pseudo-random number generator.
#[derive(Clone, Debug)]
pub struct SimRng {
    state: u64,
}

impl SimRng {
    /// Creates a generator from a seed.
    pub fn new(seed: u64) -> Self {
        Self { state: seed }
    }

    /// The next 64 random bits.
    pub fn next_u64(&mut self) -> u64 {
        self.state = self.state.wrapping_add(0x9E37_79B9_7F4A_7C15);
        let mut z = self.state;
        z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
        z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
        z ^ (z >> 31)
    }

    /// A value in `0..bound` (0 when `bound` is 0 or 1). The tiny modulo bias is
    /// irrelevant for simulation purposes.
    pub fn below(&mut self, bound: u64) -> u64 {
        if bound <= 1 {
            0
        } else {
            self.next_u64() % bound
        }
    }

    /// A value in `0..bound` as a `usize`.
    pub fn below_usize(&mut self, bound: usize) -> usize {
        usize::try_from(self.below(bound as u64)).unwrap_or(0)
    }

    /// `true` with probability `permille / 1000`.
    pub fn chance_permille(&mut self, permille: u32) -> bool {
        self.below(1000) < u64::from(permille)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn same_seed_same_sequence_different_seed_different_sequence() {
        let mut a = SimRng::new(42);
        let mut b = SimRng::new(42);
        let mut c = SimRng::new(43);
        let xs: Vec<u64> = (0..8).map(|_| a.next_u64()).collect();
        let ys: Vec<u64> = (0..8).map(|_| b.next_u64()).collect();
        let zs: Vec<u64> = (0..8).map(|_| c.next_u64()).collect();
        assert_eq!(xs, ys);
        assert_ne!(xs, zs);
    }

    #[test]
    fn bounds_and_chances() {
        let mut r = SimRng::new(1);
        for _ in 0..1000 {
            assert!(r.below(7) < 7);
            assert!(r.below_usize(3) < 3);
        }
        assert_eq!(r.below(0), 0);
        assert_eq!(r.below(1), 0);
        assert!((0..1000).all(|_| !r.chance_permille(0)));
        assert!((0..1000).all(|_| r.chance_permille(1000)));
        let hits = (0..10_000).filter(|_| r.chance_permille(100)).count();
        assert!((700..1300).contains(&hits), "hits = {hits}");
    }
}
