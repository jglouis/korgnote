//! The two listening ports, and each connection from accepted to served.

use crate::http::{App, Peer, handle};
use hyper::service::service_fn;
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::server::conn::auto;
use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::net::{TcpListener, TcpStream};
use tokio_rustls::TlsAcceptor;

/// How long a connection to the secure port may take to become TLS before it is let go: a port
/// scanner, or a browser pointed at it in plain HTTP, says nothing more.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(30);
/// Connections to the secure port open at once from one address (one /64 for IPv6): a household's
/// phones behind one router use a handful each.
const MOST_FROM_ONE_ADDRESS: usize = 64;
/// And from everywhere, at most; fewer when the process may open fewer files.
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
    let files = nix::sys::resource::getrlimit(nix::sys::resource::Resource::RLIMIT_NOFILE).map(|(soft, _)| soft).unwrap_or(1024);
    let room = (files.min(1 << 20) as usize * 3 / 4).saturating_sub(32);
    room.clamp(16, MOST_CONNECTIONS)
}

/// The address connections are counted against: an IPv6 one by its /64, which is what one
/// household or one machine is given.
fn counted_as(address: IpAddr) -> IpAddr {
    match address.to_canonical() {
        IpAddr::V6(v6) => IpAddr::V6(std::net::Ipv6Addr::from(u128::from(v6) & !((1u128 << 64) - 1))),
        v4 => v4,
    }
}

/// The connections open to the secure port, overall and by address.
struct Admission {
    most: usize,
    open: Mutex<(usize, HashMap<IpAddr, usize>)>,
    last_refusal_logged: AtomicU64,
}

/// One connection's place, given back when it closes.
struct Ticket {
    admission: Arc<Admission>,
    address: IpAddr,
}

impl Admission {
    fn new(most: usize) -> Arc<Self> {
        Arc::new(Admission { most, open: Mutex::new((0, HashMap::new())), last_refusal_logged: AtomicU64::new(0) })
    }

    fn admit(self: &Arc<Self>, remote: IpAddr) -> Option<Ticket> {
        let address = counted_as(remote);
        let mut open = self.open.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        let from_there = open.1.get(&address).copied().unwrap_or(0);
        if open.0 >= self.most || from_there >= MOST_FROM_ONE_ADDRESS {
            drop(open);
            self.say_refused(remote);
            return None;
        }
        open.0 += 1;
        open.1.insert(address, from_there + 1);
        Some(Ticket { admission: self.clone(), address })
    }

    /// Once a minute at most: a flood of refusals is not worth a flood of lines.
    fn say_refused(&self, remote: IpAddr) {
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|elapsed| elapsed.as_secs()).unwrap_or(0);
        let last = self.last_refusal_logged.load(Ordering::Relaxed);
        if now >= last + 60 && self.last_refusal_logged.compare_exchange(last, now, Ordering::Relaxed, Ordering::Relaxed).is_ok() {
            crate::warn!("Turning connections away: too many are open, from {remote} or altogether ({} at most, {MOST_FROM_ONE_ADDRESS} from one address).", self.most);
        }
    }
}

impl Drop for Ticket {
    fn drop(&mut self) {
        let mut open = self.admission.open.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        open.0 = open.0.saturating_sub(1);
        if let Some(count) = open.1.get_mut(&self.address) {
            *count -= 1;
            if *count == 0 {
                open.1.remove(&self.address);
            }
        }
    }
}

/// Ends when a connection has gone `lifetime` without showing a paired device's token, and never
/// once it has.
async fn strangers_time_is_up(paired: Arc<AtomicBool>, lifetime: Duration) {
    tokio::time::sleep(lifetime).await;
    if paired.load(Ordering::Relaxed) {
        std::future::pending::<()>().await;
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
            let probes = socket2::TcpKeepalive::new().with_time(Duration::from_secs(60)).with_interval(Duration::from_secs(20));
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
            let Some((stream, remote)) = accept(&listener).await else { continue };
            tokio::spawn(serve(app.clone(), Peer::new(remote, address.port(), false), stream));
        }
    });
    Ok(Listening { address, task })
}

/// Listens for TLS on `host:port`, for paired devices.
pub async fn listen_secure(app: Arc<App>, host: &str, port: u16, tls: rustls::ServerConfig) -> io::Result<Listening> {
    let listener = TcpListener::bind((host, port)).await?;
    let address = listener.local_addr()?;
    let acceptor = TlsAcceptor::from(Arc::new(tls));
    let admission = Admission::new(most_connections());
    let task = tokio::spawn(async move {
        loop {
            let Some((stream, remote)) = accept(&listener).await else { continue };
            // Closed at once, which a client takes as the server being busy, and tries again.
            let Some(ticket) = admission.admit(remote.ip()) else { continue };
            let (app, acceptor) = (app.clone(), acceptor.clone());
            tokio::spawn(async move {
                let _ticket = ticket;
                if let Ok(Ok(stream)) = tokio::time::timeout(HANDSHAKE_TIMEOUT, acceptor.accept(stream)).await {
                    let peer = Peer::new(remote, address.port(), true);
                    let (paired, lifetime) = (peer.paired.clone(), app.unpaired_lifetime);
                    tokio::select! {
                        _ = serve(app, peer, stream) => {}
                        _ = strangers_time_is_up(paired, lifetime) => {}
                    }
                }
            });
        }
    });
    Ok(Listening { address, task })
}
