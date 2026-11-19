//! Lines on standard output, as the Kotlin server writes its own: when, how serious, what.

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
    let _ = writeln!(std::io::stdout().lock(), "{} {level:<5} {message}", chrono::Local::now().format("%Y-%m-%d %H:%M:%S%.3f"));
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
