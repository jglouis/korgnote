//! What pairing puts on the wire: the link a QR code carries, and the request and answer of
//! `POST /api/pair`, as the apps read and write them in `shared/…/pairing/Pairing.kt`.

use crate::text::encode_uri_component;
use qrcodegen::{QrCode, QrCodeEcc};
use serde::{Deserialize, Serialize};

/// The port the server serves paired devices on, over TLS.
pub const SECURE_PORT: i32 = 8443;
/// The plain one, which only the computer the server runs on can reach.
pub const SERVER_PORT: i32 = 8080;

/// Everything a device needs to pair with a server, as the QR code on the server's Devices page
/// carries it: which server (`server_id`, which stays the same when its addresses change), where to
/// reach it, the SHA-256 `fingerprint` of the certificate it will show, which is the only one the
/// device will ever accept from it, and a one-time `code` that is exchanged for a token of the
/// device's own.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PairingLink {
    pub server_id: String,
    pub server_name: String,
    pub addresses: Vec<String>,
    pub port: i32,
    /// Lower-case hex, no separators.
    pub fingerprint: String,
    pub code: String,
}

impl PairingLink {
    /// Written as a `korgnote://pair?…` link, so that it can be pasted as well as scanned.
    pub fn to_uri(&self) -> String {
        let addresses: Vec<String> = self.addresses.iter().map(|address| encode_uri_component(address)).collect();
        format!(
            "korgnote://pair?v=1&id={}&name={}&a={}&p={}&fp={}&c={}",
            encode_uri_component(&self.server_id),
            encode_uri_component(&self.server_name),
            addresses.join(","),
            self.port,
            self.fingerprint,
            encode_uri_component(&self.code),
        )
    }
}

/// What a device sends to pair, over TLS to the pinned certificate. It is read strictly: a field it does not know, or no name, and the request is not one.
#[derive(Debug, Clone, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PairRequest {
    pub code: String,
    /// What the Devices page calls it: the phone's own name, as its owner set it.
    #[serde(rename = "deviceName")]
    pub device_name: String,
    /// The device's id from an earlier pairing with this server, given up for the new one, so that
    /// scanning again, for new addresses, leaves one entry, not two. Honoured only with its token.
    #[serde(default)]
    pub replaces: Option<String>,
    #[serde(default, rename = "replacesToken")]
    pub replaces_token: Option<String>,
}

impl PairRequest {
    pub fn new(code: &str, device_name: &str) -> Self {
        PairRequest { code: code.to_string(), device_name: device_name.to_string(), replaces: None, replaces_token: None }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct PairResponse {
    #[serde(rename = "deviceId")]
    pub device_id: String,
    /// Sent as `Authorization: Bearer …` from now on. The server keeps only its hash.
    pub token: String,
    #[serde(rename = "serverId")]
    pub server_id: String,
    #[serde(rename = "serverName")]
    pub server_name: String,
}

const BORDER: i32 = 4;

/// A pairing link as a QR code for the Devices page. It keeps the four-module quiet border
/// scanners need, and draws dark modules on light, whatever the page's own colours: many scanners
/// do not read a code drawn the other way round.
pub fn qr_svg(text: &str, module_size: i32) -> Option<String> {
    let qr = encode(text)?;
    let side = qr.size() + BORDER * 2;
    let mut path = String::new();
    for y in 0..qr.size() {
        for x in 0..qr.size() {
            if qr.get_module(x, y) {
                path.push_str(&format!("M{},{}h1v1h-1z", x + BORDER, y + BORDER));
            }
        }
    }
    let px = side * module_size;
    Some(format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 {side} {side}\" width=\"{px}\" height=\"{px}\" shape-rendering=\"crispEdges\">\
         <rect width=\"100%\" height=\"100%\" fill=\"#fff\"/><path d=\"{path}\" fill=\"#000\"/></svg>"
    ))
}

/// The same code as text for a terminal: two rows of modules per line of half blocks, black on
/// white through ANSI colours.
pub fn qr_terminal(text: &str) -> Option<String> {
    let qr = encode(text)?;
    let dark = |x: i32, y: i32| x >= 0 && x < qr.size() && y >= 0 && y < qr.size() && qr.get_module(x, y);
    let mut out = String::new();
    let mut y = -BORDER;
    while y < qr.size() + BORDER {
        out.push_str("\u{1b}[30;107m");
        for x in -BORDER..qr.size() + BORDER {
            out.push(match (dark(x, y), dark(x, y + 1)) {
                (true, true) => '█',
                (true, false) => '▀',
                (false, true) => '▄',
                (false, false) => ' ',
            });
        }
        out.push_str("\u{1b}[0m\n");
        y += 2;
    }
    Some(out)
}

/// None for a text longer than a QR code holds, some 2,300 characters: a pairing link is a few
/// hundred, but its addresses are whatever was typed, and as many.
fn encode(text: &str) -> Option<QrCode> {
    QrCode::encode_text(text, QrCodeEcc::Medium).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn link() -> PairingLink {
        PairingLink {
            server_id: "server-1".into(),
            server_name: "Test Laptop".into(),
            addresses: vec!["192.168.1.30".into(), "fe80::1%en0".into()],
            port: 8443,
            fingerprint: "ab".repeat(32),
            code: "code-1".into(),
        }
    }

    #[test]
    fn the_link_is_written_as_the_apps_read_it() {
        assert_eq!(
            link().to_uri(),
            format!("korgnote://pair?v=1&id=server-1&name=Test%20Laptop&a=192.168.1.30,fe80%3A%3A1%25en0&p=8443&fp={}&c=code-1", "ab".repeat(32)),
        );
    }

    #[test]
    fn a_request_must_name_the_device_and_nothing_unknown() {
        assert!(serde_json::from_str::<PairRequest>(r#"{"code":"c","deviceName":"Phone"}"#).is_ok());
        assert!(serde_json::from_str::<PairRequest>(r#"{"code":"c","deviceName":"Phone","replaces":null,"replacesToken":null}"#).is_ok());
        assert!(serde_json::from_str::<PairRequest>(r#"{"code":"c"}"#).is_err());
        assert!(serde_json::from_str::<PairRequest>(r#"{"code":"c","deviceName":"Phone","extra":1}"#).is_err());
        assert!(serde_json::from_str::<PairRequest>("not json").is_err());
    }

    #[test]
    fn the_answer_keeps_its_field_names() {
        let answer = PairResponse { device_id: "d".into(), token: "t".into(), server_id: "s".into(), server_name: "n".into() };
        assert_eq!(serde_json::to_string(&answer).unwrap(), r#"{"deviceId":"d","token":"t","serverId":"s","serverName":"n"}"#);
    }

    #[test]
    fn the_svg_has_its_quiet_border_and_dark_on_light() {
        let svg = qr_svg("korgnote://pair?v=1", 6).unwrap();
        assert!(svg.starts_with("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 "));
        assert!(svg.contains("fill=\"#fff\""));
        assert!(svg.contains("fill=\"#000\""));
        // Nothing is drawn in the border: the first module is at 4,4 at the nearest.
        assert!(svg.contains("M4,4h1v1h-1z"));
        assert!(!svg.contains("M3,"));
    }

    #[test]
    fn the_terminal_code_is_lines_of_half_blocks() {
        let text = qr_terminal("korgnote://pair?v=1").unwrap();
        assert!(text.lines().all(|line| line.starts_with("\u{1b}[30;107m") && line.ends_with("\u{1b}[0m")));
        assert!(text.contains('█'));
    }

    #[test]
    fn a_link_too_long_for_a_code_draws_none() {
        let long = format!("korgnote://pair?v=1&a={}", "192.168.100.100,".repeat(200));
        assert_eq!(qr_svg(&long, 6), None);
        assert_eq!(qr_terminal(&long), None);
    }
}
