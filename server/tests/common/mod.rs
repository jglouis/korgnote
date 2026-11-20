//! What the tests share: scratch folders of made-up notes, a server on ports of its own, and a
//! client that sends exactly what it is told to, over plain TCP or over TLS to a pinned certificate.
#![allow(dead_code)]

use korgnote_server::http::App;
use korgnote_server::security::ServerAccess;
use korgnote_server::server::{Listening, listen_plain, listen_secure};
use korgnote_server::store::FileStore;
use korgnote_server::text::{random_hex, sha256_hex};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::net::TcpStream;

/// A folder that goes when the test does.
pub struct Scratch {
    pub path: PathBuf,
}

impl Scratch {
    pub fn new(name: &str) -> Scratch {
        let path = std::env::temp_dir().join(format!("korgnote-{name}-{}", random_hex(8)));
        std::fs::create_dir_all(&path).unwrap();
        // Where the notes really are: on macOS the temporary folder is reached through a link.
        Scratch { path: std::fs::canonicalize(&path).unwrap() }
    }

    pub fn with(name: &str, files: &[(&str, &str)]) -> Scratch {
        let scratch = Scratch::new(name);
        for (relative, text) in files {
            scratch.write(relative, text);
        }
        scratch
    }

    pub fn file(&self, relative: &str) -> PathBuf {
        self.path.join(relative)
    }

    pub fn write(&self, relative: &str, text: &str) {
        let file = self.file(relative);
        std::fs::create_dir_all(file.parent().unwrap()).unwrap();
        std::fs::write(file, text).unwrap();
    }

    pub fn read(&self, relative: &str) -> String {
        std::fs::read_to_string(self.file(relative)).unwrap()
    }

    pub fn exists(&self, relative: &str) -> bool {
        self.file(relative).exists()
    }
}

impl Drop for Scratch {
    fn drop(&mut self) {
        // A test may have locked something in it to see what the server does then.
        let _ = std::process::Command::new("chmod").arg("-R").arg("u+rwx").arg(&self.path).status();
        let _ = std::fs::remove_dir_all(&self.path);
    }
}

/// Every regular file under `folder`, for looking at what a delete left in the trash.
pub fn files_under(folder: &Path) -> Vec<PathBuf> {
    let mut found = Vec::new();
    let mut pending = vec![folder.to_path_buf()];
    while let Some(next) = pending.pop() {
        let Ok(entries) = std::fs::read_dir(&next) else { continue };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                pending.push(path);
            } else {
                found.push(path);
            }
        }
    }
    found
}

pub struct Answer {
    pub status: u16,
    pub headers: HashMap<String, String>,
    pub body: Vec<u8>,
}

impl Answer {
    pub fn text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.get(&name.to_ascii_lowercase()).map(String::as_str)
    }

    pub fn etag(&self) -> String {
        self.header("etag").expect("an ETag").to_string()
    }

    pub fn json(&self) -> serde_json::Value {
        serde_json::from_slice(&self.body).unwrap_or_else(|error| panic!("not JSON ({error}): {}", self.text()))
    }
}

/// One request written out by hand, and its answer read to the end of the connection.
pub async fn exchange<S: AsyncRead + AsyncWrite + Unpin>(mut stream: S, method: &str, path: &str, headers: &[(&str, &str)], body: Option<&[u8]>) -> Answer {
    let mut request = format!("{method} {path} HTTP/1.1\r\nConnection: close\r\n");
    if !headers.iter().any(|(name, _)| name.eq_ignore_ascii_case("host")) {
        request.push_str("Host: localhost\r\n");
    }
    for (name, value) in headers {
        request.push_str(&format!("{name}: {value}\r\n"));
    }
    if let Some(body) = body {
        request.push_str(&format!("Content-Length: {}\r\n", body.len()));
    }
    request.push_str("\r\n");
    stream.write_all(request.as_bytes()).await.unwrap();
    if let Some(body) = body {
        // A server may answer and hang up before a body it will not take has all been sent.
        let _ = stream.write_all(body).await;
    }
    let _ = stream.flush().await;
    let mut raw = Vec::new();
    let _ = stream.read_to_end(&mut raw).await;
    parse(&raw)
}

fn parse(raw: &[u8]) -> Answer {
    let split = raw.windows(4).position(|window| window == b"\r\n\r\n").unwrap_or_else(|| panic!("no answer: {:?}", String::from_utf8_lossy(raw)));
    let head = String::from_utf8_lossy(&raw[..split]).into_owned();
    let mut lines = head.split("\r\n");
    let status = lines.next().unwrap().split(' ').nth(1).unwrap().parse().unwrap();
    let headers: HashMap<String, String> = lines.filter_map(|line| line.split_once(':')).map(|(name, value)| (name.trim().to_ascii_lowercase(), value.trim().to_string())).collect();
    let mut body = raw[split + 4..].to_vec();
    if headers.get("transfer-encoding").is_some_and(|value| value.contains("chunked")) {
        let mut out = Vec::new();
        let mut rest = body.as_slice();
        while let Some(end) = rest.windows(2).position(|window| window == b"\r\n") {
            let size = usize::from_str_radix(String::from_utf8_lossy(&rest[..end]).split(';').next().unwrap().trim(), 16).unwrap_or(0);
            rest = &rest[end + 2..];
            if size == 0 || rest.len() < size {
                break;
            }
            out.extend_from_slice(&rest[..size]);
            rest = &rest[(size + 2).min(rest.len())..];
        }
        body = out;
    }
    Answer { status, headers, body }
}

/// A server on ports of its own, serving `app`.
pub struct TestServer {
    pub app: Arc<App>,
    pub plain: Listening,
    pub secure: Option<Listening>,
    /// The fingerprint of the certificate the secure port shows.
    pub fingerprint: Option<String>,
}

impl TestServer {
    /// For this computer only, on the plain port: what the tests of the sync API want.
    pub async fn local(store: FileStore) -> TestServer {
        Self::start(App::new(store, ServerAccess::local_only()), None).await
    }

    pub async fn start(app: App, tls: Option<(rustls::ServerConfig, String)>) -> TestServer {
        Self::start_on(app, tls, "127.0.0.1").await
    }

    pub async fn start_on(app: App, tls: Option<(rustls::ServerConfig, String)>, plain_host: &str) -> TestServer {
        let app = Arc::new(app);
        let plain = listen_plain(app.clone(), plain_host, 0).await.unwrap();
        let (secure, fingerprint) = match tls {
            Some((config, fingerprint)) => (Some(listen_secure(app.clone(), "127.0.0.1", 0, config).await.unwrap()), Some(fingerprint)),
            None => (None, None),
        };
        TestServer { app, plain, secure, fingerprint }
    }

    pub fn port(&self) -> u16 {
        self.plain.address.port()
    }

    pub fn secure_port(&self) -> u16 {
        self.secure.as_ref().expect("a secure port").address.port()
    }

    pub async fn request(&self, method: &str, path: &str, headers: &[(&str, &str)], body: Option<&[u8]>) -> Answer {
        let stream = TcpStream::connect(("127.0.0.1", self.port())).await.unwrap();
        exchange(stream, method, path, headers, body).await
    }

    pub async fn get(&self, path: &str) -> Answer {
        self.request("GET", path, &[], None).await
    }

    pub async fn put(&self, path: &str, headers: &[(&str, &str)], body: &str) -> Answer {
        self.request("PUT", path, headers, Some(body.as_bytes())).await
    }

    pub async fn delete(&self, path: &str, headers: &[(&str, &str)]) -> Answer {
        self.request("DELETE", path, headers, None).await
    }

    pub async fn post(&self, path: &str, headers: &[(&str, &str)], body: &str) -> Answer {
        self.request("POST", path, headers, Some(body.as_bytes())).await
    }

    /// Over TLS, as a paired device asks: trusting the server's certificate by its fingerprint, and
    /// nothing else. `Err` is a connection the client would not make.
    pub async fn secure_request(&self, pinned: &str, method: &str, path: &str, headers: &[(&str, &str)], body: Option<&[u8]>) -> Result<Answer, String> {
        let stream = TcpStream::connect(("127.0.0.1", self.secure_port())).await.map_err(|error| error.to_string())?;
        let connector = tokio_rustls::TlsConnector::from(Arc::new(pinning(pinned)));
        let name = rustls::pki_types::ServerName::try_from("korgnote").unwrap();
        let stream = connector.connect(name, stream).await.map_err(|error| error.to_string())?;
        Ok(exchange(stream, method, path, headers, body).await)
    }

    /// A connection to the secure port made as a paired device makes one, left open for the test
    /// to say what it likes on, or nothing. `Err` is one the server would not take.
    pub async fn device_connection(&self) -> Result<tokio_rustls::client::TlsStream<TcpStream>, String> {
        let stream = TcpStream::connect(("127.0.0.1", self.secure_port())).await.map_err(|error| error.to_string())?;
        let connector = tokio_rustls::TlsConnector::from(Arc::new(pinning(self.fingerprint.as_deref().expect("a secure port"))));
        connector.connect(rustls::pki_types::ServerName::try_from("korgnote").unwrap(), stream).await.map_err(|error| error.to_string())
    }

    /// As `secure_request`, pinning the certificate this server does show.
    pub async fn device(&self, method: &str, path: &str, headers: &[(&str, &str)], body: Option<&[u8]>) -> Answer {
        self.secure_request(self.fingerprint.as_deref().expect("a secure port"), method, path, headers, body).await.unwrap()
    }
}

impl Drop for TestServer {
    fn drop(&mut self) {
        self.plain.stop();
        if let Some(secure) = &self.secure {
            secure.stop();
        }
        if let Some(watcher) = &self.app.watcher {
            watcher.stop();
        }
    }
}

/// Trusts a server by the SHA-256 of its certificate alone, as `PinnedTrustManager` does in the apps.
#[derive(Debug)]
struct Pinned {
    fingerprint: String,
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl rustls::client::danger::ServerCertVerifier for Pinned {
    fn verify_server_cert(
        &self,
        end_entity: &rustls::pki_types::CertificateDer<'_>,
        _intermediates: &[rustls::pki_types::CertificateDer<'_>],
        _server_name: &rustls::pki_types::ServerName<'_>,
        _ocsp_response: &[u8],
        _now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        if sha256_hex(end_entity.as_ref()) == self.fingerprint {
            Ok(rustls::client::danger::ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("The server's certificate is not the one this device paired with.".to_string()))
        }
    }

    fn verify_tls12_signature(&self, message: &[u8], cert: &rustls::pki_types::CertificateDer<'_>, dss: &rustls::DigitallySignedStruct) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(&self, message: &[u8], cert: &rustls::pki_types::CertificateDer<'_>, dss: &rustls::DigitallySignedStruct) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

fn pinning(fingerprint: &str) -> rustls::ClientConfig {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(Pinned { fingerprint: fingerprint.to_string(), provider }))
        .with_no_client_auth()
}

/// Waits until `condition` holds, looking every 50 ms, for at most `seconds`.
pub async fn eventually(seconds: u64, mut condition: impl FnMut() -> bool) -> bool {
    let until = std::time::Instant::now() + std::time::Duration::from_secs(seconds);
    while std::time::Instant::now() < until {
        if condition() {
            return true;
        }
        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
    }
    condition()
}
