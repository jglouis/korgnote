//! Who the server answers, and on which port: paired devices over TLS, this computer on the plain one.
//!
//! These arrive over TLS for real, as a device would, trusting the certificate by its fingerprint as
//! a paired phone does.

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
use std::time::Duration;

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

    /// Asks to pair, and presses Allow on the Devices page for whatever asks meanwhile, as whoever
    /// pairs a phone does.
    async fn pair_with(&self, body: &str) -> Answer {
        self.pair_answered(body, true).await
    }

    /// As [pair_with](Self::pair_with), pressing Allow or Refuse as `allow` says.
    async fn pair_answered(&self, body: &str, allow: bool) -> Answer {
        let asking = self.server.device("POST", "/api/pair", &[("Content-Type", "application/json")], Some(body.as_bytes()));
        tokio::pin!(asking);
        loop {
            tokio::select! {
                answer = &mut asking => return answer,
                _ = tokio::time::sleep(Duration::from_millis(10)) => {
                    for request in self.server.app.approvals.waiting() {
                        self.server.app.approvals.answer(&request.id, allow);
                    }
                }
            }
        }
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

/// The identity a release up to 1.8 (the JVM server) made, which is also a test that a phone paired
/// with such a release finds the server the same after an upgrade.
fn identity(state: &Scratch) -> ServerIdentity {
    let fixture = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/state-1.x");
    for name in ["server-id", "tls.password", "tls.jks"] {
        std::fs::copy(fixture.join(name), state.file(name)).unwrap();
    }
    ServerIdentity::load_or_create(&state.path).unwrap()
}

/// A server with both ports. `addresses` are those it was started with; `interfaces` those it finds for itself.
async fn fixture_with(addresses: &[&str], interfaces: &'static [&'static str], notes: &[(&str, &str)]) -> Fixture {
    fixture_adjusted(addresses, interfaces, notes, |_| {}).await
}

/// As `fixture_with`, with what `adjust` changes of the server before it starts.
async fn fixture_adjusted(addresses: &[&str], interfaces: &'static [&'static str], notes: &[(&str, &str)], adjust: impl FnOnce(&mut App)) -> Fixture {
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
    let mut app = App::new(FileStore::new(&notes.path), access);
    adjust(&mut app);
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

/// What is turned down as no pairing request, for one is read strictly.
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
async fn the_token_is_read_from_the_header() {
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
    // Nor one that says nothing a browser would say of itself, even when the server is asked for as localhost on port 80.
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
    assert!(picture.contains("\nScan it in KorgNote's Settings, under Pair with a computer, within 10 minutes.\nThen press Allow on the Devices page, at /devices on this computer, when the phone asks.\nAddresses: 192.168.1.30, 192.168.1.20  Port: 8443\nOr paste this link there:\nkorgnote://pair?v=1"), "{picture}");
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
async fn a_form_of_either_kind_is_read_and_nothing_else() {
    let f = fixture().await;
    let none = f.server.post("/devices/pair", &[], "").await;
    assert_eq!(none.status, 415);
    assert_eq!(none.text(), "Send the addresses as a form.");
    let json = f.server.post("/devices/pair", &[("Content-Type", "application/json")], r#"{"addresses":"10.0.0.5"}"#).await;
    assert_eq!(json.status, 400);
    assert_eq!(json.text(), "Send the addresses as a form, not as JSON.");
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

// --- What a stranger on the network can hold, and what a revoked device keeps ---

/// Sends `request` on `stream` and reads the answer's head and as much body as it says it has.
async fn ask<S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin>(stream: &mut S, request: &str) -> String {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    stream.write_all(request.as_bytes()).await.unwrap();
    let mut raw = Vec::new();
    let mut buffer = [0u8; 4096];
    loop {
        let read = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buffer)).await.expect("an answer").unwrap();
        assert!(read > 0, "closed before answering: {:?}", String::from_utf8_lossy(&raw));
        raw.extend_from_slice(&buffer[..read]);
        if let Some(end) = raw.windows(4).position(|window| window == b"\r\n\r\n") {
            let head = String::from_utf8_lossy(&raw[..end]).to_ascii_lowercase();
            let length: usize = head.lines().find_map(|line| line.strip_prefix("content-length:")).map(|value| value.trim().parse().unwrap()).unwrap_or(0);
            if raw.len() >= end + 4 + length {
                return String::from_utf8_lossy(&raw).into_owned();
            }
        }
    }
}

/// Reads `stream` until what it has said contains `wanted`, and returns all it said.
async fn read_until<S: tokio::io::AsyncRead + Unpin>(stream: &mut S, wanted: &str) -> String {
    use tokio::io::AsyncReadExt;
    let mut seen = Vec::new();
    let mut buffer = [0u8; 1024];
    while !String::from_utf8_lossy(&seen).contains(wanted) {
        let read = tokio::time::timeout(Duration::from_secs(5), stream.read(&mut buffer)).await.expect("more of the stream").unwrap();
        assert!(read > 0, "ended before {wanted:?}: {:?}", String::from_utf8_lossy(&seen));
        seen.extend_from_slice(&buffer[..read]);
    }
    String::from_utf8_lossy(&seen).into_owned()
}

/// Whether the server closes `stream` within `within`, saying nothing more.
async fn closed_within<S: tokio::io::AsyncRead + Unpin>(stream: &mut S, within: Duration) -> bool {
    use tokio::io::AsyncReadExt;
    let mut buffer = [0u8; 64];
    matches!(tokio::time::timeout(within, stream.read(&mut buffer)).await, Ok(Ok(0)) | Ok(Err(_)))
}

async fn short_lived() -> Fixture {
    fixture_adjusted(&["192.168.1.20"], &[], &[("a.org", "* A\n")], |app| app.unpaired_lifetime = Duration::from_millis(500)).await
}

#[tokio::test(flavor = "multi_thread")]
async fn a_stranger_is_let_go_and_a_paired_device_is_not() {
    use tokio::io::AsyncWriteExt;
    let f = short_lived().await;
    let (_, token) = f.paired().await;
    let request = format!("GET /api/v1/manifest HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {token}\r\n\r\n");
    let mut stranger = f.server.device_connection().await.unwrap();
    let mut trickling = f.server.device_connection().await.unwrap();
    let mut device = f.server.device_connection().await.unwrap();
    assert!(ask(&mut device, &request).await.starts_with("HTTP/1.1 200"));
    // A request begun and never finished buys no more time than none at all.
    trickling.write_all(b"GET /api/v1/manifest HTTP/1.1\r\nHost: localhost\r\n").await.unwrap();
    assert!(closed_within(&mut stranger, Duration::from_secs(3)).await, "a connection that says nothing is closed");
    assert!(closed_within(&mut trickling, Duration::from_secs(3)).await, "so is one that never finishes its request");
    // The device's, past the same time, still answers on the same connection.
    assert!(ask(&mut device, &request).await.starts_with("HTTP/1.1 200"));
}

#[tokio::test(flavor = "multi_thread")]
async fn a_stranger_who_fails_to_show_a_token_is_let_go_too() {
    let f = short_lived().await;
    let mut guessing = f.server.device_connection().await.unwrap();
    let wrong = "GET /api/v1/manifest HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer not-a-token\r\n\r\n";
    assert!(ask(&mut guessing, wrong).await.starts_with("HTTP/1.1 401"));
    assert!(closed_within(&mut guessing, Duration::from_secs(3)).await);
}

#[tokio::test(flavor = "multi_thread")]
async fn strangers_who_fill_the_queue_do_not_keep_a_phone_out() {
    // All from one address, as every connection is behind Docker Desktop.
    let f = fixture_adjusted(&["192.168.1.20"], &[], &[("a.org", "* A\n")], |app| app.most_strangers = 8).await;
    let (_, token) = f.paired().await;
    let request = format!("GET /api/v1/manifest HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {token}\r\n\r\n");
    let mut phone = f.server.device_connection().await.unwrap();
    assert!(ask(&mut phone, &request).await.starts_with("HTTP/1.1 200"));
    let mut strangers = Vec::new();
    for _ in 0..8 {
        strangers.push(f.server.device_connection().await.unwrap());
    }
    // The queue is full of strangers: a new connection takes the place of the oldest, and is served.
    let mut newcomer = f.server.device_connection().await.expect("a place made for it");
    assert!(ask(&mut newcomer, &request).await.starts_with("HTTP/1.1 200"));
    assert!(closed_within(&mut strangers[0], Duration::from_secs(3)).await, "the oldest stranger was let go");
    // Many more strangers after it: neither phone connection is let go for them.
    for _ in 0..20 {
        strangers.push(f.server.device_connection().await.unwrap());
    }
    assert!(ask(&mut phone, &request).await.starts_with("HTTP/1.1 200"));
    assert!(ask(&mut newcomer, &request).await.starts_with("HTTP/1.1 200"));
}

#[tokio::test(flavor = "multi_thread")]
async fn a_pairing_waiting_to_be_allowed_is_not_let_go() {
    let f = fixture_adjusted(&["192.168.1.20"], &[], &[], |app| {
        app.unpaired_lifetime = Duration::from_millis(300);
        app.most_strangers = 2;
    })
    .await;
    let code = f.devices().new_pairing_code();
    let body = format!(r#"{{"code":"{code}","deviceName":"Phone"}}"#);
    let asking = f.server.device("POST", "/api/pair", &[("Content-Type", "application/json")], Some(body.as_bytes()));
    tokio::pin!(asking);
    // Past the strangers' time, and past a queue's worth of strangers: it still waits.
    let flood = async {
        while f.server.app.approvals.waiting().is_empty() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let mut strangers = Vec::new();
        for _ in 0..6 {
            strangers.push(f.server.device_connection().await.unwrap());
        }
        tokio::time::sleep(Duration::from_millis(900)).await;
        let waiting = f.server.app.approvals.waiting();
        assert_eq!(waiting.len(), 1);
        assert!(f.server.app.approvals.answer(&waiting[0].id, true));
        strangers
    };
    let (answer, _strangers) = tokio::join!(&mut asking, flood);
    assert_eq!(answer.status, 200, "{}", answer.text());
    assert_eq!(f.devices().all().len(), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_good_code_alone_does_not_pair() {
    let f = fixture_adjusted(&["192.168.1.20"], &[], &[], |app| app.approval_wait = Duration::from_millis(300)).await;
    // Refused on the Devices page.
    let code = f.devices().new_pairing_code();
    let refused = f.pair_answered(&format!(r#"{{"code":"{code}","deviceName":"Phone"}}"#), false).await;
    assert_eq!(refused.status, 410);
    assert!(f.devices().all().is_empty());
    assert_eq!(f.pair(&code).await.status, 403, "its code is spent");
    // Not answered in time.
    let code = f.devices().new_pairing_code();
    let late = f.server.device("POST", "/api/pair", &[("Content-Type", "application/json")], Some(format!(r#"{{"code":"{code}","deviceName":"Phone"}}"#).as_bytes())).await;
    assert_eq!(late.status, 408);
    assert!(f.devices().all().is_empty());
    assert!(f.server.app.approvals.waiting().is_empty(), "no longer shown");
    // A wrong code is never shown at all.
    assert_eq!(f.server.device("POST", "/api/pair", &[("Content-Type", "application/json")], Some(br#"{"code":"made-up","deviceName":"Phone"}"#)).await.status, 403);
    assert!(f.server.app.approvals.waiting().is_empty());
}

#[tokio::test(flavor = "multi_thread")]
async fn the_devices_page_shows_who_asks_and_only_its_own_pages_answer() {
    let f = fixture().await;
    let code = f.devices().new_pairing_code();
    let body = format!(r#"{{"code":"{code}","deviceName":"Pixel <img src=x onerror=alert(1)>"}}"#);
    let asking = f.server.device("POST", "/api/pair", &[("Content-Type", "application/json")], Some(body.as_bytes()));
    tokio::pin!(asking);
    let page = async {
        while f.server.app.approvals.waiting().is_empty() {
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
        let page = f.server.get("/devices").await;
        assert!(page.text().contains("<b>Pixel &lt;img src=x onerror=alert(1)&gt;</b> asks to pair, from 127.0.0.1"), "{}", page.text());
        let listed = f.server.get("/devices/requests").await.json();
        let id = listed[0]["id"].as_str().unwrap().to_string();
        assert_eq!(listed[0]["name"], "Pixel <img src=x onerror=alert(1)>");
        // Another site's page cannot press Allow.
        let forged = f.server.post(&format!("/devices/requests/{id}/allow"), &[("Origin", "http://evil.example")], "").await;
        assert_eq!(forged.status, 403);
        assert_eq!(f.server.app.approvals.waiting().len(), 1);
        // Nor can a paired device, over TLS.
        assert_eq!(f.server.device("POST", &format!("/devices/requests/{id}/allow"), &[], None).await.status, 404);
        // The page's own button can.
        let allowed = f.server.post(&format!("/devices/requests/{id}/allow"), &[("Origin", &format!("http://localhost:{}", f.server.port())), ("Host", &format!("localhost:{}", f.server.port()))], "").await;
        assert_eq!(allowed.status, 302);
    };
    let (answer, ()) = tokio::join!(&mut asking, page);
    assert_eq!(answer.status, 200);
    assert_eq!(f.devices().all()[0].name, "Pixel <img src=x onerror=alert(1)>");
}

#[tokio::test(flavor = "multi_thread")]
async fn notes_and_codes_are_kept_by_no_cache() {
    let f = fixture().await;
    let (_, token) = f.paired().await;
    let bearer = format!("Bearer {token}");
    for path in ["/api/v1/manifest", "/api/v1/bundle", "/api/v1/files/a.org"] {
        assert_eq!(f.server.device("GET", path, &[("Authorization", &bearer)], None).await.header("cache-control"), Some("no-store"), "{path}");
    }
    assert_eq!(f.server.get("/devices").await.header("cache-control"), Some("no-store"));
    assert_eq!(f.server.get("/api/v1/manifest").await.header("cache-control"), Some("no-store"));
    assert_eq!(f.server.get("/favicon.png").await.header("cache-control"), None, "what says nothing of them may be cached");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_pairing_request_past_its_size_is_not_read_and_spends_no_code() {
    let f = fixture().await;
    let code = f.devices().new_pairing_code();
    let long_name = "x".repeat(20_000);
    let too_long = f.pair_with(&format!(r#"{{"code":"{code}","deviceName":"{long_name}"}}"#)).await;
    assert_eq!(too_long.status, 403);
    assert!(f.devices().all().is_empty());
    assert_eq!(f.pair(&code).await.status, 200, "the code is still good");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_revoked_devices_change_stream_ends_at_once() {
    use tokio::io::AsyncWriteExt;
    let f = fixture().await;
    let (id, token) = f.paired().await;
    let (_, other_token) = f.paired().await;
    let mut revoked = f.server.device_connection().await.unwrap();
    let mut other = f.server.device_connection().await.unwrap();
    for (stream, token) in [(&mut revoked, &token), (&mut other, &other_token)] {
        stream.write_all(format!("GET /api/v1/events HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {token}\r\n\r\n").as_bytes()).await.unwrap();
    }
    read_until(&mut revoked, ": connected").await;
    read_until(&mut other, ": connected").await;
    f.devices().revoke(&id).unwrap();
    // The revoked device's connection is closed, its stream with it, with no change told before.
    let mut rest = Vec::new();
    {
        use tokio::io::AsyncReadExt;
        let ended = tokio::time::timeout(Duration::from_secs(3), revoked.read_to_end(&mut rest)).await;
        assert!(ended.is_ok(), "the connection is closed at once");
    }
    assert!(!String::from_utf8_lossy(&rest).contains("event:"), "{}", String::from_utf8_lossy(&rest));
    // The other device's goes on, and is told of a change made after.
    f.server.app.journal.record("b.org", Some("etag-b"), false);
    assert!(read_until(&mut other, "event: changed").await.contains("b.org"));
}

#[tokio::test(flavor = "multi_thread")]
async fn no_page_is_shown_inside_another_sites() {
    let f = fixture().await;
    for answer in [f.server.get("/devices").await, f.server.get("/api/v1/manifest").await, f.server.get("/nowhere").await] {
        assert_eq!(answer.header("x-frame-options"), Some("DENY"));
        assert_eq!(answer.header("content-security-policy"), Some("frame-ancestors 'none'"));
        assert_eq!(answer.header("x-content-type-options"), Some("nosniff"));
    }
}
