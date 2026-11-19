//! The two listening ports, and each connection from accepted to served.

use crate::http::{App, Peer, handle};
use hyper::service::service_fn;
use hyper_util::rt::{TokioExecutor, TokioIo};
use hyper_util::server::conn::auto;
use std::io;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tokio::net::{TcpListener, TcpStream};
use tokio_rustls::TlsAcceptor;

/// How long a connection to the secure port may take to become TLS before it is let go: a port
/// scanner, or a browser pointed at it in plain HTTP, says nothing more.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(30);

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
        let app = app.clone();
        async move { Ok::<_, std::convert::Infallible>(handle(app, peer, request).await) }
    });
    // HTTP/1.1 or HTTP/2, whichever the first bytes are. A connection that ends badly is its
    // client's business: a phone gone out of range is not worth a line in the log.
    let _ = auto::Builder::new(TokioExecutor::new()).serve_connection(TokioIo::new(stream), service).await;
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
            tokio::spawn(serve(app.clone(), Peer { remote, local_port: address.port(), secure: false }, stream));
        }
    });
    Ok(Listening { address, task })
}

/// Listens for TLS on `host:port`, for paired devices.
pub async fn listen_secure(app: Arc<App>, host: &str, port: u16, tls: rustls::ServerConfig) -> io::Result<Listening> {
    let listener = TcpListener::bind((host, port)).await?;
    let address = listener.local_addr()?;
    let acceptor = TlsAcceptor::from(Arc::new(tls));
    let task = tokio::spawn(async move {
        loop {
            let Some((stream, remote)) = accept(&listener).await else { continue };
            let (app, acceptor) = (app.clone(), acceptor.clone());
            tokio::spawn(async move {
                if let Ok(Ok(stream)) = tokio::time::timeout(HANDSHAKE_TIMEOUT, acceptor.accept(stream)).await {
                    serve(app, Peer { remote, local_port: address.port(), secure: true }, stream).await;
                }
            });
        }
    });
    Ok(Listening { address, task })
}
