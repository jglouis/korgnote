//! The two listening ports, and each connection from accepted to served.

use crate::devices::DeviceRegistry;
use crate::http::{App, Peer, handle};
use hyper::service::service_fn;
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::server::conn::auto;
use std::collections::VecDeque;
use std::io;
use std::net::SocketAddr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Notify;
use tokio_rustls::TlsAcceptor;

/// How long a connection to the secure port may take to become TLS before it is let go: a port
/// scanner, or a browser pointed at it in plain HTTP, says nothing more.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(30);
/// Connections to the secure port open at once, at most; fewer when the process may open fewer files.
const MOST_CONNECTIONS: usize = 512;
/// The most HTTP/1.1 reads ahead on one connection.
const READ_BUFFER: usize = 64 * 1024;

/// Raises the number of files the process may open to what the system allows it, up to 4096: a
/// service started by launchd on macOS may open 256, which a few hundred connections use up.
pub fn raise_open_file_limit() {
    use nix::sys::resource::{Resource, getrlimit, setrlimit};
    if let Ok((soft, hard)) = getrlimit(Resource::RLIMIT_NOFILE) {
        let wanted = hard.min(4096);
        if soft < wanted {
            let _ = setrlimit(Resource::RLIMIT_NOFILE, wanted, hard);
        }
    }
}

/// How many connections the secure port takes at once: `MOST_CONNECTIONS`, or three quarters of
/// the files the process may open, less some for the notes, the plain port and the watcher.
fn most_connections() -> usize {
    let files = nix::sys::resource::getrlimit(nix::sys::resource::Resource::RLIMIT_NOFILE)
        .map(|(soft, _)| soft)
        .unwrap_or(1024);
    let room = (files.min(1 << 20) as usize * 3 / 4).saturating_sub(32);
    room.clamp(16, MOST_CONNECTIONS)
}

/// Tracks admission limits and shared admission state.
///
/// `Admission` coordinates access by enforcing an overall maximum (`most`) and
/// a separate maximum for stranger entries (`most_strangers`). Mutable state is
/// stored in `open` behind a mutex, while `last_logged` records the last log time
/// for rate-limited or diagnostic logging.
struct Admission {
    most: usize,
    most_strangers: usize,
    open_connections: Mutex<OpenConnections>,
    last_logged: AtomicU64,
}

/// Tracks open connections and their token-pairing state.
///
/// Connections that have not yet presented a token are kept in `strangers` in
/// oldest-first order. Connections that have presented a token are counted in
/// `paired`.
///
/// The `next` value is the next connection identifier or sequence number to
/// allocate.
struct OpenConnections {
    next: u64,
    /// Connections that had shown no token when last looked at, the oldest first.
    strangers: VecDeque<Stranger>,
    /// The others: those that have shown one.
    paired: usize,
}

/// Represents a peer connection that is not yet fully accepted, identified, or
/// integrated into the main peer set.
///
/// `Stranger` keeps the peer handle together with shared shutdown/cleanup state
/// used to coordinate its lifetime across asynchronous tasks.
///
/// # Fields
///
/// - `id`: Unique identifier assigned to this stranger.
/// - `peer`: The underlying peer connection or peer metadata.
/// - `let_go`: Notification used to signal that the peer should be released,
///   disconnected, or allowed to terminate.
/// - `gone`: Shared atomic flag indicating whether the peer has already left or
///   has been marked as terminated.
struct Stranger {
    id: u64,
    peer: Peer,
    let_go: Arc<Notify>,
    gone: Arc<AtomicBool>,
}

/// Represents a ticket associated with a shared admission state.
///
/// A `Ticket` identifies a granted or pending admission using a unique `id` and
/// holds shared synchronization state used to coordinate release or cancellation.
///
/// The ticket keeps:
/// - a shared reference to the associated [`Admission`],
/// - a unique ticket identifier,
/// - a notification handle used to signal that the ticket may be released, and
/// - an atomic flag indicating whether the ticket has already been marked as gone.
///
/// Shared ownership is provided through [`Arc`], allowing the ticket state to be
/// referenced safely across tasks or threads.
struct Ticket {
    admission: Arc<Admission>,
    id: u64,
    let_go: Arc<Notify>,
    gone: Arc<AtomicBool>,
}

impl OpenConnections {
    /// Removes strangers whose peers have become paired and updates the paired count.
    ///
    /// Retains only unpaired strangers in `self.strangers`, then increments
    /// `self.paired` by the number of removed paired strangers.
    fn settle(&mut self) {
        let before = self.strangers.len();
        self.strangers.retain(|stranger| !stranger.peer.is_paired());
        self.paired += before - self.strangers.len();
    }
}

impl Admission {
    fn new(most: usize, most_strangers: usize) -> Arc<Self> {
        Arc::new(Admission {
            most,
            most_strangers,
            open_connections: Mutex::new(OpenConnections {
                next: 0,
                strangers: VecDeque::new(),
                paired: 0,
            }),
            last_logged: AtomicU64::new(0),
        })
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, OpenConnections> {
        self.open_connections
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    /// Attempts to admit `peer` as a new unpaired connection.
    ///
    /// This records the peer as a `Stranger` and returns a [`Ticket`] that represents
    /// the admission. The ticket contains the admission state, the assigned admission
    /// id, and notification state used to signal when the connection has been forced
    /// out.
    ///
    /// Before admitting the peer, this method:
    ///
    /// - locks the admission state,
    /// - settles any stale/finished entries,
    /// - enforces both the maximum number of strangers and the total connection limit,
    /// - evicts the oldest stranger that has not presented or started waiting on a
    ///   pairing token when space is needed.
    ///
    /// If an eviction occurs, the evicted stranger is marked as gone and its waiter is
    /// notified.
    ///
    /// Returns `None` when the admission limits are full and every currently open
    /// stranger has already shown a paired device's token or is waiting on pairing,
    /// meaning no safe eviction candidate is available. Otherwise, returns `Some` with
    /// a newly created [`Ticket`] for `peer`.
    fn admit(self: &Arc<Self>, peer: &Peer) -> Option<Ticket> {
        let mut open_connections = self.lock();
        open_connections.settle();
        while open_connections.strangers.len() >= self.most_strangers
            || open_connections.strangers.len() + open_connections.paired >= self.most
        { // Need to evict a stranger with a pairing counter == 0.
            let Some(at) = open_connections
                .strangers
                .iter()
                .position(|stranger| stranger.peer.pairing.load(Ordering::SeqCst) == 0)
            else { // No stranger to evict, drop the connection.
                drop(open_connections);
                self.say("Turning connections away: every one open has shown a paired device's token or waits on its pairing.");
                return None;
            };
            let oldest = open_connections.strangers.remove(at).expect("it was found there");
            oldest.gone.store(true, Ordering::SeqCst);
            oldest.let_go.notify_one();
            self.say("Letting the oldest connections that showed no token go, to make room for new ones: something is opening many.");
        }
        let id = open_connections.next;
        open_connections.next += 1;
        let (let_go, gone) = (Arc::new(Notify::new()), Arc::new(AtomicBool::new(false)));
        open_connections.strangers.push_back(Stranger {
            id,
            peer: peer.clone(),
            let_go: let_go.clone(),
            gone: gone.clone(),
        });
        Some(Ticket {
            admission: self.clone(),
            id,
            let_go,
            gone,
        })
    }

    /// Logs `what` as a warning at most once every 60 seconds.
    ///
    /// Uses the current UNIX timestamp and an atomic `last_logged` value to
    /// rate-limit repeated messages. If the stored timestamp is at least 60 seconds
    /// behind the current time, it is updated and the warning is emitted.
    ///
    /// If the system time is earlier than the UNIX epoch, the timestamp defaults to
    /// `0`, which prevents panicking.
    fn say(&self, what: &str) {
        let now = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|elapsed| elapsed.as_secs())
            .unwrap_or(0);
        let last = self.last_logged.load(Ordering::Relaxed);
        if now >= last + 60
            && self
                .last_logged
                .compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed)
                .is_ok()
        {
            crate::warn!("{what}");
        }
    }
}

impl Drop for Ticket {
    fn drop(&mut self) {
        let mut open = self.admission.lock();
        if let Some(at) = open
            .strangers
            .iter()
            .position(|stranger| stranger.id == self.id)
        {
            open.strangers.remove(at);
        } else if !self.gone.load(Ordering::SeqCst) {
            open.paired = open.paired.saturating_sub(1);
        }
    }
}

/// Ends when a connection has gone `lifetime` without showing a paired device's token, unless a
/// pairing on it is waiting to be allowed; never once it has shown one.
async fn strangers_time_is_up(peer: Peer, lifetime: Duration) {
    loop {
        tokio::time::sleep(lifetime).await;
        if peer.is_paired() {
            std::future::pending::<()>().await;
        }
        if peer.pairing.load(Ordering::SeqCst) == 0 {
            return;
        }
    }
}

/// Ends when the device whose token the connection showed is revoked: its change stream and
/// anything else it holds open go with it.
async fn revoked(peer: Peer, devices: Arc<DeviceRegistry>) {
    let mut removals = devices.removals();
    loop {
        if removals.changed().await.is_err() {
            std::future::pending::<()>().await;
        }
        if peer.paired.get().is_some_and(|token| !devices.knows(token)) {
            return;
        }
    }
}

/// A port being listened on, with what it serves.
pub struct Listening {
    pub address: SocketAddr,
    task: tokio::task::JoinHandle<()>,
}

impl Listening {
    /// Stops taking connections. Those already open go on until they end.
    pub fn stop(&self) {
        self.task.abort();
    }
}

async fn serve<S>(app: Arc<App>, peer: Peer, stream: S)
where
    S: tokio::io::AsyncRead + tokio::io::AsyncWrite + Unpin + Send + 'static,
{
    let service = service_fn(move |request| {
        let (app, peer) = (app.clone(), peer.clone());
        async move { Ok::<_, std::convert::Infallible>(handle(app, peer, request).await) }
    });
    // HTTP/1.1 or HTTP/2, whichever the first bytes are. A connection that ends badly is its
    // client's business: a phone gone out of range is not worth a line in the log.
    let mut builder = auto::Builder::new(TokioExecutor::new());
    // What HTTP/1.1 reads ahead of the handler grows, for a client that sends fast, to some 400 KB
    // a connection, and stays that size while it is open. 64 KB reads a note as fast and holds a
    // request line and headers of any size the apps send.
    builder.http1().max_buf_size(READ_BUFFER);
    let _ = builder.serve_connection(TokioIo::new(stream), service).await;
}

async fn accept(listener: &TcpListener) -> Option<(TcpStream, SocketAddr)> {
    match listener.accept().await {
        Ok((stream, remote)) => {
            let _ = stream.set_nodelay(true);
            // A phone that leaves the network says nothing, and its connection would stay open for
            // good, a task and a descriptor each. Asked every so often whether it is still there, one
            // that is not is closed by the system within a few minutes.
            let probes = socket2::TcpKeepalive::new()
                .with_time(Duration::from_secs(60))
                .with_interval(Duration::from_secs(20));
            let _ = socket2::SockRef::from(&stream).set_tcp_keepalive(&probes);
            Some((stream, remote))
        }
        Err(error) => {
            // Out of file descriptors, most likely: wait for some to come back rather than spin.
            crate::warn!("Could not take a connection: {error}");
            tokio::time::sleep(Duration::from_millis(100)).await;
            None
        }
    }
}

/// Listens for plain HTTP on `host:port`, for this computer.
pub async fn listen_plain(app: Arc<App>, host: &str, port: u16) -> io::Result<Listening> {
    let listener = TcpListener::bind((host, port)).await?;
    let address = listener.local_addr()?;
    let task = tokio::spawn(async move {
        loop {
            let Some((stream, remote)) = accept(&listener).await else { continue; };
            tokio::spawn(serve(
                app.clone(),
                Peer::new(remote, address.port(), false),
                stream,
            ));
        }
    });
    Ok(Listening { address, task })
}

/// Listens for TLS on `host:port`, for paired devices.
pub async fn listen_secure(app: Arc<App>, host: &str, port: u16, tls: rustls::ServerConfig) -> io::Result<Listening> {
    let listener = TcpListener::bind((host, port)).await?;
    let address = listener.local_addr()?;
    let acceptor = TlsAcceptor::from(Arc::new(tls));
    let admission = Admission::new(most_connections(), app.most_strangers);
    let task = tokio::spawn(async move {
        loop {
            let Some((stream, remote)) = accept(&listener).await else { continue };
            let peer = Peer::new(remote, address.port(), true);
            // Closed at once, which a client takes as the server being busy, and tries again.
            let Some(ticket) = admission.admit(&peer) else { continue };
            let (app, acceptor) = (app.clone(), acceptor.clone());
            tokio::spawn(async move {
                let let_go = ticket.let_go.clone();
                let _ticket = ticket;
                // Subscribed from the start, so that no revocation falls between a request and this.
                let revoked = revoked(peer.clone(), app.access.devices.clone());
                let lifetime = strangers_time_is_up(peer.clone(), app.unpaired_lifetime);
                tokio::select! {
                    _ = async {
                        if let Ok(Ok(stream)) = tokio::time::timeout(HANDSHAKE_TIMEOUT, acceptor.accept(stream)).await {
                            serve(app, peer, stream).await;
                        }
                    } => {}
                    _ = lifetime => {}
                    _ = revoked => {}
                    _ = let_go.notified() => {}
                }
            });
        }
    });
    Ok(Listening { address, task })
}
