//! Lines on standard output, in the form earlier releases wrote them: when, how serious, what.

use std::sync::atomic::{AtomicBool, Ordering};

static DEBUG: AtomicBool = AtomicBool::new(false);

/// Debug lines are written when `KORGNOTE_LOG` is `debug`.
pub fn set_debug(on: bool) {
    DEBUG.store(on, Ordering::Relaxed);
}

pub fn write(level: &str, message: &str) {
    if level == "DEBUG" && !DEBUG.load(Ordering::Relaxed) {
        return;
    }
    // Written without `println!`, which panics when the line cannot be written: a full disk under
    // a redirected log, or a pipe whose reader has gone, must not take the server with it.
    use std::io::Write;
    let now = crate::clock::local(crate::clock::now_millis());
    let _ = writeln!(std::io::stdout().lock(), "{}.{:03} {level:<5} {message}", crate::clock::date_time(now), now.millisecond());
}

#[macro_export]
macro_rules! info {
    ($($argument:tt)*) => { $crate::logging::write("INFO", &format!($($argument)*)) };
}

#[macro_export]
macro_rules! warn {
    ($($argument:tt)*) => { $crate::logging::write("WARN", &format!($($argument)*)) };
}

#[macro_export]
macro_rules! error {
    ($($argument:tt)*) => { $crate::logging::write("ERROR", &format!($($argument)*)) };
}

#[macro_export]
macro_rules! debug {
    ($($argument:tt)*) => { $crate::logging::write("DEBUG", &format!($($argument)*)) };
}
