//! The KorgNote sync server, written again in Rust beside the Kotlin one in `server/`, for
//! computers with little memory. The Kotlin server is the specification: this one reads the same
//! environment, keeps the same files, and gives the same answers, checked by `conformance/harness.py`.

// The one exception is asking the C library for the time zone, in `clock`, where it says why.
#![deny(unsafe_code)]

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
