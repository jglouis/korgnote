//! The KorgNote sync server: it keeps the notes in a folder, serves them to the apps over the sync
//! API, and pairs the devices that may use it. It reads the same environment and keeps the same
//! files as earlier releases, so a state directory or compose file made for them still works.

// The one exception is asking the C library for the time zone, in `clock`, where it says why.
#![deny(unsafe_code)]

pub mod approvals;
pub mod clock;
pub mod config;
pub mod devices;
pub mod devices_page;
pub mod http;
pub mod identity;
pub mod journal;
pub mod logging;
pub mod pairing;
pub mod security;
pub mod server;
pub mod store;
pub mod text;
pub mod watcher;
