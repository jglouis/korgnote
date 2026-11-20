//! Every request, from the bytes hyper hands over to the answer: the sync API, the Devices page and
//! the web client, and what goes on around them that a client can see: how a path finds its route,
//! what a wrong method gets, what is compressed.

use crate::approvals::Approvals;
use crate::devices_page::{devices_body, page, pairing_body};
use crate::journal::ChangeJournal;
use crate::pairing::{PairRequest, PairResponse, PairingLink, qr_terminal};
use crate::security::{Asking, ServerAccess, bearer_token, is_pairing_address, refusal_of};
use crate::store::{FileMeta, FileStore, MAX_BYTES, WriteResult};
use crate::text::{decode_utf8, percent_decode, query_value, trim, utf16_length};
use crate::watcher::NotesWatcher;
use bytes::Bytes;
use flate2::Compression;
use flate2::write::{DeflateEncoder, GzEncoder};
use futures_util::stream::{self, Stream, StreamExt};
use http_body_util::combinators::UnsyncBoxBody;
use http_body_util::{BodyExt, Full, StreamBody};
use hyper::body::{Frame, Incoming};
use hyper::header::{self, HeaderMap, HeaderValue};
use hyper::{Method, Request, Response, StatusCode, Version};
use serde::Serialize;
use std::io::{self, Write};
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;
use tokio::io::AsyncReadExt;
use tokio::sync::mpsc;

pub type Body = UnsyncBoxBody<Bytes, io::Error>;

/// What the server serves, and with what.
pub struct App {
    pub store: Arc<FileStore>,
    pub access: Arc<ServerAccess>,
    pub journal: Arc<ChangeJournal>,
    pub watcher: Option<Arc<NotesWatcher>>,
    /// The build of the web client to serve at `/`, when there is one.
    pub web_client: Option<PathBuf>,
    /// How often a quiet change stream says it is still there. A connection that died without a word
    /// (a phone asleep, or moved to another network) looks exactly like one with nothing to say; the
    /// client takes three missed beats as the former, and reconnects.
    pub heartbeat_every: Duration,
    /// Whether each request is written to the log.
    pub log_requests: bool,
    /// How long a body being sent may say nothing before it is given up on. A phone that leaves
    /// the network in the middle of a save is never heard from again, and what it held of the
    /// upload budget must come back.
    pub body_idle: Duration,
    /// How long a connection to the secure port may stay open without showing a paired device's
    /// token: see `UNPAIRED_LIFETIME`.
    pub unpaired_lifetime: Duration,
    /// How many strangers' connections the secure port holds at once: see `MOST_STRANGERS`.
    pub most_strangers: usize,
    /// Pairings waiting for someone at the computer to allow them.
    pub approvals: Approvals,
    /// How long a pairing waits to be allowed on the Devices page: see `APPROVAL_WAIT`.
    pub approval_wait: Duration,
    /// How much of notes being sent may be held in memory at once, in megabytes: see `UPLOAD_BUDGET_MB`.
    uploads: tokio::sync::Semaphore,
}

impl App {
    /// As the server itself is put together, every request logged.
    pub fn with(store: Arc<FileStore>, access: Arc<ServerAccess>, journal: Arc<ChangeJournal>, watcher: Option<Arc<NotesWatcher>>, web_client: Option<PathBuf>) -> Self {
        App { store, access, journal, watcher, web_client, log_requests: true, ..Self::defaults() }
    }

    pub fn new(store: FileStore, access: ServerAccess) -> Self {
        App { store: Arc::new(store), access: Arc::new(access), log_requests: false, ..Self::defaults() }
    }

    /// Everything but the notes and who may reach them, as the server runs.
    fn defaults() -> Self {
        App {
            store: Arc::new(FileStore::new(PathBuf::new())),
            access: Arc::new(ServerAccess::local_only()),
            journal: Arc::new(ChangeJournal::new()),
            watcher: None,
            web_client: None,
            heartbeat_every: Duration::from_secs(30),
            log_requests: true,
            body_idle: BODY_IDLE,
            unpaired_lifetime: UNPAIRED_LIFETIME,
            most_strangers: MOST_STRANGERS,
            approvals: Approvals::default(),
            approval_wait: APPROVAL_WAIT,
            uploads: tokio::sync::Semaphore::new(UPLOAD_BUDGET_MB),
        }
    }
}

/// Where a connection comes from, and which of the two ports it arrived on.
#[derive(Debug, Clone)]
pub struct Peer {
    pub remote: SocketAddr,
    pub local_port: u16,
    pub secure: bool,
    /// The token of the paired device a request on this connection first showed. Until there is
    /// one, a connection to the secure port is a stranger's, which the server does not keep long;
    /// once the device is revoked, the connection is closed.
    pub paired: Arc<OnceLock<String>>,
    /// Pairings on this connection waiting to be allowed on the Devices page, which can take a
    /// minute or two: a connection with one is not let go meanwhile.
    pub pairing: Arc<AtomicUsize>,
}

impl Peer {
    pub fn new(remote: SocketAddr, local_port: u16, secure: bool) -> Self {
        Peer { remote, local_port, secure, paired: Arc::new(OnceLock::new()), pairing: Arc::new(AtomicUsize::new(0)) }
    }

    /// Whether a request on this connection has shown a paired device's token.
    pub fn is_paired(&self) -> bool {
        self.paired.get().is_some()
    }
}

/// What `/` and `/api/` answer when the web client is not served, which a health check may look for.
const RUNNING: &str = "The KorgNote server is running.";
const TEXT: &str = "text/plain; charset=UTF-8";
const HTML: &str = "text/html; charset=UTF-8";
const JSON: &str = "application/json";
const FAVICON: &[u8] = include_bytes!("../assets/favicon.png");
/// Beyond this, `/bundle` stops and lets the client fetch the rest one by one.
const BUNDLE_BYTE_BUDGET: i64 = 8 * 1024 * 1024;
/// A body shorter than this is not worth compressing.
const MINIMUM_COMPRESSED_SIZE: u64 = 200;
/// How much of a body that is too large is read and thrown away, so that the sender hears `413`
/// rather than a connection cut under it. Past this the connection is cut.
const DRAIN_LIMIT: u64 = 1024 * 1024 * 1024;
const CHUNK: usize = 64 * 1024;
const BODY_IDLE: Duration = Duration::from_secs(60);
/// How long a note may take to arrive whole, however steadily it comes: a sender that trickles a
/// byte a minute would otherwise hold its share of the upload budget for good. A 32 MB note at
/// 64 KB/s is in well within it.
const BODY_WHOLE: Duration = Duration::from_secs(10 * 60);
/// How long a connection to the secure port may stay open, once it is TLS, without a request that
/// shows a paired device's token, a pairing waiting to be allowed aside. Anyone else that holds a
/// connection open, saying nothing or a byte at a time, would otherwise hold it for good.
pub const UNPAIRED_LIFETIME: Duration = Duration::from_secs(30);
/// How many connections to the secure port that have shown no token, strangers' and phones' about
/// to, it holds at once. A new one past it takes the place of the oldest, so a stranger who fills
/// them does not keep a phone out: its connection is the newest, and in a moment it shows its token.
/// Behind Docker Desktop every connection comes from one address, so they cannot be counted by address.
pub const MOST_STRANGERS: usize = 128;
/// How long a pairing waits for someone at the computer to allow it on the Devices page.
pub const APPROVAL_WAIT: Duration = Duration::from_secs(120);
/// A pairing request is a code, a name of 80 characters and perhaps the device it replaces: a few
/// hundred bytes. Anyone can send one, before a token, so no more than this is ever held of it.
const PAIR_BODY_LIMIT: usize = 16 * 1024;
/// A note is held whole while it is checked and written, and one may be 32 MB. So that what the
/// server needs does not grow with how many are sent at once, no more than this much of them is
/// held at a time, and a note that would pass it waits its turn: two of the largest, or any number
/// of the size notes are.
const UPLOAD_BUDGET_MB: usize = 64;

/// How much of the upload budget a body of the length it says it has will take, in megabytes.
fn megabytes_held(content_length: Option<u64>) -> u32 {
    match content_length {
        // Over the limit, said beforehand: it is read and let go, and none of it is held.
        Some(length) if length > MAX_BYTES as u64 => 1,
        Some(length) => length.div_ceil(1024 * 1024).max(1) as u32,
        // It does not say: it may be as large as a note may be.
        None => (MAX_BYTES / (1024 * 1024)) as u32,
    }
}

enum Payload {
    Empty,
    Full(Bytes),
    /// A body that comes in pieces, with its length when that is known beforehand.
    Stream(Box<dyn Stream<Item = io::Result<Bytes>> + Send + Unpin>, Option<u64>),
}

struct Reply {
    status: StatusCode,
    content_type: Option<&'static str>,
    headers: Vec<(header::HeaderName, String)>,
    payload: Payload,
}

impl Reply {
    fn empty(status: StatusCode) -> Reply {
        Reply { status, content_type: None, headers: Vec::new(), payload: Payload::Empty }
    }

    fn text(status: StatusCode, text: impl Into<String>) -> Reply {
        Reply { status, content_type: Some(TEXT), headers: Vec::new(), payload: Payload::Full(Bytes::from(text.into())) }
    }

    fn html(status: StatusCode, text: String) -> Reply {
        Reply { status, content_type: Some(HTML), headers: Vec::new(), payload: Payload::Full(Bytes::from(text)) }
    }

    fn json(value: &impl Serialize) -> Reply {
        let body = serde_json::to_vec(value).expect("what is answered is plain data");
        Reply { status: StatusCode::OK, content_type: Some(JSON), headers: Vec::new(), payload: Payload::Full(Bytes::from(body)) }
    }

    fn with(mut self, name: header::HeaderName, value: impl Into<String>) -> Reply {
        self.headers.push((name, value.into()));
        self
    }

    /// The disk would not say. The log says what and why; the answer does not pass on the error's
    /// own message, which would give the path of the file on this computer.
    fn could_not_read(what: &str, error: &io::Error) -> Reply {
        crate::error!("Could not read {what}: {error}");
        Reply::text(StatusCode::INTERNAL_SERVER_ERROR, "The server could not read the notes; its log says why.")
    }
}

#[derive(Serialize)]
struct ManifestEntry<'a> {
    path: &'a str,
    size: u64,
    mtime: i64,
    etag: &'a str,
}

#[derive(Serialize)]
struct Manifest<'a> {
    generation: i64,
    /// False when this is a delta answered from the journal rather than the whole library.
    complete: bool,
    entries: Vec<ManifestEntry<'a>>,
    deleted: Vec<&'a str>,
}

#[derive(Serialize)]
struct BundleLine<'a> {
    path: &'a str,
    etag: &'a str,
    text: &'a str,
}

#[derive(Serialize)]
struct BundleTruncated {
    truncated: bool,
    generation: i64,
}

/// A pairing waiting to be allowed, as the Devices page's script reads it.
#[derive(Serialize)]
struct WaitingRequest<'a> {
    id: &'a str,
    name: &'a str,
    from: String,
    #[serde(rename = "askedAt")]
    asked_at: i64,
}

#[derive(Serialize)]
struct EtagResponse<'a> {
    etag: &'a str,
}

fn entries_of(metas: &[FileMeta]) -> Vec<ManifestEntry<'_>> {
    metas.iter().map(|meta| ManifestEntry { path: &meta.path, size: meta.size, mtime: meta.mtime_millis, etag: &meta.etag }).collect()
}

/// A header's first value as text. Bytes that are not text are kept as what they are not, so that
/// they match nothing rather than vanish.
fn header_text(headers: &HeaderMap, name: header::HeaderName) -> Option<String> {
    headers.get(name).map(|value| String::from_utf8_lossy(value.as_bytes()).into_owned())
}

async fn on_disk<T: Send + 'static>(work: impl FnOnce() -> T + Send + 'static) -> T {
    tokio::task::spawn_blocking(work).await.expect("work on the disk does not panic")
}

/// One request to one answer.
pub async fn handle(app: Arc<App>, peer: Peer, request: Request<Incoming>) -> Response<Body> {
    let (parts, body) = request.into_parts();
    // A request line that names a whole URL is not a path of this server's, and finds nothing.
    // Over HTTP/2 every request carries its scheme and host, and the path is the path.
    let absolute_form = parts.version < Version::HTTP_2 && parts.uri.scheme().is_some();
    let uri_text = if absolute_form { parts.uri.to_string() } else { parts.uri.path_and_query().map(|pq| pq.as_str().to_string()).unwrap_or_else(|| "/".to_string()) };
    let (raw_path, query) = match uri_text.split_once('?') {
        Some((path, query)) => (path.to_string(), Some(query.to_string())),
        None => (uri_text.clone(), None),
    };
    if app.log_requests {
        let agent = header_text(&parts.headers, header::USER_AGENT).unwrap_or_else(|| "null".to_string());
        crate::info!("Request {} {uri_text} from {}:{} UA={agent}", parts.method, peer.remote.ip(), peer.remote.port());
    }
    let accept_encoding = header_text(&parts.headers, header::ACCEPT_ENCODING);

    let host = header_text(&parts.headers, header::HOST).or_else(|| parts.uri.authority().map(|authority| authority.as_str().to_string()));
    let origin = header_text(&parts.headers, header::ORIGIN);
    let authorization = header_text(&parts.headers, header::AUTHORIZATION);
    let asking = Asking {
        method: parts.method.as_str(),
        raw_path: &raw_path,
        host: host.as_deref(),
        origin: origin.as_deref(),
        authorization: authorization.as_deref(),
        remote: peer.remote.ip(),
        local_port: peer.local_port,
        secure: peer.secure,
    };
    let refusal = refusal_of(&asking, &app.access);
    // Over TLS, a request let through anywhere but pairing showed a paired device's token.
    let token = if peer.secure && matches!(refusal, Ok(None)) && raw_path != "/api/pair" {
        let token = authorization.as_deref().map(|header| bearer_token(header).to_string()).unwrap_or_default();
        peer.paired.get_or_init(|| token.clone());
        Some(token)
    } else {
        None
    };
    let reply = match refusal {
        Ok(Some((status, why))) => Reply::text(StatusCode::from_u16(status).unwrap_or(StatusCode::FORBIDDEN), why),
        Err(error) => Reply::could_not_read("the paired devices", &error),
        Ok(None) if parts.headers.get_all(header::HOST).iter().count() > 1 => Reply::empty(StatusCode::BAD_REQUEST),
        Ok(None) => route(&app, &peer, &parts.method, &parts.headers, &uri_text, &raw_path, query.as_deref(), token, body).await,
    };
    finish(reply, accept_encoding.as_deref())
}

#[derive(Debug, PartialEq, Eq)]
enum Route {
    Root,
    Api,
    Favicon,
    Devices,
    DevicesPair,
    Revoke(String),
    /// The pairings waiting to be allowed, for the Devices page to show as they come.
    Requests,
    /// Allow (true) or refuse the pairing waiting with this id.
    Answer(String, bool),
    LocalPairing,
    Pair,
    Manifest,
    Bundle,
    Events,
    Files(String),
}

/// The route a path names, with the methods it takes and whether it is spelled out whole: a route
/// with a name or a tail in it answers a wrong method as if it were not there.
///
/// Empty segments are ignored and the rest decoded, and `/api` is not `/api/`.
fn find_route(segments: &[String], trailing: bool, web: bool) -> Option<(Route, &'static [&'static str], bool)> {
    let s: Vec<&str> = segments.iter().map(String::as_str).collect();
    const GET: &[&str] = &["GET"];
    const POST: &[&str] = &["POST"];
    Some(match (s.as_slice(), trailing) {
        ([], _) if !web => (Route::Root, GET, true),
        (["api"], true) => (Route::Api, GET, true),
        (["favicon.png"], false) | (["favicon.ico"], false) => (Route::Favicon, GET, true),
        (["devices"], false) => (Route::Devices, GET, true),
        (["devices", "pair"], false) => (Route::DevicesPair, POST, true),
        (["api", "local", "pairing"], false) => (Route::LocalPairing, POST, true),
        (["api", "pair"], false) => (Route::Pair, POST, true),
        (["api", "v1", "manifest"], false) => (Route::Manifest, GET, true),
        (["api", "v1", "bundle"], false) => (Route::Bundle, GET, true),
        (["api", "v1", "events"], false) => (Route::Events, GET, true),
        (["devices", id, "revoke"], false) => (Route::Revoke(id.to_string()), POST, false),
        (["devices", "requests"], false) => (Route::Requests, GET, true),
        (["devices", "requests", id, "allow"], false) => (Route::Answer(id.to_string(), true), POST, false),
        (["devices", "requests", id, "refuse"], false) => (Route::Answer(id.to_string(), false), POST, false),
        (["api", "v1", "files", rest @ ..], _) => {
            let mut path = rest.join("/");
            if trailing && !rest.is_empty() {
                path.push('/');
            }
            (Route::Files(path), &["GET", "PUT", "DELETE"], false)
        }
        _ => return None,
    })
}

/// `token` is the paired device's that asks, over TLS; None on the plain port.
#[allow(clippy::too_many_arguments)]
async fn route(app: &Arc<App>, peer: &Peer, method: &Method, headers: &HeaderMap, uri_text: &str, raw_path: &str, query: Option<&str>, token: Option<String>, body: Incoming) -> Reply {
    let mut segments = Vec::new();
    for segment in raw_path.split('/').filter(|segment| !segment.is_empty()) {
        match percent_decode(segment, false) {
            Some(decoded) => segments.push(decoded),
            None => return Reply::text(StatusCode::BAD_REQUEST, format!("Url decode failed for {uri_text}")),
        }
    }
    let trailing = raw_path.ends_with('/');
    let web = app.web_client.is_some();
    let found = find_route(&segments, trailing, web);
    let name = method.as_str();
    let Some((route, _, _)) = found.filter(|(_, methods, _)| methods.contains(&name)) else {
        // Every GET that no route takes is the web client's, when there is one: a file, or nothing.
        if let (true, Some(web_client)) = (method == Method::GET, &app.web_client) {
            return static_file(web_client, &segments).await;
        }
        let spelled_out = find_route(&segments, trailing, web).is_some_and(|(_, _, constant)| constant);
        return Reply::empty(if spelled_out { StatusCode::METHOD_NOT_ALLOWED } else { StatusCode::NOT_FOUND });
    };
    let query = query.unwrap_or("");
    // Notes, pairing codes and the devices are kept by no cache, the browser's or a phone's own on its
    // disk: only what says nothing of them may be.
    let private = !matches!(route, Route::Root | Route::Api | Route::Favicon);
    let reply = match route {
        Route::Root | Route::Api => Reply::text(StatusCode::OK, RUNNING),
        Route::Favicon => Reply { status: StatusCode::OK, content_type: Some("image/png"), headers: Vec::new(), payload: Payload::Full(Bytes::from_static(FAVICON)) },
        Route::Devices => Reply::html(StatusCode::OK, page(&devices_body(&app.access, &app.approvals.waiting(), app.watcher.as_ref().map(|watcher| watcher.status()).as_ref(), None, None))),
        Route::DevicesPair => devices_pair(app, headers, body).await,
        Route::Requests => {
            let requests = app.approvals.waiting();
            let waiting: Vec<WaitingRequest> = requests.iter().map(|request| WaitingRequest { id: &request.id, name: &request.name, from: request.from.to_string(), asked_at: request.asked_at }).collect();
            Reply::json(&waiting)
        }
        Route::Answer(id, allow) => {
            if app.approvals.answer(&id, allow) {
                crate::info!("Pairing {id} {} on the Devices page", if allow { "allowed" } else { "refused" });
            }
            Reply::empty(StatusCode::FOUND).with(header::LOCATION, "/devices")
        }
        Route::Revoke(id) => match app.access.devices.revoke(&id) {
            Ok(revoked) => {
                if revoked {
                    crate::info!("Revoked device {id}");
                }
                Reply::empty(StatusCode::FOUND).with(header::LOCATION, "/devices")
            }
            Err(error) => Reply::could_not_read("the paired devices", &error),
        },
        Route::LocalPairing => local_pairing(app, query),
        Route::Pair => pair(app, peer, headers, body).await,
        Route::Manifest => manifest(app, query).await,
        Route::Bundle => bundle(app, query).await,
        Route::Events => events(app, token),
        Route::Files(path) => match name {
            "GET" => get_file(app, headers, path).await,
            "PUT" => put_file(app, headers, path, body).await,
            _ => delete_file(app, headers, path).await,
        },
    };
    if private && !reply.headers.iter().any(|(name, _)| name == header::CACHE_CONTROL) { reply.with(header::CACHE_CONTROL, "no-store") } else { reply }
}

// --- The sync API ---

fn bad_query() -> Reply {
    Reply::text(StatusCode::BAD_REQUEST, "Unable to decode query parameters")
}

/// `since`, when the query has one that is a number. `Err` is a query that cannot be read at all.
fn since_of(query: &str) -> Result<Option<i64>, Reply> {
    Ok(query_value(query, "since").map_err(|_| bad_query())?.and_then(|since| since.parse::<i64>().ok()))
}

async fn manifest(app: &Arc<App>, query: &str) -> Reply {
    let since = match since_of(query) {
        Ok(since) => since,
        Err(reply) => return reply,
    };
    if let Some(delta) = since.and_then(|since| app.journal.changes_since(since)) {
        // The journal could answer: send only what moved.
        let store = app.store.clone();
        let paths: Vec<String> = delta.changes.iter().filter(|change| !change.deleted).map(|change| change.path.clone()).collect();
        let metas = on_disk(move || paths.iter().filter_map(|path| store.meta(path).transpose()).collect::<io::Result<Vec<FileMeta>>>()).await;
        return match metas {
            Ok(metas) => Reply::json(&Manifest {
                generation: delta.generation,
                complete: false,
                entries: entries_of(&metas),
                deleted: delta.changes.iter().filter(|change| change.deleted).map(|change| change.path.as_str()).collect(),
            }),
            Err(error) => Reply::could_not_read("the notes that changed", &error),
        };
    }
    // Before the walk, so that a change landing during it is reported again rather than lost.
    let generation = app.journal.generation();
    let store = app.store.clone();
    match on_disk(move || store.list()).await {
        Ok(metas) => Reply::json(&Manifest { generation, complete: true, entries: entries_of(&metas), deleted: Vec::new() }),
        Err(error) => Reply::could_not_read("the notes folder", &error),
    }
}

async fn bundle(app: &Arc<App>, query: &str) -> Reply {
    let since = match since_of(query) {
        Ok(since) => since,
        Err(reply) => return reply,
    };
    let paths: Vec<String> = match since.and_then(|since| app.journal.changes_since(since)) {
        Some(delta) => delta.changes.into_iter().filter(|change| !change.deleted).map(|change| change.path).collect(),
        None => {
            let store = app.store.clone();
            match on_disk(move || store.list()).await {
                Ok(metas) => metas.into_iter().map(|meta| meta.path).collect(),
                Err(error) => return Reply::could_not_read("the notes folder", &error),
            }
        }
    };
    // Streamed, not assembled: the whole library is megabytes and need not be held twice. One
    // line is made at a time, and the next is not read until this one has gone, so a reader that is
    // slow holds one note in memory and no thread.
    let (sender, receiver) = mpsc::channel::<io::Result<Bytes>>(1);
    let (store, journal) = (app.store.clone(), app.journal.clone());
    tokio::spawn(async move {
        let mut budget = BUNDLE_BYTE_BUDGET;
        for path in paths {
            let reading = store.clone();
            let line = on_disk(move || {
                let file = reading.read_with_meta(&path)?;
                // In UTF-16 units, as the budget is defined.
                let length = utf16_length(&file.text) as i64;
                let mut line = serde_json::to_vec(&BundleLine { path: &path, etag: &file.meta.etag, text: &file.text }).expect("a bundle line is plain data");
                line.push(b'\n');
                Some((length, line))
            })
            .await;
            let Some((length, line)) = line else { continue };
            if budget - length < 0 {
                let mut marker = serde_json::to_vec(&BundleTruncated { truncated: true, generation: journal.generation() }).expect("a bundle line is plain data");
                marker.push(b'\n');
                let _ = sender.send(Ok(Bytes::from(marker))).await;
                break;
            }
            budget -= length;
            if sender.send(Ok(Bytes::from(line))).await.is_err() {
                break;
            }
        }
    });
    Reply { status: StatusCode::OK, content_type: Some(JSON), headers: Vec::new(), payload: Payload::Stream(Box::new(receiver_stream(receiver)), None) }
}

fn receiver_stream(receiver: mpsc::Receiver<io::Result<Bytes>>) -> impl Stream<Item = io::Result<Bytes>> + Send + Unpin {
    Box::pin(stream::unfold(receiver, |mut receiver| async move { receiver.recv().await.map(|item| (item, receiver)) }))
}

/// The change stream. `token` is the device's that opened it, over TLS: when that device is
/// revoked the stream ends there and then, with nothing more said to it.
fn events(app: &Arc<App>, token: Option<String>) -> Reply {
    // A hint stream: clients reconcile with the manifest, so a missed event is a delay, never a
    // lost edit. It closes at one missed, for the client to do that.
    let mut changes = app.journal.events_until_one_is_missed();
    let heartbeat_every = app.heartbeat_every;
    let devices = app.access.devices.clone();
    let mut removals = devices.removals();
    let still_paired = move || token.as_deref().is_none_or(|token| devices.knows(token));
    // One frame at a time: a reader that has stopped reading stops this task, and the journal then
    // drops what that reader would have been told, which ends the stream when it reads again.
    let (sender, receiver) = mpsc::channel::<io::Result<Bytes>>(1);
    tokio::spawn(async move {
        if sender.send(Ok(Bytes::from_static(b": connected\n\n"))).await.is_err() {
            return;
        }
        let mut heartbeat = tokio::time::interval_at(tokio::time::Instant::now() + heartbeat_every, heartbeat_every);
        heartbeat.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
        loop {
            let frame = tokio::select! {
                change = changes.next() => match change {
                    Some(entry) => {
                        let name = if entry.deleted { "deleted" } else { "changed" };
                        format!("event: {name}\ndata: {}\n\n", serde_json::to_string(&entry).expect("a change is plain data"))
                    }
                    None => return,
                },
                // A comment line, which SSE clients skip.
                _ = heartbeat.tick() => ": ping\n\n".to_string(),
                _ = sender.closed() => return,
                // A device was taken off: nothing to say, but if it is this one the stream ends below.
                Ok(()) = removals.changed() => String::new(),
            };
            if !still_paired() {
                return;
            }
            if frame.is_empty() {
                continue;
            }
            if sender.send(Ok(Bytes::from(frame))).await.is_err() {
                return;
            }
        }
    });
    Reply {
        status: StatusCode::OK,
        content_type: Some("text/event-stream; charset=UTF-8"),
        headers: vec![(header::CACHE_CONTROL, "no-cache".to_string())],
        payload: Payload::Stream(Box::new(receiver_stream(receiver)), None),
    }
}

fn not_found(path: &str) -> Reply {
    Reply::text(StatusCode::NOT_FOUND, format!("Not found: {path}"))
}

async fn get_file(app: &Arc<App>, headers: &HeaderMap, path: String) -> Reply {
    // A client that has a version is answered from the hash, without reading the file.
    let known = header_text(headers, header::IF_NONE_MATCH).map(|etag| etag.trim_matches('"').to_string());
    let store = app.store.clone();
    let shown = path.clone();
    let answer = on_disk(move || {
        if let Some(known) = known
            && store.meta(&path)?.is_some_and(|meta| meta.etag == known) {
                return Ok(Err(known));
            }
        Ok::<_, io::Error>(Ok(store.read_with_meta(&path)))
    })
    .await;
    match answer {
        Err(error) => Reply::could_not_read(&shown, &error),
        Ok(Err(known)) => Reply::empty(StatusCode::NOT_MODIFIED).with(header::ETAG, known),
        Ok(Ok(None)) => not_found(&shown),
        Ok(Ok(Some(file))) => Reply::text(StatusCode::OK, file.text).with(header::ETAG, file.meta.etag),
    }
}

/// Reads a whole body, as long as it is no more than `limit`. `Ok(None)` is a body that was more:
/// it was read to its end and thrown away, so that the answer finds the sender listening.
async fn read_body(mut body: Incoming, limit: usize, idle: Duration) -> io::Result<Option<Vec<u8>>> {
    use hyper::body::Body as _;
    // Room for what it says it is, all at once: grown as it comes, a large note is copied over and
    // over, and for a moment held twice.
    let expected = body.size_hint().upper().filter(|length| *length <= limit as u64).unwrap_or(0) as usize;
    // A body that says beforehand it is too long is kept none of: held up to the limit, it would
    // let a sender that stops one byte short hold that much for as long as it waits.
    let too_long = body.size_hint().lower() > limit as u64;
    let mut data = Vec::with_capacity(expected);
    let mut seen: u64 = 0;
    loop {
        let frame = match tokio::time::timeout(idle, body.frame()).await {
            Ok(Some(frame)) => frame.map_err(io::Error::other)?,
            Ok(None) => break,
            Err(_) => return Err(io::Error::new(io::ErrorKind::TimedOut, "nothing more of it came")),
        };
        let Ok(chunk) = frame.into_data() else { continue };
        seen += chunk.len() as u64;
        if seen > DRAIN_LIMIT {
            return Err(io::Error::other("a body past all reason"));
        }
        if !too_long && seen as usize <= limit {
            data.extend_from_slice(&chunk);
        } else {
            // No more is kept, and what was is let go.
            data = Vec::new();
        }
    }
    Ok((!too_long && seen as usize <= limit).then_some(data))
}

/// How a body's bytes are to be read as text, from its `Content-Type`.
#[derive(Debug, PartialEq, Eq)]
enum Charset {
    Utf8,
    Latin1,
    Ascii,
    Utf16 { little_endian: Option<bool> },
    /// One this server does not read; the apps send UTF-8.
    Other(String),
}

/// The charset a `Content-Type` names, UTF-8 when it names none. `Err` is a header that is not
/// a content type at all, with the header.
fn charset_of(content_type: Option<&str>) -> Result<Charset, String> {
    let Some(value) = content_type.filter(|value| !crate::text::is_blank(value)) else { return Ok(Charset::Utf8) };
    let mut pieces = value.split(';');
    let kind = pieces.next().unwrap_or("");
    let well_formed = match kind.split_once('/') {
        Some((main, sub)) => {
            let (main, sub) = (trim(main), trim(sub));
            !main.is_empty() && !sub.is_empty() && !main.contains(' ') && !sub.contains(' ') && !sub.contains('/')
        }
        None => trim(kind) == "*",
    };
    if !well_formed {
        return Err(value.to_string());
    }
    let named = pieces.find_map(|piece| {
        let (name, value) = piece.split_once('=')?;
        trim(name).eq_ignore_ascii_case("charset").then(|| trim(value).trim_matches('"').to_ascii_lowercase())
    });
    Ok(match named.as_deref() {
        None | Some("") | Some("utf-8") | Some("utf8") | Some("unicode-1-1-utf-8") => Charset::Utf8,
        Some("iso-8859-1") | Some("iso8859-1") | Some("iso8859_1") | Some("latin1") | Some("l1") | Some("iso_8859-1") | Some("iso-ir-100") | Some("ibm819") | Some("cp819") | Some("csisolatin1") | Some("819") | Some("iso_8859_1") | Some("8859_1") => Charset::Latin1,
        Some("us-ascii") | Some("ascii") | Some("us") | Some("iso646-us") | Some("ansi_x3.4-1968") | Some("ansi_x3.4-1986") | Some("iso_646.irv:1991") | Some("iso-ir-6") | Some("cp367") | Some("ibm367") | Some("csascii") | Some("646") | Some("default") => Charset::Ascii,
        Some("utf-16") | Some("utf16") | Some("utf_16") | Some("unicode") | Some("unicodebig") => Charset::Utf16 { little_endian: None },
        Some("utf-16le") | Some("utf_16le") | Some("x-utf-16le") | Some("unicodelittleunmarked") => Charset::Utf16 { little_endian: Some(true) },
        Some("utf-16be") | Some("utf_16be") | Some("x-utf-16be") | Some("iso-10646-ucs-2") | Some("unicodebigunmarked") => Charset::Utf16 { little_endian: Some(false) },
        // Known charsets this server does not read. A name that is no known charset at all is
        // read as UTF-8.
        Some(other) if is_another_charset(other) => Charset::Other(other.to_string()),
        Some(_) => Charset::Utf8,
    })
}

/// Whether `name`, in lower case, is a known charset that is none of those read here.
fn is_another_charset(name: &str) -> bool {
    const FAMILIES: &[&str] = &["windows-", "cp", "ibm", "iso-8859-", "iso8859", "iso_8859", "iso-2022", "utf-32", "utf_32", "x-", "koi8", "euc", "gb", "big5", "shift", "sjis", "ms", "tis", "mac", "jis", "ksc", "johab", "hz", "ebcdic"];
    FAMILIES.iter().any(|family| name.starts_with(family)) || matches!(name, "latin2" | "latin3" | "latin4" | "latin5" | "latin9" | "l2" | "l3" | "l4" | "l5" | "l9" | "cesu-8" | "utf-7" | "ucs-2" | "ucs-4")
}

/// A body's bytes as the UTF-8 that is written to the note, going through text: what is not text in the charset it was said to be in becomes U+FFFD.
fn text_bytes(body: Vec<u8>, charset: &Charset) -> Vec<u8> {
    match charset {
        Charset::Utf8 | Charset::Other(_) => decode_utf8(body).into_bytes(),
        Charset::Latin1 => body.iter().map(|byte| *byte as char).collect::<String>().into_bytes(),
        Charset::Ascii => body.iter().map(|byte| if byte.is_ascii() { *byte as char } else { '\u{fffd}' }).collect::<String>().into_bytes(),
        Charset::Utf16 { little_endian } => {
            // With no order said, a mark at the start says it and is not text; big-endian without one.
            let (little, data) = match (little_endian, body.as_slice()) {
                (Some(little), data) => (*little, data),
                (None, [0xFF, 0xFE, rest @ ..]) => (true, rest),
                (None, [0xFE, 0xFF, rest @ ..]) => (false, rest),
                (None, data) => (false, data),
            };
            let units: Vec<u16> = data.as_chunks::<2>().0.iter().map(|pair| if little { u16::from_le_bytes(*pair) } else { u16::from_be_bytes(*pair) }).collect();
            let odd = data.len() % 2 == 1;
            // As Java reads it, and so earlier releases, not as Rust would: the first half of a pair with no second
            // half after it is one replacement for itself and for the unit that follows it.
            let mut text = String::with_capacity(units.len());
            let mut at = 0;
            while at < units.len() {
                let unit = units[at];
                at += 1;
                match unit {
                    0xD800..=0xDBFF => match units.get(at) {
                        Some(low @ 0xDC00..=0xDFFF) => {
                            let code = 0x1_0000 + ((u32::from(unit) - 0xD800) << 10) + (u32::from(*low) - 0xDC00);
                            text.push(char::from_u32(code).unwrap_or('\u{fffd}'));
                            at += 1;
                        }
                        Some(_) => {
                            text.push('\u{fffd}');
                            at += 1;
                        }
                        // Cut short by the end of the text, with what is left of it.
                        None => {
                            text.push('\u{fffd}');
                            return text.into_bytes();
                        }
                    },
                    0xDC00..=0xDFFF => text.push('\u{fffd}'),
                    _ => text.push(char::from_u32(u32::from(unit)).unwrap_or('\u{fffd}')),
                }
            }
            if odd {
                text.push('\u{fffd}');
            }
            text.into_bytes()
        }
    }
}

fn respond_to(result: io::Result<WriteResult>, path: &str, written: impl FnOnce(FileMeta) -> Reply) -> Reply {
    match result {
        Err(error) => Reply::could_not_read(path, &error),
        Ok(WriteResult::Written(meta)) => written(meta),
        // 409, not 412: the versions can still be merged, so the client should fetch and try again
        // rather than treat this as a failed precondition.
        Ok(WriteResult::Conflict(current)) => Reply::text(StatusCode::CONFLICT, "Conflict: the file changed on the server").with(header::ETAG, current.etag),
        Ok(WriteResult::NotFound) => not_found(path),
        Ok(WriteResult::Invalid) => Reply::text(StatusCode::UNPROCESSABLE_ENTITY, "invalid path"),
        Ok(WriteResult::TooLarge) => Reply::text(StatusCode::PAYLOAD_TOO_LARGE, "file too large"),
        Ok(WriteResult::Failed(cause)) => {
            crate::error!("Could not change {path}: {cause}");
            Reply::text(StatusCode::INTERNAL_SERVER_ERROR, format!("Could not change {path} on the server; its log says why."))
        }
        Ok(WriteResult::ReadOnly) => Reply::text(StatusCode::FORBIDDEN, "Read-only library"),
    }
}

async fn put_file(app: &Arc<App>, headers: &HeaderMap, path: String, body: Incoming) -> Reply {
    let if_match = header_text(headers, header::IF_MATCH).map(|etag| etag.trim_matches('"').to_string());
    let must_not_exist = header_text(headers, header::IF_NONE_MATCH).is_some_and(|value| trim(&value) == "*");
    let charset = match charset_of(header_text(headers, header::CONTENT_TYPE).as_deref()) {
        Ok(charset) => charset,
        Err(value) => {
            let _ = read_body(body, 0, app.body_idle).await;
            return Reply::text(StatusCode::BAD_REQUEST, format!("Illegal Content-Type format: {value}"));
        }
    };
    // The body is read whole, but no more of it is kept than a note
    // may be: past that it is let go as it comes, and the answer is the same.
    let said = header_text(headers, header::CONTENT_LENGTH).and_then(|length| length.parse::<u64>().ok());
    let _held = app.uploads.acquire_many(megabytes_held(said)).await.expect("the budget is never closed");
    let body = match tokio::time::timeout(BODY_WHOLE, read_body(body, MAX_BYTES, app.body_idle)).await {
        Ok(Ok(body)) => body,
        Ok(Err(error)) if error.kind() == io::ErrorKind::TimedOut => return Reply::text(StatusCode::REQUEST_TIMEOUT, "The note did not arrive whole: nothing more of it came."),
        Ok(Err(error)) => return Reply::text(StatusCode::BAD_REQUEST, format!("The note did not arrive whole: {error}")),
        Err(_) => return Reply::text(StatusCode::REQUEST_TIMEOUT, "The note did not arrive whole: it was too long coming."),
    };
    if let Charset::Other(name) = &charset {
        return Reply::text(StatusCode::UNSUPPORTED_MEDIA_TYPE, format!("This server reads notes sent as UTF-8, ISO-8859-1, US-ASCII or UTF-16, not as {name}."));
    }
    let store = app.store.clone();
    let shown = path.clone();
    let result = on_disk(move || match body {
        Some(body) => store.put(&path, &text_bytes(body, &charset), if_match.as_deref(), must_not_exist),
        // Too large whatever it holds; the store says so in its turn among what else may be wrong.
        None => Ok(store.put_too_large(&path)),
    })
    .await;
    respond_to(result, &shown, |meta| {
        app.journal.record(&shown, Some(&meta.etag), false);
        Reply::json(&EtagResponse { etag: &meta.etag }).with(header::ETAG, meta.etag.clone())
    })
}

async fn delete_file(app: &Arc<App>, headers: &HeaderMap, path: String) -> Reply {
    let Some(if_match) = header_text(headers, header::IF_MATCH).map(|etag| etag.trim_matches('"').to_string()) else {
        return Reply::text(StatusCode::UNPROCESSABLE_ENTITY, "If-Match is required to delete");
    };
    let store = app.store.clone();
    let shown = path.clone();
    let result = on_disk(move || store.delete(&path, Some(&if_match))).await;
    respond_to(result, &shown, |_| {
        app.journal.record(&shown, None, true);
        Reply::empty(StatusCode::NO_CONTENT)
    })
}

// --- Pairing, and the Devices page ---

fn is_json(headers: &HeaderMap) -> bool {
    header_text(headers, header::CONTENT_TYPE).is_some_and(|value| trim(value.split(';').next().unwrap_or("")).eq_ignore_ascii_case(JSON))
}

/// `POST /api/pair`: a device exchanges the one-time code from a pairing QR code for a token of its
/// own. Reached over TLS, to the certificate the same QR code named, so the token cannot go anywhere
/// else.
///
/// A good code is not enough: another app on the phone may have taken it from the QR code. The
/// device is shown on the Devices page, and this answers once someone there allows it (`200` with
/// the token), refuses it (`410`), or `APPROVAL_WAIT` passes (`408`).
async fn pair(app: &Arc<App>, peer: &Peer, headers: &HeaderMap, body: Incoming) -> Reply {
    let Some(details) = &app.access.pairing else { return Reply::text(StatusCode::NOT_FOUND, "This server does not pair devices.") };
    let json = is_json(headers);
    let body = read_body(body, PAIR_BODY_LIMIT, app.body_idle).await.ok().flatten();
    let request = body.filter(|_| json).and_then(|body| serde_json::from_slice::<PairRequest>(&body).ok());
    let Some(request) = request.filter(|request| app.access.devices.take_code(&request.code)) else {
        return Reply::text(StatusCode::FORBIDDEN, "That pairing code is not valid any more. Show a new one on the Devices page and scan it again.");
    };
    let name = crate::devices::shown_name(&request.device_name);
    let from = peer.remote.ip().to_canonical();
    crate::info!("{name} asks to pair, from {from}: waiting for Allow on the Devices page");
    // While it waits, the connection is not a stranger's to let go of.
    let _waiting = PairingInFlight::new(peer);
    let mut asked = app.approvals.ask(&name, from, crate::clock::now_millis());
    match tokio::time::timeout(app.approval_wait, &mut asked.answer).await {
        Ok(Ok(true)) => {}
        Ok(_) => {
            crate::info!("{name} was refused on the Devices page");
            return Reply::text(StatusCode::GONE, "Pairing was refused on the computer's Devices page.");
        }
        Err(_) => {
            crate::info!("{name} was not allowed on the Devices page in time");
            return Reply::text(StatusCode::REQUEST_TIMEOUT, "Nobody allowed this device on the computer's Devices page in time.");
        }
    }
    let (device, token) = match app.access.devices.add(&request) {
        Ok(added) => added,
        Err(error) => return Reply::could_not_read("the paired devices", &error),
    };
    crate::info!("Paired {} ({})", device.name, device.id);
    Reply::json(&PairResponse { device_id: device.id, token, server_id: details.server_id.clone(), server_name: details.server_name.clone() })
}

/// Counts a pairing waiting on a connection, for as long as it waits.
struct PairingInFlight(Arc<AtomicUsize>);

impl PairingInFlight {
    fn new(peer: &Peer) -> Self {
        peer.pairing.fetch_add(1, Ordering::SeqCst);
        PairingInFlight(peer.pairing.clone())
    }
}

impl Drop for PairingInFlight {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::SeqCst);
    }
}

/// When the QR code library refuses the link: a `500`, since the server made the link.
fn too_long_for_a_code() -> Reply {
    Reply::text(StatusCode::INTERNAL_SERVER_ERROR, "The pairing link is too long to draw as a QR code: give fewer addresses.")
}

fn split_addresses(text: &str) -> Vec<String> {
    text.split([',', ' ', '\n']).map(|address| trim(address).to_string()).filter(|address| !address.is_empty()).collect()
}

fn new_link(app: &App, details: &crate::security::PairingDetails) -> PairingLink {
    PairingLink {
        server_id: details.server_id.clone(),
        server_name: details.server_name.clone(),
        addresses: details.addresses(),
        port: details.port,
        fingerprint: details.fingerprint.clone(),
        code: app.access.devices.new_pairing_code(),
    }
}

/// The first field called `name` in a `multipart/form-data` body, as text. A browser sends the
/// Devices page's form URL-encoded; this is for whatever else posts one.
fn multipart_value(content_type: &str, body: &[u8], name: &str) -> Option<String> {
    let boundary = content_type.split(';').skip(1).find_map(|piece| {
        let (key, value) = piece.split_once('=')?;
        trim(key).eq_ignore_ascii_case("boundary").then(|| trim(value).trim_matches('"').to_string())
    })?;
    let body = decode_utf8(body.to_vec());
    let delimiter = format!("--{boundary}");
    body.split(delimiter.as_str()).skip(1).find_map(|part| {
        let part = part.strip_prefix("\r\n")?;
        let (head, value) = part.split_once("\r\n\r\n")?;
        let named = head.lines().any(|line| {
            let Some((header, rest)) = line.split_once(':') else { return false };
            trim(header).eq_ignore_ascii_case("content-disposition")
                && rest.split(';').any(|piece| piece.split_once('=').is_some_and(|(key, value)| trim(key).eq_ignore_ascii_case("name") && trim(value).trim_matches('"') == name))
        });
        named.then(|| value.strip_suffix("\r\n").unwrap_or(value).to_string())
    })
}

async fn devices_pair(app: &Arc<App>, headers: &HeaderMap, body: Incoming) -> Reply {
    let Some(details) = &app.access.pairing else { return Reply::text(StatusCode::NOT_FOUND, "This server does not pair devices.") };
    // A form of either kind is read, JSON is a body that cannot be made a form of, and anything
    // else is no form at all.
    let content_type = header_text(headers, header::CONTENT_TYPE).unwrap_or_default();
    let kind = trim(content_type.split(';').next().unwrap_or("")).to_ascii_lowercase();
    let body = read_body(body, 1024 * 1024, app.body_idle).await.ok().flatten().unwrap_or_default();
    let typed_text = match kind.as_str() {
        "application/x-www-form-urlencoded" => query_value(&decode_utf8(body), "addresses").ok().flatten(),
        "multipart/form-data" => multipart_value(&content_type, &body, "addresses"),
        JSON => return Reply::text(StatusCode::BAD_REQUEST, "Send the addresses as a form, not as JSON."),
        _ => return Reply::text(StatusCode::UNSUPPORTED_MEDIA_TYPE, "Send the addresses as a form."),
    }
    .unwrap_or_default();
    let typed = split_addresses(&typed_text);
    let status = app.watcher.as_ref().map(|watcher| watcher.status());
    // A code is only made for somewhere a phone can go: an address it cannot use, or none at
    // all, gets the form back with what to do, rather than a QR code that leads nowhere.
    let wrong: Vec<&str> = typed.iter().map(String::as_str).filter(|address| !is_pairing_address(address)).collect();
    if !wrong.is_empty() {
        let problem = format!("“{}” is not an address. Type addresses only, such as 192.168.1.20, separated by commas: no http://, no port.", wrong.join(", "));
        return Reply::html(StatusCode::BAD_REQUEST, page(&devices_body(&app.access, &app.approvals.waiting(), status.as_ref(), Some(&problem), Some(&typed_text))));
    }
    if !typed.is_empty() {
        details.set_last_addresses(typed);
    }
    if let Some(advice) = details.missing_address_advice() {
        return Reply::html(StatusCode::BAD_REQUEST, page(&devices_body(&app.access, &app.approvals.waiting(), status.as_ref(), Some(advice), Some(&typed_text))));
    }
    match pairing_body(&new_link(app, details)) {
        Some(body) => Reply::html(StatusCode::OK, page(&body)),
        None => too_long_for_a_code(),
    }
}

/// `POST /api/local/pairing`, which `scripts/pair.sh` calls with the host's addresses and prints
/// the answer in the terminal.
fn local_pairing(app: &Arc<App>, query: &str) -> Reply {
    let Some(details) = &app.access.pairing else { return Reply::text(StatusCode::NOT_FOUND, "This server does not pair devices.") };
    let (Ok(addresses), Ok(format)) = (query_value(query, "addresses"), query_value(query, "format")) else { return bad_query() };
    let given = addresses.map(|addresses| split_addresses(&addresses)).unwrap_or_default();
    let wrong: Vec<&str> = given.iter().map(String::as_str).filter(|address| !is_pairing_address(address)).collect();
    if !wrong.is_empty() {
        return Reply::text(StatusCode::BAD_REQUEST, format!("Not an address: {}\n", wrong.join(", ")));
    }
    if !given.is_empty() {
        details.set_last_addresses(given);
    }
    if let Some(advice) = details.missing_address_advice() {
        return Reply::text(StatusCode::CONFLICT, format!("{advice} Or pass it here: /api/local/pairing?addresses=192.168.1.20\n"));
    }
    let link = new_link(app, details);
    let uri = link.to_uri();
    if format.as_deref() == Some("link") {
        return Reply::text(StatusCode::OK, format!("{uri}\n"));
    }
    let Some(picture) = qr_terminal(&uri) else { return too_long_for_a_code() };
    Reply::text(
        StatusCode::OK,
        format!(
            "{picture}\nScan it in KorgNote's Settings, under Pair with a computer, within 10 minutes.\nThen press Allow on the Devices page, at /devices on this computer, when the phone asks.\nAddresses: {}  Port: {}\nOr paste this link there:\n{uri}\n",
            link.addresses.join(", "),
            link.port
        ),
    )
}

// --- The web client ---

/// The type of a file of the web client, by its extension.
fn content_type_of(name: &str) -> &'static str {
    let extension = name.rsplit_once('.').map(|(_, extension)| extension.to_ascii_lowercase()).unwrap_or_default();
    match extension.as_str() {
        "html" | "htm" => HTML,
        "js" | "mjs" => "text/javascript; charset=UTF-8",
        "css" => "text/css; charset=UTF-8",
        "txt" => TEXT,
        "md" => "text/markdown; charset=UTF-8",
        "csv" => "text/csv; charset=UTF-8",
        "xml" => "application/xml; charset=UTF-8",
        "svg" => "image/svg+xml; charset=UTF-8",
        "json" => JSON,
        "wasm" => "application/wasm",
        "map" => "application/x-navimap",
        "webmanifest" => "application/manifest+json",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "ico" => "image/x-icon",
        "webp" => "image/webp",
        "ttf" => "font/ttf",
        "otf" => "font/otf",
        "woff" => "font/woff",
        "woff2" => "font/woff2",
        "pdf" => "application/pdf",
        "zip" => "application/zip",
        "mp4" => "video/mp4",
        "mp3" => "audio/mpeg",
        "bin" => "application/macbinary",
        _ => "application/octet-stream",
    }
}

/// A file of the web client, or `index.html` for a folder; nothing for what is not there.
async fn static_file(web_client: &Path, segments: &[String]) -> Reply {
    // A segment may hold a slash that came encoded, and `.` and `..` are followed, but never out of
    // the folder: what would climb past it stays in it.
    let mut parts: Vec<&str> = Vec::new();
    for part in segments.iter().flat_map(|segment| segment.split('/')) {
        match part {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            // A name that could not be a file's on this system.
            other if other.contains('\0') => return Reply::empty(StatusCode::NOT_FOUND),
            other => parts.push(other),
        }
    }
    let mut file = web_client.to_path_buf();
    file.extend(&parts);
    let found = on_disk(move || {
        let file = if file.is_dir() { file.join("index.html") } else { file };
        std::fs::metadata(&file).ok().filter(|metadata| metadata.is_file()).map(|metadata| (file, metadata.len()))
    })
    .await;
    let Some((file, length)) = found else { return Reply::empty(StatusCode::NOT_FOUND) };
    let Ok(opened) = tokio::fs::File::open(&file).await else { return Reply::empty(StatusCode::NOT_FOUND) };
    let name = file.file_name().map(|name| name.to_string_lossy().into_owned()).unwrap_or_default();
    // Never more than was promised, should the file grow while it is sent.
    let pieces = stream::unfold((opened.take(length), false), |(mut opened, done)| async move {
        if done {
            return None;
        }
        let mut buffer = vec![0u8; CHUNK];
        match opened.read(&mut buffer).await {
            Ok(0) => None,
            Ok(read) => {
                buffer.truncate(read);
                Some((Ok(Bytes::from(buffer)), (opened, false)))
            }
            Err(error) => Some((Err(error), (opened, true))),
        }
    });
    Reply { status: StatusCode::OK, content_type: Some(content_type_of(&name)), headers: Vec::new(), payload: Payload::Stream(Box::new(Box::pin(pieces)), Some(length)) }
}

// --- Compression, with gzip and deflate ---

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Encoding {
    Gzip,
    Deflate,
}

/// The encoding an `Accept-Encoding` asks for, of the two there are: the one it weighs most, gzip
/// when it weighs them the same, and none that it gives a weight of zero. `*` stands for whichever
/// it does not name.
fn encoding_for(accept: Option<&str>) -> Option<Encoding> {
    let accept = accept?;
    let (mut gzip, mut deflate, mut any) = (None, None, None);
    for item in accept.split(',') {
        let mut pieces = item.split(';');
        let name = trim(pieces.next().unwrap_or("")).to_ascii_lowercase();
        let quality = pieces
            .find_map(|piece| {
                let (key, value) = piece.split_once('=')?;
                trim(key).eq_ignore_ascii_case("q").then(|| trim(value).parse::<f64>().unwrap_or(1.0))
            })
            .unwrap_or(1.0);
        match name.as_str() {
            "gzip" => gzip = gzip.or(Some(quality)),
            "deflate" => deflate = deflate.or(Some(quality)),
            "*" => any = any.or(Some(quality)),
            _ => {}
        }
    }
    let gzip = gzip.or(any).filter(|quality| *quality > 0.0);
    let deflate = deflate.or(any).filter(|quality| *quality > 0.0);
    match (gzip, deflate) {
        (Some(g), Some(d)) if d > g => Some(Encoding::Deflate),
        (Some(_), _) => Some(Encoding::Gzip),
        (None, Some(_)) => Some(Encoding::Deflate),
        (None, None) => None,
    }
}

/// What is left alone: pictures and sound that are compressed already, and the change stream,
/// which must go out as it comes.
fn is_compressible(content_type: Option<&str>) -> bool {
    let kind = content_type.unwrap_or("").split(';').next().unwrap_or("").trim().to_ascii_lowercase();
    !(kind.starts_with("video/") || kind.starts_with("audio/") || kind.starts_with("multipart/") || kind == "image/png" || kind == "image/jpeg" || kind == "text/event-stream")
}

enum Encoder {
    Gzip(GzEncoder<Vec<u8>>),
    Deflate(DeflateEncoder<Vec<u8>>),
}

impl Encoder {
    fn new(encoding: Encoding) -> Encoder {
        match encoding {
            Encoding::Gzip => Encoder::Gzip(GzEncoder::new(Vec::new(), Compression::default())),
            Encoding::Deflate => Encoder::Deflate(DeflateEncoder::new(Vec::new(), Compression::default())),
        }
    }

    /// Takes `chunk` in, and gives what has come out so far.
    fn push(&mut self, chunk: &[u8]) -> io::Result<Vec<u8>> {
        match self {
            Encoder::Gzip(encoder) => {
                encoder.write_all(chunk)?;
                Ok(std::mem::take(encoder.get_mut()))
            }
            Encoder::Deflate(encoder) => {
                encoder.write_all(chunk)?;
                Ok(std::mem::take(encoder.get_mut()))
            }
        }
    }

    fn finish(self) -> io::Result<Vec<u8>> {
        match self {
            Encoder::Gzip(encoder) => encoder.finish(),
            Encoder::Deflate(encoder) => encoder.finish(),
        }
    }
}

/// `pieces` compressed, a piece at a time.
fn compressed(pieces: Box<dyn Stream<Item = io::Result<Bytes>> + Send + Unpin>, encoding: Encoding) -> impl Stream<Item = io::Result<Bytes>> + Send + Unpin {
    Box::pin(stream::unfold((pieces, Some(Encoder::new(encoding))), |(mut pieces, mut encoder)| async move {
        loop {
            let active = encoder.as_mut()?;
            match pieces.next().await {
                Some(Ok(chunk)) => match active.push(&chunk) {
                    Ok(out) if out.is_empty() => continue,
                    Ok(out) => return Some((Ok(Bytes::from(out)), (pieces, encoder))),
                    Err(error) => return Some((Err(error), (pieces, None))),
                },
                Some(Err(error)) => return Some((Err(error), (pieces, None))),
                None => {
                    let rest = encoder.take()?.finish();
                    return Some((rest.map(Bytes::from), (pieces, None)));
                }
            }
        }
    }))
}

/// A body held whole, handed on in pieces, so that compressing a large note does not keep a thread
/// from everything else for as long as it takes.
fn in_pieces(body: Bytes) -> Box<dyn Stream<Item = io::Result<Bytes>> + Send + Unpin> {
    Box::new(stream::unfold(body, |mut rest| async move {
        if rest.is_empty() {
            return None;
        }
        let piece = rest.split_to(rest.len().min(CHUNK));
        tokio::task::yield_now().await;
        Some((Ok(piece), rest))
    })
    .boxed())
}

fn stream_body(pieces: impl Stream<Item = io::Result<Bytes>> + Send + 'static) -> Body {
    StreamBody::new(pieces.map(|piece| piece.map(Frame::data))).boxed_unsync()
}

fn finish(reply: Reply, accept_encoding: Option<&str>) -> Response<Body> {
    // No page of this server's is shown inside another site's: the Devices page would otherwise
    // be a button to revoke a phone with, under whatever that site draws over it. And a type is
    // taken as said, never guessed from the bytes.
    let mut response = Response::builder()
        .status(reply.status)
        .header(header::X_FRAME_OPTIONS, "DENY")
        .header(header::CONTENT_SECURITY_POLICY, "frame-ancestors 'none'")
        .header(header::X_CONTENT_TYPE_OPTIONS, "nosniff");
    if let Some(content_type) = reply.content_type {
        response = response.header(header::CONTENT_TYPE, content_type);
    }
    for (name, value) in &reply.headers {
        if let Ok(value) = HeaderValue::from_bytes(value.as_bytes()) {
            response = response.header(name, value);
        }
    }
    let length = match &reply.payload {
        Payload::Empty => Some(0),
        Payload::Full(body) => Some(body.len() as u64),
        Payload::Stream(_, length) => *length,
    };
    let worth_it = length.is_none_or(|length| length >= MINIMUM_COMPRESSED_SIZE) && is_compressible(reply.content_type);
    let encoding = if worth_it { encoding_for(accept_encoding) } else { None };
    let body = match (reply.payload, encoding) {
        (Payload::Empty, _) => {
            // A length of zero for every answer without a body, but for `204`.
            if reply.status != StatusCode::NO_CONTENT {
                response = response.header(header::CONTENT_LENGTH, 0);
            }
            Full::new(Bytes::new()).map_err(|never| match never {}).boxed_unsync()
        }
        (payload, Some(encoding)) => {
            response = response.header(header::CONTENT_ENCODING, if encoding == Encoding::Gzip { "gzip" } else { "deflate" }).header(header::VARY, "Accept-Encoding");
            let pieces = match payload {
                Payload::Full(body) => in_pieces(body),
                Payload::Stream(pieces, _) => pieces,
                Payload::Empty => unreachable!(),
            };
            stream_body(compressed(pieces, encoding))
        }
        (Payload::Full(body), None) => Full::new(body).map_err(|never| match never {}).boxed_unsync(),
        (Payload::Stream(pieces, length), None) => {
            if let Some(length) = length {
                response = response.header(header::CONTENT_LENGTH, length);
            }
            stream_body(pieces)
        }
    };
    response.body(body).expect("an answer made of these parts is one")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn segments(path: &str) -> Vec<String> {
        path.split('/').filter(|s| !s.is_empty()).map(str::to_string).collect()
    }

    fn found(path: &str, web: bool) -> Option<Route> {
        find_route(&segments(path), path.ends_with('/'), web).map(|(route, _, _)| route)
    }

    #[test]
    fn a_note_being_sent_takes_its_size_of_the_upload_budget() {
        assert_eq!(megabytes_held(Some(0)), 1);
        assert_eq!(megabytes_held(Some(2_000)), 1);
        assert_eq!(megabytes_held(Some(1024 * 1024 + 1)), 2);
        assert_eq!(megabytes_held(Some(MAX_BYTES as u64)), 32);
        assert_eq!(megabytes_held(Some(MAX_BYTES as u64 + 1)), 1, "what is too large is not held at all");
        assert_eq!(megabytes_held(None), 32);
        assert!(megabytes_held(None) as usize * 2 <= UPLOAD_BUDGET_MB, "two of the largest notes can be sent at once");
    }

    #[test]
    fn a_path_finds_its_route() {
        assert_eq!(found("/api/", false), Some(Route::Api));
        assert_eq!(found("/api//", false), Some(Route::Api));
        assert_eq!(found("/api", false), None);
        assert_eq!(found("/api/v1/manifest", false), Some(Route::Manifest));
        assert_eq!(found("/api//v1//manifest", false), Some(Route::Manifest));
        assert_eq!(found("/api/v1/manifest/", false), None);
        assert_eq!(found("/API/v1/manifest", false), None);
        assert_eq!(found("/devices", false), Some(Route::Devices));
        assert_eq!(found("/devices/", false), None);
        assert_eq!(found("/devices/abc/revoke", false), Some(Route::Revoke("abc".into())));
        assert_eq!(found("/devices/a/b/revoke", false), None);
        assert_eq!(found("/", false), Some(Route::Root));
        assert_eq!(found("//", false), Some(Route::Root));
        assert_eq!(found("/", true), None, "the web client has the root when it is served");
    }

    #[test]
    fn a_notes_path_is_what_follows_files_with_its_last_slash() {
        assert_eq!(found("/api/v1/files", false), Some(Route::Files("".into())));
        assert_eq!(found("/api/v1/files/", false), Some(Route::Files("".into())));
        assert_eq!(found("/api/v1/files/inbox.org", false), Some(Route::Files("inbox.org".into())));
        assert_eq!(found("/api/v1/files//inbox.org", false), Some(Route::Files("inbox.org".into())));
        assert_eq!(found("/api/v1/files/inbox.org/", false), Some(Route::Files("inbox.org/".into())));
        assert_eq!(found("/api/v1/files/a/../b.org", false), Some(Route::Files("a/../b.org".into())));
    }

    #[test]
    fn the_encoding_is_chosen_from_what_the_client_accepts() {
        let chosen = |accept: &str| encoding_for(Some(accept));
        assert_eq!(chosen("gzip"), Some(Encoding::Gzip));
        assert_eq!(chosen("deflate"), Some(Encoding::Deflate));
        assert_eq!(chosen("gzip, deflate"), Some(Encoding::Gzip));
        assert_eq!(chosen("br"), None);
        assert_eq!(chosen("identity"), None);
        assert_eq!(chosen("gzip;q=0"), None);
        assert_eq!(chosen("deflate, gzip;q=0.5"), Some(Encoding::Deflate));
        assert_eq!(chosen("*"), Some(Encoding::Gzip));
        assert_eq!(chosen("GZIP"), Some(Encoding::Gzip));
        assert_eq!(chosen("gzip;q=0.5, deflate;q=0.5"), Some(Encoding::Gzip));
        assert_eq!(chosen("deflate;q=0.5, gzip;q=0.5"), Some(Encoding::Gzip));
        assert_eq!(chosen("br, *;q=0.1"), Some(Encoding::Gzip));
        assert_eq!(chosen("identity, gzip"), Some(Encoding::Gzip));
        assert_eq!(chosen("gzip;q=abc"), Some(Encoding::Gzip));
        assert_eq!(chosen("x-gzip"), None);
        assert_eq!(chosen(""), None);
        assert_eq!(chosen("gzip , deflate"), Some(Encoding::Gzip));
        assert_eq!(chosen("*;q=0, gzip"), Some(Encoding::Gzip));
        assert_eq!(chosen("gzip;q=0, *"), Some(Encoding::Deflate));
        assert_eq!(encoding_for(None), None);
    }

    #[test]
    fn what_is_compressed_already_is_left_alone() {
        for kind in ["image/png", "image/jpeg", "video/mp4", "audio/mpeg", "text/event-stream; charset=UTF-8", "multipart/form-data"] {
            assert!(!is_compressible(Some(kind)), "{kind}");
        }
        for kind in ["text/plain; charset=UTF-8", "application/json", "application/wasm", "image/gif", "image/svg+xml; charset=UTF-8", "font/ttf"] {
            assert!(is_compressible(Some(kind)), "{kind}");
        }
        assert!(is_compressible(None));
    }

    #[test]
    fn a_charset_is_read_from_the_content_type() {
        assert_eq!(charset_of(None), Ok(Charset::Utf8));
        assert_eq!(charset_of(Some("")), Ok(Charset::Utf8));
        assert_eq!(charset_of(Some("text/plain")), Ok(Charset::Utf8));
        assert_eq!(charset_of(Some("text/plain; charset=UTF-8")), Ok(Charset::Utf8));
        assert_eq!(charset_of(Some("text/plain; charset=\"utf-8\"")), Ok(Charset::Utf8));
        assert_eq!(charset_of(Some("text/plain; charset=")), Ok(Charset::Utf8));
        assert_eq!(charset_of(Some("application/json; charset=ISO-8859-1")), Ok(Charset::Latin1));
        assert_eq!(charset_of(Some("text/plain; charset=US-ASCII")), Ok(Charset::Ascii));
        assert_eq!(charset_of(Some("text/plain; charset=UTF-16")), Ok(Charset::Utf16 { little_endian: None }));
        assert_eq!(charset_of(Some("text/plain; charset=UTF-16LE")), Ok(Charset::Utf16 { little_endian: Some(true) }));
        assert_eq!(charset_of(Some("text/plain; charset=Shift_JIS")), Ok(Charset::Other("shift_jis".into())));
        assert_eq!(charset_of(Some("text/plain; charset=made-up")), Ok(Charset::Utf8), "a name that is no known charset is read as UTF-8");
        assert_eq!(charset_of(Some("text/plain; charset=windows-1252")), Ok(Charset::Other("windows-1252".into())));
        assert_eq!(charset_of(Some("nonsense")), Err("nonsense".to_string()));
        assert_eq!(charset_of(Some("/plain")), Err("/plain".to_string()));
        assert_eq!(charset_of(Some("a b/plain")), Err("a b/plain".to_string()));
    }

    #[test]
    fn a_body_becomes_utf8_through_the_charset_it_was_said_to_be_in() {
        let text = |body: &[u8], charset: Charset| String::from_utf8(text_bytes(body.to_vec(), &charset)).unwrap();
        assert_eq!(text(b"caf\xc3\xa9", Charset::Utf8), "café");
        assert_eq!(text(b"\xff\xfe \xc3\x28 \xe2\x82", Charset::Utf8), "\u{fffd}\u{fffd} \u{fffd}( \u{fffd}");
        assert_eq!(text(b"caf\xe9 \x80", Charset::Latin1), "café \u{80}");
        assert_eq!(text(b"caf\xe9", Charset::Ascii), "caf\u{fffd}");
        assert_eq!(text(b"\xff\xfec\x00a\x00", Charset::Utf16 { little_endian: None }), "ca");
        assert_eq!(text(b"\xfe\xff\x00c\x00a", Charset::Utf16 { little_endian: None }), "ca");
        assert_eq!(text(b"\x00c\x00a", Charset::Utf16 { little_endian: None }), "ca");
        assert_eq!(text(b"c\x00a\x00", Charset::Utf16 { little_endian: Some(true) }), "ca");
        assert_eq!(text(b"\x00c\x00a\x00", Charset::Utf16 { little_endian: Some(false) }), "ca\u{fffd}");
        // Each of these is what earlier releases kept of the same bytes.
        assert_eq!(text(b"\xd8\x00\x00a\x00b", Charset::Utf16 { little_endian: Some(false) }), "\u{fffd}b", "half a pair takes what follows it with it");
        assert_eq!(text(b"\x00a\xdc\x00\x00b", Charset::Utf16 { little_endian: Some(false) }), "a\u{fffd}b");
        assert_eq!(text(b"\x00a\xd8\x00\xd8\x00\xdc\x00\x00b", Charset::Utf16 { little_endian: Some(false) }), "a\u{fffd}\u{fffd}b");
        assert_eq!(text(b"\x00a\xd8\x00", Charset::Utf16 { little_endian: Some(false) }), "a\u{fffd}");
        assert_eq!(text(b"\xd8\x3d\xde\x00", Charset::Utf16 { little_endian: Some(false) }), "😀");
    }

    #[test]
    fn a_file_of_the_web_client_has_the_type_of_its_extension() {
        assert_eq!(content_type_of("index.html"), "text/html; charset=UTF-8");
        assert_eq!(content_type_of("UPPER.HTML"), "text/html; charset=UTF-8");
        assert_eq!(content_type_of("composeApp.js"), "text/javascript; charset=UTF-8");
        assert_eq!(content_type_of("8ffcd5ccc5d1fd03602f.wasm"), "application/wasm");
        assert_eq!(content_type_of("composeApp.js.map"), "application/x-navimap");
        assert_eq!(content_type_of("strings.commonMain.cvr"), "application/octet-stream");
        assert_eq!(content_type_of("noextension"), "application/octet-stream");
    }
}
