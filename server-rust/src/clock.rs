//! The time, in UTC and in this computer's own zone, as the Kotlin server writes it: in its log, in
//! the names of the trash's folders, and on the Devices page.
//!
//! The zone is the C library's, as `localtime_r` finds it: `TZ`, then `/etc/localtime`. In the
//! container, which has neither, that is UTC, as it is for the Kotlin server's JVM.

use std::time::{SystemTime, UNIX_EPOCH};
use time::{OffsetDateTime, UtcOffset};

pub fn now_millis() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|since| since.as_millis() as i64).unwrap_or(0)
}

/// `epoch_millis` in UTC; the epoch itself for an instant no calendar date reaches.
pub fn utc(epoch_millis: i64) -> OffsetDateTime {
    OffsetDateTime::from_unix_timestamp_nanos(epoch_millis as i128 * 1_000_000).unwrap_or(OffsetDateTime::UNIX_EPOCH)
}

/// `epoch_millis` in this computer's zone, as it was then (summer time or not); UTC where the zone
/// cannot be told.
pub fn local(epoch_millis: i64) -> OffsetDateTime {
    let instant = utc(epoch_millis);
    offset_at(instant.unix_timestamp()).and_then(|offset| instant.checked_to_offset(offset)).unwrap_or(instant)
}

/// `2026-11-19`
pub fn date(at: OffsetDateTime) -> String {
    format!("{:04}-{:02}-{:02}", at.year(), at.month() as u8, at.day())
}

/// `2026-11-19 14:05:09`
pub fn date_time(at: OffsetDateTime) -> String {
    format!("{} {:02}:{:02}:{:02}", date(at), at.hour(), at.minute(), at.second())
}

/// The offset from UTC of this computer's zone at `epoch_seconds`.
///
/// `libc::time_t` is marked deprecated on musl only because the crate means to follow musl's move
/// to a 64-bit `time_t` on 32-bit systems one day. Until then it is the width of the `localtime_r`
/// the crate links to, which musl keeps for that reason, so the two agree on every system.
#[allow(unsafe_code, deprecated)]
fn offset_at(epoch_seconds: i64) -> Option<UtcOffset> {
    let seconds = libc::time_t::try_from(epoch_seconds).ok()?;
    // SAFETY: `localtime_r` writes only into `fields`, which lives until it returns, and reads the
    // zone through the environment. That read is unsafe only beside a write to the environment, and
    // this program makes none: `std::env::set_var` is itself `unsafe` in this edition, so one would
    // stand out, and `#![deny(unsafe_code)]` in lib.rs refuses it anywhere but here.
    let mut fields: libc::tm = unsafe { std::mem::zeroed() };
    let filled = unsafe { libc::localtime_r(&seconds, &mut fields) };
    if filled.is_null() {
        return None;
    }
    UtcOffset::from_whole_seconds(i32::try_from(fields.tm_gmtoff).ok()?).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn utc_is_written_as_the_kotlin_server_writes_it() {
        let at = utc(1_800_000_000_123);
        assert_eq!(date_time(at), "2027-01-15 08:00:00");
        assert_eq!(date(at), "2027-01-15");
        assert_eq!(at.millisecond(), 123);
        assert_eq!(date_time(utc(0)), "1970-01-01 00:00:00");
        assert_eq!(date_time(utc(-1)), "1969-12-31 23:59:59");
        assert_eq!(date_time(utc(i64::MAX)), "1970-01-01 00:00:00", "out of the calendar's reach");
    }

    /// The same instant, moved into the zone, never otherwise changed.
    #[test]
    fn local_time_is_the_same_instant() {
        for millis in [0, 1_800_000_000_123, 1_790_000_000_000] {
            assert_eq!(local(millis), utc(millis));
            assert_eq!(local(millis).unix_timestamp(), utc(millis).unix_timestamp());
        }
    }
}
