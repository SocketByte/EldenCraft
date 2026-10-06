//! Read-only Elden Ring game-clock sampling and day-phase conversion.
//!
//! Source: pinned fromsoftware-rs `cs/world_area_time.rs`, `dlut/date_time.rs`,
//! and `CSEventWorldAreaTimeCtrl::fade_out_and_pass_time` in `cs/event_man.rs`.
//! `GameMan::world_area_time` is a backup for visits to another player's world;
//! the current clock is `WorldAreaTime::clock`. This is game time, not OS time.

pub const SECONDS_PER_DAY: f32 = 86_400.0;
#[cfg(test)]
const MINECRAFT_TICKS_PER_DAY: u32 = 24_000;

/// Validate SDK components before combining them. Calendar/timezone fields are
/// deliberately ignored: they do not change the local simulated time of day.
pub fn seconds_from_components(hour: u8, minute: u8, second: u8, millisecond: u16) -> Option<f32> {
    if hour >= 24 || minute >= 60 || second >= 60 || millisecond >= 1000 {
        return None;
    }
    let seconds = f64::from(hour) * 3600.0
        + f64::from(minute) * 60.0
        + f64::from(second)
        + f64::from(millisecond) / 1000.0;
    // 23:59:59.999 can round to 86400 in f32. Keep the transport's half-open
    // interval without incorrectly wrapping a pre-midnight sample to midnight.
    let last_representable = f32::from_bits(SECONDS_PER_DAY.to_bits() - 1);
    Some((seconds as f32).min(last_representable))
}

/// Convert clock seconds to Minecraft's day phase: 06:00 -> 0, noon -> 6000,
/// 18:00 -> 12000, midnight -> 18000. Does not advance a day counter or set time.
/// No timezone offset is applied to a simulated game clock.
#[cfg(test)]
fn minecraft_day_ticks(seconds_since_midnight: f32) -> Option<u32> {
    if !seconds_since_midnight.is_finite()
        || !(0.0..SECONDS_PER_DAY).contains(&seconds_since_midnight)
    {
        return None;
    }
    let phase = (f64::from(seconds_since_midnight) - 21_600.0).rem_euclid(86_400.0);
    Some((phase * f64::from(MINECRAFT_TICKS_PER_DAY) / 86_400.0).floor() as u32)
}

/// Sample the current game's clock without retaining an SDK reference.
///
/// # Safety
/// Run only on the authorized game-task thread after the supported executable,
/// offline/session, live-world/player and transition gates pass. No borrowed
/// SDK objects may cross this call. This does not establish those caller gates.
/// Returns None for an unavailable singleton or invalid time components. Never
/// call it from an IO thread, compositor callback, loader lock or external tool.
#[cfg(windows)]
pub unsafe fn read_seconds_since_midnight() -> Option<f32> {
    use eldenring::cs::WorldAreaTime;
    use fromsoftware_shared::FromStatic;
    let date = unsafe { WorldAreaTime::instance().ok()?.clock.date };
    seconds_from_components(
        date.hours(),
        date.minutes(),
        date.seconds(),
        date.millisecond(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn clock_components_and_midnight_rounding_stay_in_bounds() {
        assert_eq!(seconds_from_components(0, 0, 0, 0), Some(0.0));
        assert_eq!(seconds_from_components(6, 0, 0, 0), Some(21_600.0));
        assert_eq!(seconds_from_components(12, 34, 56, 500), Some(45_296.5));
        let last = seconds_from_components(23, 59, 59, 999).unwrap();
        assert!((86_399.0..SECONDS_PER_DAY).contains(&last));
        for (h, m, s, ms) in [(24, 0, 0, 0), (0, 60, 0, 0), (0, 0, 60, 0), (0, 0, 0, 1000)] {
            assert_eq!(seconds_from_components(h, m, s, ms), None);
        }
    }
    #[test]
    fn minecraft_phase_has_six_am_origin_without_timezone_conversion() {
        for (hour, expected) in [
            (0, 18000),
            (3, 21000),
            (6, 0),
            (12, 6000),
            (18, 12000),
            (23, 17000),
        ] {
            let seconds = seconds_from_components(hour, 0, 0, 0).unwrap();
            assert_eq!(minecraft_day_ticks(seconds), Some(expected));
        }
        assert_eq!(minecraft_day_ticks(21_599.0), Some(23_999));
        assert_eq!(minecraft_day_ticks(21_600.0), Some(0));
        for second in 0..86_400 {
            assert!(minecraft_day_ticks(second as f32).unwrap() < 24_000);
        }
    }
    #[test]
    fn invalid_transport_clock_never_becomes_a_valid_day_phase() {
        for seconds in [
            f32::NAN,
            f32::INFINITY,
            f32::NEG_INFINITY,
            -1.0,
            86_400.0,
            100_000.0,
        ] {
            assert_eq!(minecraft_day_ticks(seconds), None);
        }
    }
}
