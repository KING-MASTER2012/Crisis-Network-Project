//! Choosing how long an announcement stays: a few presets, and a custom value from one second
//! up to the protocol's maximum.

/// The shortest lifetime of a message in seconds.
pub const MIN_LIFETIME_SECS: u64 = 1;

/// The lifetime selected when the compose screen opens: one day.
pub const DEFAULT_LIFETIME_SECS: u64 = 86_400;

/// The preset lifetimes in seconds: 15 minutes, 1 hour, 6 hours, 1 day, 3 days, 7 days.
pub const LIFETIME_PRESET_SECS: [u64; 6] = [900, 3_600, 21_600, 86_400, 259_200, 604_800];

/// A unit of the lifetime of an announcement.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum LifetimeUnit {
    /// One second.
    Seconds,
    /// 60 seconds.
    Minutes,
    /// 3 600 seconds.
    Hours,
    /// 86 400 seconds.
    Days,
}

impl LifetimeUnit {
    /// Every unit, smallest first.
    pub const ALL: [Self; 4] = [Self::Seconds, Self::Minutes, Self::Hours, Self::Days];

    /// Seconds in one unit.
    #[must_use]
    pub const fn seconds(self) -> u64 {
        match self {
            Self::Seconds => 1,
            Self::Minutes => 60,
            Self::Hours => 3_600,
            Self::Days => 86_400,
        }
    }
}

/// The presets that do not exceed `max_secs`.
#[must_use]
pub fn lifetime_presets(max_secs: u64) -> Vec<u64> {
    LIFETIME_PRESET_SECS
        .into_iter()
        .filter(|&secs| secs <= max_secs)
        .collect()
}

/// `value` times `unit` in seconds; zero for a value of zero, saturated instead of
/// overflowing.
#[must_use]
pub const fn lifetime_to_seconds(value: u64, unit: LifetimeUnit) -> u64 {
    value.saturating_mul(unit.seconds())
}

/// Whether `secs` is between [`MIN_LIFETIME_SECS`] and `max_secs`.
#[must_use]
pub fn valid_lifetime(secs: u64, max_secs: u64) -> bool {
    (MIN_LIFETIME_SECS..=max_secs).contains(&secs)
}

/// The largest unit that divides `secs` evenly, for showing "15 minutes" instead of "900
/// seconds". Zero, and anything no larger unit divides, stays in seconds.
#[must_use]
pub fn split_lifetime(secs: u64) -> (u64, LifetimeUnit) {
    for unit in LifetimeUnit::ALL.into_iter().rev() {
        let size = unit.seconds();
        if secs >= size && secs.is_multiple_of(size) {
            return (secs / size, unit);
        }
    }
    (secs, LifetimeUnit::Seconds)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_into_the_largest_even_unit() {
        assert_eq!(split_lifetime(900), (15, LifetimeUnit::Minutes));
        assert_eq!(split_lifetime(3_600), (1, LifetimeUnit::Hours));
        assert_eq!(split_lifetime(172_800), (2, LifetimeUnit::Days));
        assert_eq!(split_lifetime(90), (90, LifetimeUnit::Seconds));
        assert_eq!(split_lifetime(0), (0, LifetimeUnit::Seconds));
    }

    #[test]
    fn saturates_instead_of_overflowing() {
        assert_eq!(lifetime_to_seconds(u64::MAX, LifetimeUnit::Days), u64::MAX);
        assert_eq!(lifetime_to_seconds(0, LifetimeUnit::Hours), 0);
        assert_eq!(lifetime_to_seconds(2, LifetimeUnit::Hours), 7_200);
    }

    #[test]
    fn presets_respect_the_maximum() {
        assert_eq!(lifetime_presets(86_400), [900, 3_600, 21_600, 86_400]);
        assert!(lifetime_presets(100).is_empty());
        assert_eq!(lifetime_presets(u64::MAX).len(), 6);
    }

    #[test]
    fn validity_is_inclusive() {
        assert!(!valid_lifetime(0, 100));
        assert!(valid_lifetime(1, 100));
        assert!(valid_lifetime(100, 100));
        assert!(!valid_lifetime(101, 100));
    }
}
