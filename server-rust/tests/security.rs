//! Who the server answers, and on which port: paired devices over TLS, this computer on the plain one.
//!
//! A port of `TransportSecurityTest.kt`, `PairingAddressTest.kt`, `PairingOverTlsTest.kt` and
//! `PlainPortPeerTest.kt`. Where the Kotlin tests call the test host's one port secure to arrive as a
//! device would, these arrive over TLS for real, trusting the certificate by its fingerprint as a
//! paired phone does.

mod common;

use common::{Answer, Scratch, TestServer};
use korgnote_server::devices::{CODE_LIFETIME_MS, DeviceRegistry};
use korgnote_server::http::App;
use korgnote_server::identity::ServerIdentity;
use korgnote_server::pairing::PairRequest;
use korgnote_server::security::{PairingDetails, ServerAccess};
use korgnote_server::store::FileStore;
use std::collections::HashSet;
use std::path::Path;
use std::sync::Arc;
use std::sync::atomic::{AtomicI64, AtomicUsize, Ordering};

struct Fixture {
    clock: Arc<AtomicI64>,
    codes: Arc<AtomicUsize>,
    server: TestServer,
    _state: Scratch,
    _notes: Scratch,
}

impl Fixture {
    fn devices(&self) -> &DeviceRegistry {
        &self.server.app.access.devices
    }

    fn details(&self) -> &PairingDetails {
        self.server.app.access.pairing.as_ref().unwrap()
    }

    async fn pair_with(&self, body: &str) -> Answer {
        self.server.device("POST", "/api/pair", &[("Content-Type", "application/json")], Some(body.as_bytes())).await
    }

    async fn pair(&self, code: &str) -> Answer {
        self.pair_with(&format!(r#"{{"code":"{code}","deviceName":"Phone"}}"#)).await
    }

    /// Pairs a device and returns its id and token.
    async fn paired(&self) -> (String, String) {
        let answer = self.pair(&self.devices().new_pairing_code()).await.json();
        (answer["deviceId"].as_str().unwrap().to_string(), answer["token"].as_str().unwrap().to_string())
    }
}

/// The identity the Kotlin server made, which is also a test that a phone paired with that server
/// would find this one to be the same.
fn identity(state: &Scratch) -> ServerIdentity {
    let fixture = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/kotlin-state");
    for name in ["server-id", "tls.password", "tls.jks"] {
        std::fs::copy(fixture.join(name), state.file(name)).unwrap();
    }
    ServerIdentity::load_or_create(&state.path).unwrap()
}

/// A server with both ports. `addresses` are those it was started with; `interfaces` those it finds for itself.
async fn fixture_with(addresses: &[&str], interfaces: &'static [&'static str], notes: &[(&str, &str)]) -> Fixture {
    let clock = Arc::new(AtomicI64::new(1_000_000));
    let (tokens, codes) = (Arc::new(AtomicUsize::new(0)), Arc::new(AtomicUsize::new(0)));
    let (now, made) = (clock.clone(), codes.clone());
    let devices = DeviceRegistry::with(
        None,
        Box::new(move || now.load(Ordering::SeqCst)),
        Box::new(move || format!("token-{}", tokens.fetch_add(1, Ordering::SeqCst) + 1)),
        Box::new(move || format!("code-{}", made.fetch_add(1, Ordering::SeqCst) + 1)),
    )
    .unwrap();
    let state = Scratch::new("state");
    let identity = identity(&state);
    let details = PairingDetails::with_interfaces(
        "server-1",
        "Laptop",
        &identity.fingerprint,
        8443,
        addresses.iter().map(|address| address.to_string()).collect(),
        Box::new(move || interfaces.iter().map(|address| address.to_string()).collect()),
    );
    let access = ServerAccess { devices: Arc::new(devices), pairing: Some(details), this_computer: HashSet::new() };
    let notes = Scratch::with("notes", notes);
    let app = App::new(FileStore::new(&notes.path), access);
    let server = TestServer::start(app, Some((identity.tls_config().unwrap(), identity.fingerprint.clone()))).await;
    Fixture { clock, codes, server, _state: state, _notes: notes }
}

async fn fixture() -> Fixture {
    fixture_with(&["192.168.1.20"], &[], &[("a.org", "* A\n")]).await
}

/// As in a container started without addresses: none configured, none seen on its interfaces.
async fn nowhere() -> Fixture {
    fixture_with(&[], &[], &[]).await
}

fn bearer(token: &str) -> String {
    format!("Bearer {token}")
}

// --- Over TLS: paired devices only ---

#[tokio::test(flavor = "multi_thread")]
async fn an_unpaired_device_is_refused() {
    let f = fixture().await;
    let none = f.server.device("GET", "/api/v1/manifest", &[], None).await;
    assert_eq!(none.status, 401);
    assert_eq!(none.text(), "This device is not paired, or was revoked. Pair it again from the Devices page.");
    assert_eq!(f.server.device("GET", "/api/v1/manifest", &[("Authorization", "Bearer guess")], None).await.status, 401);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_code_buys_a_token_that_opens_the_api() {
    let f = fixture().await;
    let answer = f.pair(&f.devices().new_pairing_code()).await;
    assert_eq!(answer.status, 200);
    assert_eq!(answer.header("content-type"), Some("application/json"));
    assert!(answer.text().starts_with("{\"deviceId\":\""), "{}", answer.text());
    assert!(answer.text().ends_with("\",\"token\":\"token-1\",\"serverId\":\"server-1\",\"serverName\":\"Laptop\"}"), "{}", answer.text());

    let manifest = f.server.device("GET", "/api/v1/manifest", &[("Authorization", "Bearer token-1")], None).await;
    assert_eq!(manifest.status, 200);
    assert!(manifest.text().contains("a.org"));
    let devices = f.devices().all();
    assert_eq!(devices.len(), 1);
    assert_eq!(devices[0].name, "Phone");
    assert_eq!(devices[0].last_seen, Some(f.clock.load(Ordering::SeqCst)));
}

#[tokio::test(flavor = "multi_thread")]
async fn a_code_works_once() {
    let f = fixture().await;
    let code = f.devices().new_pairing_code();
    assert_eq!(f.pair(&code).await.status, 200);
    let again = f.pair(&code).await;
    assert_eq!(again.status, 403);
    assert_eq!(again.text(), "That pairing code is not valid any more. Show a new one on the Devices page and scan it again.");
    assert_eq!(f.pair("made-up").await.status, 403);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_code_expires() {
    let f = fixture().await;
    let code = f.devices().new_pairing_code();
    f.clock.fetch_add(CODE_LIFETIME_MS + 1, Ordering::SeqCst);
    assert_eq!(f.pair(&code).await.status, 403);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_revoked_device_is_refused_at_once() {
    let f = fixture().await;
    let (id, token) = f.paired().await;
    assert_eq!(f.server.device("GET", "/api/v1/manifest", &[("Authorization", &bearer(&token))], None).await.status, 200);
    f.devices().revoke(&id).unwrap();
    assert_eq!(f.server.device("GET", "/api/v1/manifest", &[("Authorization", &bearer(&token))], None).await.status, 401);
}

#[tokio::test(flavor = "multi_thread")]
async fn pairing_again_replaces_the_old_entry_when_it_can_prove_it_is_that_device() {
    let f = fixture().await;
    let (first, first_token) = f.paired().await;
    let wrong = format!(r#"{{"code":"{}","deviceName":"Phone","replaces":"{first}","replacesToken":"not-its-token"}}"#, f.devices().new_pairing_code());
    assert_eq!(f.pair_with(&wrong).await.status, 200);
    assert_eq!(f.devices().all().len(), 2, "a wrong token replaces nothing");

    let right = format!(r#"{{"code":"{}","deviceName":"Phone","replaces":"{first}","replacesToken":"{first_token}"}}"#, f.devices().new_pairing_code());
    let third = f.pair_with(&right).await.json()["deviceId"].as_str().unwrap().to_string();
    let all = f.devices().all();
    assert_eq!(all.len(), 2);
    assert!(!all.iter().any(|device| device.id == first));
    assert!(all.iter().any(|device| device.id == third));
}

/// What the Kotlin server turns down as no pairing request, for it reads one strictly.
#[tokio::test(flavor = "multi_thread")]
async fn a_request_that_is_not_one_buys_nothing_and_spends_no_code() {
    let f = fixture().await;
    let code = f.devices().new_pairing_code();
    for body in ["", "not json", &format!(r#"{{"code":"{code}"}}"#), &format!(r#"{{"code":"{code}","deviceName":"Phone","extra":1}}"#)] {
        assert_eq!(f.pair_with(body).await.status, 403, "{body}");
    }
    let as_text = f.server.device("POST", "/api/pair", &[("Content-Type", "text/plain")], Some(format!(r#"{{"code":"{code}","deviceName":"Phone"}}"#).as_bytes())).await;
    assert_eq!(as_text.status, 403);
    assert!(f.devices().all().is_empty());
    assert_eq!(f.pair(&code).await.status, 200, "the code is still good");
}

#[tokio::test(flavor = "multi_thread")]
async fn over_tls_there_is_nothing_but_the_api() {
    let f = fixture().await;
    for (method, path) in [("GET", "/devices"), ("POST", "/api/local/pairing"), ("GET", "/"), ("GET", "/api/"), ("GET", "/favicon.png"), ("GET", "/api/v1")] {
        let answer = f.server.device(method, path, &[], Some(b"")).await;
        assert_eq!(answer.status, 404, "{method} {path}");
        assert_eq!(answer.text(), "Not found. The web client and the Devices page are on the computer the server runs on.");
    }
}

/// Routing ignores empty segments and decodes the rest, so these reach the same route as
/// `/api/local/pairing`. A paired device has no business making pairing codes, and with `addresses`
/// it would change where the next QR code sends a phone.
#[tokio::test(flavor = "multi_thread")]
async fn over_tls_the_local_api_cannot_be_reached_by_another_spelling() {
    let f = fixture().await;
    let (_, token) = f.paired().await;
    for path in ["/api/local/pairing", "/api//local/pairing", "/api/%6Cocal/pairing", "/%61pi/local/pairing", "/api/v1/../local/pairing", "/api/v1/%2e%2e/local/pairing"] {
        let answer = f.server.device("POST", &format!("{path}?addresses=6.6.6.6&format=link"), &[("Authorization", &bearer(&token))], Some(b"")).await;
        assert_eq!(answer.status, 404, "{path}");
    }
    assert!(f.details().last_addresses().is_empty());
    assert_eq!(f.codes.load(Ordering::SeqCst), 1, "no code was made but the one that paired");
}

/// Only what a phone uses is there at all, with or without a token.
#[tokio::test(flavor = "multi_thread")]
async fn over_tls_a_paired_device_reaches_the_sync_api_and_pairing_only() {
    let f = fixture().await;
    let (_, token) = f.paired().await;
    let authorized = [("Authorization", bearer(&token))];
    let authorized: Vec<(&str, &str)> = authorized.iter().map(|(name, value)| (*name, value.as_str())).collect();
    assert_eq!(f.server.device("GET", "/api/v1/manifest", &authorized, None).await.status, 200);
    assert_eq!(f.server.device("GET", "/api/v1/bundle", &authorized, None).await.status, 200);
    assert_eq!(f.server.device("GET", "/api/v1/files/a.org", &authorized, None).await.text(), "* A\n");
    assert_eq!(f.server.device("GET", "/api/", &authorized, None).await.status, 404);
    assert_eq!(f.server.device("GET", "/api/", &[], None).await.status, 404);
    // A change from a device needs no page of the server's behind it, and its Host is whatever address it found.
    let pushed = f.server.device("PUT", "/api/v1/files/from-phone.org", &[authorized[0], ("Origin", "https://evil.example"), ("Host", "192.168.1.20:8443")], Some(b"* from the phone\n")).await;
    assert_eq!(pushed.status, 200);
}

#[tokio::test(flavor = "multi_thread")]
async fn the_token_is_read_from_the_header_as_the_kotlin_server_reads_it() {
    let f = fixture().await;
    let (_, token) = f.paired().await;
    let ask = |header: String| {
        let server = &f.server;
        async move { server.device("GET", "/api/v1/manifest", &[("Authorization", &header)], None).await.status }
    };
    assert_eq!(ask(format!("Bearer {token}")).await, 200);
    assert_eq!(ask(format!("Bearer  {token}")).await, 200);
    assert_eq!(ask(token.clone()).await, 200);
    assert_eq!(ask(format!("bearer {token}")).await, 401);
    assert_eq!(ask(format!("Basic {token}")).await, 401);
    assert_eq!(ask(format!("Bearer {token}x")).await, 401);
    assert_eq!(ask("Bearer ".to_string()).await, 401);
}

// --- TLS itself (PairingOverTlsTest) ---

/// Pairing as a phone does it, over a real TLS connection to the server's own certificate: the
/// client trusts that certificate by fingerprint and nothing else.
#[tokio::test(flavor = "multi_thread")]
async fn a_server_with_another_certificate_is_refused() {
    let f = fixture().await;
    let refused = f.server.secure_request(&"00".repeat(32), "GET", "/api/v1/manifest", &[], None).await;
    assert!(refused.is_err(), "a client pinning another certificate must not get as far as asking");
    let accepted = f.server.secure_request(f.server.fingerprint.as_deref().unwrap(), "GET", "/api/v1/manifest", &[], None).await;
    assert_eq!(accepted.unwrap().status, 401);
}

#[tokio::test(flavor = "multi_thread")]
async fn the_plain_port_serves_this_computer_without_a_token() {
    let f = fixture().await;
    assert_eq!(f.server.get("/api/v1/manifest").await.status, 200);
    assert_eq!(f.server.get("/devices").await.status, 200);
}

#[tokio::test(flavor = "multi_thread")]
async fn plain_http_on_the_secure_port_gets_no_answer() {
    let f = fixture().await;
    let stream = tokio::net::TcpStream::connect(("127.0.0.1", f.server.secure_port())).await.unwrap();
    let mut raw = Vec::new();
    {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let mut stream = stream;
        stream.write_all(b"GET /api/v1/manifest HTTP/1.1\r\nHost: localhost\r\n\r\n").await.unwrap();
        let _ = tokio::time::timeout(std::time::Duration::from_secs(5), stream.read_to_end(&mut raw)).await;
    }
    assert!(!raw.starts_with(b"HTTP/"), "{:?}", String::from_utf8_lossy(&raw));
}

// --- The plain port: this computer only ---

#[tokio::test(flavor = "multi_thread")]
async fn a_page_reaching_it_by_another_name_is_refused() {
    let f = fixture().await;
    let ask = |host: &'static str| {
        let server = &f.server;
        async move { server.request("GET", "/api/v1/manifest", &[("Host", host)], None).await }
    };
    let refused = ask("rebound.example:8080").await;
    assert_eq!(refused.status, 403);
    assert_eq!(refused.text(), "This port serves the computer the server runs on, by the name localhost.");
    assert_eq!(ask("192.168.1.20:8080").await.status, 403);
    assert_eq!(ask("127.0.0.1:8080").await.status, 200);
    assert_eq!(ask("10.0.2.2:8080").await.status, 403, "the emulator pairs, as a phone does");
    // Anyone can register a name that starts like a loopback address and point it here.
    for host in ["127.evil.example:8080", "127.0.0.1.evil.example:8080", "127.0.0.evil:8080"] {
        assert_eq!(ask(host).await.status, 403, "{host}");
    }
    assert_eq!(ask("127.1.2.3:8080").await.status, 200);
}

#[tokio::test(flavor = "multi_thread")]
async fn another_sites_page_cannot_change_notes() {
    let f = fixture().await;
    let answer = f.server.put("/api/v1/files/new.org", &[("Origin", "https://evil.example")], "* hi\n").await;
    assert_eq!(answer.status, 403);
    assert_eq!(answer.text(), "Changes are accepted from this server's own pages only.");
    // Nor one that says nothing a browser would say of itself; the Kotlin server lets `null` through when it is asked for as localhost on port 80.
    for origin in ["null", "not a url", ""] {
        assert_eq!(f.server.put("/api/v1/files/new.org", &[("Origin", origin), ("Host", "localhost")], "* hi\n").await.status, 403, "{origin:?}");
    }
    let own = format!("http://localhost:{}", f.server.port());
    let host = format!("localhost:{}", f.server.port());
    assert_eq!(f.server.put("/api/v1/files/new.org", &[("Origin", &own), ("Host", &host)], "* hi\n").await.status, 200);
    assert_eq!(f.server.put("/api/v1/files/new2.org", &[], "* hi\n").await.status, 200, "an app sends no Origin");
    assert_eq!(f.server.request("GET", "/api/v1/manifest", &[("Origin", "https://evil.example")], None).await.status, 200, "a GET changes nothing");
}

#[tokio::test(flavor = "multi_thread")]
async fn the_devices_page_lists_and_revokes() {
    let f = fixture().await;
    f.devices().pair(&PairRequest::new(&f.devices().new_pairing_code(), "Pixel <b>")).unwrap();
    let page = f.server.get("/devices").await;
    assert_eq!(page.header("content-type"), Some("text/html; charset=UTF-8"));
    assert!(page.text().contains("Pixel &lt;b&gt;"), "names are escaped");
    assert!(page.text().contains("<td>never</td>"));

    let id = f.devices().all()[0].id.clone();
    let host = format!("localhost:{}", f.server.port());
    let revoked = f.server.post(&format!("/devices/{id}/revoke"), &[("Origin", &format!("http://{host}")), ("Host", &host)], "").await;
    assert_eq!(revoked.status, 302);
    assert_eq!(revoked.header("location"), Some("/devices"));
    assert!(f.devices().all().is_empty());
    assert_eq!(f.server.post("/devices/nobody/revoke", &[], "").await.status, 302, "revoking nobody is no error");
    assert!(f.server.get("/devices").await.text().contains("No device is paired yet."));
}

/// The browser decodes an attribute before it parses it as script, so no escaping keeps a name
/// safely inside inline script: `Jean's phone` was a syntax error there, and Revoke went ahead
/// unasked, and a device can pick a name that runs.
#[tokio::test(flavor = "multi_thread")]
async fn a_device_name_never_reaches_inline_script() {
    let f = fixture().await;
    let name = "Jean's \"phone\"'); fetch('/x'); ('";
    f.devices().pair(&PairRequest::new(&f.devices().new_pairing_code(), name)).unwrap();
    let page = f.server.get("/devices").await.text();
    assert!(!page.contains("onsubmit"), "{page}");
    assert!(page.contains(&format!("data-name=\"{}\"", name.replace('"', "&quot;"))), "{page}");
    let script = page.split("<script>").nth(1).unwrap().split("</script>").next().unwrap();
    assert!(script.contains("confirm("), "the confirmation is still asked");
    assert!(!script.contains("Jean"), "{script}");
}

fn form(addresses: &str) -> String {
    format!("addresses={}", korgnote_server::text::encode_uri_component(addresses))
}

const FORM: (&str, &str) = ("Content-Type", "application/x-www-form-urlencoded");

#[tokio::test(flavor = "multi_thread")]
async fn the_devices_page_shows_a_pairing_code() {
    let f = fixture().await;
    let page = f.server.post("/devices/pair", &[FORM], &form("10.0.0.5, 192.168.1.20")).await.text();
    assert!(page.contains("<svg"));
    assert!(page.contains("korgnote://pair?v=1&amp;id=server-1"));
    assert!(page.contains("a=10.0.0.5,192.168.1.20"), "{page}");
    assert!(page.contains("&amp;c=code-1</code>"), "{page}");
    assert_eq!(f.details().last_addresses(), ["10.0.0.5", "192.168.1.20"]);
}

#[tokio::test(flavor = "multi_thread")]
async fn the_script_gets_the_code_as_text() {
    let f = fixture().await;
    let text = f.server.post("/api/local/pairing?addresses=192.168.1.30&format=link", &[], "").await.text();
    let text = text.trim();
    assert!(text.starts_with("korgnote://pair?v=1&id=server-1&name=Laptop&a=192.168.1.30,192.168.1.20&p=8443&fp="), "{text}");
    assert!(text.ends_with("&c=code-1"));
    let picture = f.server.post("/api/local/pairing", &[], "").await.text();
    assert!(picture.starts_with("\u{1b}[30;107m"), "{picture}");
    assert!(picture.contains("\nScan it in KorgNote's Settings, under Pair with a computer, within 10 minutes.\nAddresses: 192.168.1.30, 192.168.1.20  Port: 8443\nOr paste this link there:\nkorgnote://pair?v=1"), "{picture}");
    assert!(picture.ends_with("&c=code-2\n"));
}

// --- A pairing code is only made for an address a phone can use (PairingAddressTest) ---

#[tokio::test(flavor = "multi_thread")]
async fn the_devices_page_says_when_it_knows_no_address() {
    let f = nowhere().await;
    let page = f.server.get("/devices").await.text();
    assert!(page.contains("class=warning"), "{page}");
    assert!(page.contains("does not know an address"), "{page}");
    assert!(page.contains("KORGNOTE_ADVERTISED_ADDRESSES"), "{page}");
}

#[tokio::test(flavor = "multi_thread")]
async fn no_address_means_no_code() {
    let f = nowhere().await;
    let answer = f.server.post("/devices/pair", &[FORM], &form("")).await;
    assert_eq!(answer.status, 400);
    let page = answer.text();
    assert!(!page.contains("<svg"), "no QR code");
    assert!(!page.contains("korgnote://pair"), "no link");
    assert!(page.contains("does not know an address"), "{page}");
    assert_eq!(f.codes.load(Ordering::SeqCst), 0, "no code was spent");
}

#[tokio::test(flavor = "multi_thread")]
async fn an_address_typed_on_the_page_makes_the_code() {
    let f = nowhere().await;
    let page = f.server.post("/devices/pair", &[FORM], &form("192.168.1.20")).await.text();
    assert!(page.contains("<svg"));
    assert!(page.contains("a=192.168.1.20&amp;"), "{page}");
    // Remembered for the next code, until the server restarts.
    assert!(!f.server.get("/devices").await.text().contains("class=warning"));
}

#[tokio::test(flavor = "multi_thread")]
async fn what_is_not_an_address_is_turned_down_and_kept_to_correct() {
    let f = nowhere().await;
    let answer = f.server.post("/devices/pair", &[FORM], &form("https://192.168.1.20:8443")).await;
    assert_eq!(answer.status, 400);
    let page = answer.text();
    assert!(!page.contains("<svg"));
    assert!(page.contains("is not an address"), "{page}");
    assert!(page.contains("value=\"https://192.168.1.20:8443\""), "left in the box to correct");
    assert_eq!(f.codes.load(Ordering::SeqCst), 0);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_form_is_read_as_ktor_reads_one() {
    let f = fixture().await;
    let none = f.server.post("/devices/pair", &[], "").await;
    assert_eq!(none.status, 415);
    assert_eq!(none.text(), "Cannot transform this request's content to io.ktor.http.Parameters");
    let json = f.server.post("/devices/pair", &[("Content-Type", "application/json")], r#"{"addresses":"10.0.0.5"}"#).await;
    assert_eq!(json.status, 400);
    assert_eq!(json.text(), "Failed to convert request body to class io.ktor.http.Parameters");
    assert_eq!(f.codes.load(Ordering::SeqCst), 0);
    let multipart = f.server.post("/devices/pair", &[("Content-Type", "multipart/form-data; boundary=x")], "--x\r\nContent-Disposition: form-data; name=\"addresses\"\r\n\r\n10.7.7.7\r\n--x--\r\n").await;
    assert_eq!(multipart.status, 200);
    assert!(multipart.text().contains("a=10.7.7.7,192.168.1.20&amp;"), "{}", multipart.text());
    let encoded = f.server.post("/devices/pair", &[FORM], "addresses=10.3.3.3+10.4.4.4%2C10.5.5.5&addresses=10.9.9.9").await.text();
    assert!(encoded.contains("a=10.3.3.3,10.4.4.4,10.5.5.5,192.168.1.20&amp;"), "{encoded}");
}

#[tokio::test(flavor = "multi_thread")]
async fn the_script_is_told_too() {
    let f = nowhere().await;
    let answer = f.server.post("/api/local/pairing?format=link", &[], "").await;
    assert_eq!(answer.status, 409);
    assert!(answer.text().contains("does not know an address"));
    assert!(answer.text().ends_with(" Or pass it here: /api/local/pairing?addresses=192.168.1.20\n"));
    assert_eq!(f.codes.load(Ordering::SeqCst), 0);

    let bad = f.server.post("/api/local/pairing?addresses=http://x&format=link", &[], "").await;
    assert_eq!(bad.status, 400);
    assert_eq!(bad.text(), "Not an address: http://x\n");

    let given = f.server.post("/api/local/pairing?addresses=192.168.1.30&format=link", &[], "").await;
    assert_eq!(given.status, 200);
    assert!(given.text().contains("&a=192.168.1.30&"));
}

// --- A connection from elsewhere that says it is localhost (PlainPortPeerTest) ---

/// An IPv4 address of this machine that is not loopback, or None on one with none.
fn non_loopback_address() -> Option<std::net::Ipv4Addr> {
    nix::ifaddrs::getifaddrs().ok()?.find_map(|interface| {
        let address = interface.address?.as_sockaddr_in()?.ip();
        let up = interface.flags.contains(nix::net::if_::InterfaceFlags::IFF_UP) && interface.flags.contains(nix::net::if_::InterfaceFlags::IFF_RUNNING);
        (up && !address.is_loopback()).then_some(address)
    })
}

/// The plain port serves the computer the server runs on, and a `Host: localhost` header says
/// nothing about where a connection comes from: the caller writes it. In a container the port
/// listens on every interface inside, so another container on the same network could reach it,
/// claim to be localhost, read and write every note, and pair a device of its own.
///
/// These connect over real sockets, from an address of this machine that is not loopback, which is
/// what such a neighbour looks like to the server.
#[tokio::test(flavor = "multi_thread")]
async fn a_connection_from_elsewhere_is_refused_though_it_says_localhost() {
    let Some(elsewhere) = non_loopback_address() else { return }; // Nothing to connect from: nothing to show.
    let notes = Scratch::with("peer", &[("a.org", "* A\n")]);
    let server = TestServer::start_on(App::new(FileStore::new(&notes.path), ServerAccess::local_only()), None, "0.0.0.0").await;
    let status_of = |method: &'static str, path: &'static str, from: std::net::Ipv4Addr| {
        let port = server.port();
        async move {
            let socket = tokio::net::TcpSocket::new_v4().unwrap();
            socket.bind((from, 0).into()).unwrap();
            let stream = socket.connect((from, port).into()).await.unwrap();
            common::exchange(stream, method, path, &[("Host", "localhost")], None).await
        }
    };
    let refused = status_of("GET", "/api/v1/manifest", elsewhere).await;
    assert_eq!(refused.status, 403);
    assert_eq!(refused.text(), "This port serves the computer the server runs on only.");
    assert_eq!(status_of("POST", "/api/local/pairing", elsewhere).await.status, 403, "nor may it pair a device");
    assert_eq!(status_of("GET", "/api/v1/manifest", std::net::Ipv4Addr::LOCALHOST).await.status, 200);
}

/// In a container the host's published port arrives from the network's gateway, which is this computer too.
#[tokio::test(flavor = "multi_thread")]
async fn the_gateway_of_a_container_is_this_computer() {
    let Some(elsewhere) = non_loopback_address() else { return };
    let notes = Scratch::with("peer", &[("a.org", "* A\n")]);
    let mut access = ServerAccess::local_only();
    access.this_computer.insert(elsewhere.to_string());
    let server = TestServer::start_on(App::new(FileStore::new(&notes.path), access), None, "0.0.0.0").await;
    let socket = tokio::net::TcpSocket::new_v4().unwrap();
    socket.bind((elsewhere, 0).into()).unwrap();
    let stream = socket.connect((elsewhere, server.port()).into()).await.unwrap();
    assert_eq!(common::exchange(stream, "GET", "/api/v1/manifest", &[("Host", "localhost")], None).await.status, 200);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_server_that_pairs_no_devices_says_so() {
    let notes = Scratch::new("peer");
    let server = TestServer::local(FileStore::new(&notes.path)).await;
    for path in ["/api/pair", "/api/local/pairing", "/devices/pair"] {
        let answer = server.post(path, &[FORM], "").await;
        assert_eq!(answer.status, 404, "{path}");
        assert_eq!(answer.text(), "This server does not pair devices.");
    }
    let page = server.get("/devices").await.text();
    assert!(!page.contains("Pair a device"));
    assert!(page.contains("not watched"), "there is no watcher in this test, which the page says");
}

/// The addresses typed are checked one by one, and not counted: enough of them make a link no QR
/// code can hold. That is an answer to whoever typed them, and the server goes on.
#[tokio::test(flavor = "multi_thread")]
async fn too_many_addresses_for_a_code_is_an_error_and_not_the_end_of_the_server() {
    let f = fixture().await;
    let many: Vec<String> = (0..200).map(|n| format!("192.168.{}.{}", n / 250 + 100, n % 250 + 1)).collect();
    let page = f.server.post("/devices/pair", &[FORM], &form(&many.join(", "))).await;
    assert_eq!(page.status, 500);
    assert!(page.text().contains("too long"), "{}", page.text());
    let picture = f.server.post(&format!("/api/local/pairing?addresses={}", many.join(",")), &[], "").await;
    assert_eq!(picture.status, 500);
    // The link itself is still given: it is only the picture of it that cannot be drawn.
    let link = f.server.post(&format!("/api/local/pairing?addresses={}&format=link", many.join(",")), &[], "").await;
    assert_eq!(link.status, 200);
    assert!(link.text().starts_with("korgnote://pair?v=1"));
    // And with fewer, a code is drawn again.
    assert_eq!(f.server.post("/devices/pair", &[FORM], &form("192.168.1.20")).await.status, 200);
}
