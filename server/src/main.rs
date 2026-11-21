use korgnote_server::config::ServerConfig;
use korgnote_server::devices::DeviceRegistry;
use korgnote_server::http::App;
use korgnote_server::identity::ServerIdentity;
use korgnote_server::journal::ChangeJournal;
use korgnote_server::security::{PairingDetails, ServerAccess, container_gateway};
use korgnote_server::server::{listen_plain, listen_secure, raise_open_file_limit};
use korgnote_server::watcher::NotesWatcher;
use korgnote_server::{error, info, warn};
use std::sync::Arc;

fn port(value: i32, variable: &str) -> Result<u16, String> {
    u16::try_from(value).map_err(|_| format!("{variable} is {value}, which is not a port."))
}

async fn run() -> Result<(), String> {
    korgnote_server::logging::set_debug(
        std::env::var("KORGNOTE_LOG").is_ok_and(|level| level.eq_ignore_ascii_case("debug"))
    );
    let config = ServerConfig::from_process();
    raise_open_file_limit();
    let (http_port, https_port) = (
        port(config.http_port, "KORGNOTE_PORT")?,
        port(config.https_port, "KORGNOTE_HTTPS_PORT")?
    );

    let identity = ServerIdentity::load_or_create(&config.state_dir)
        .map_err(|error| format!("Could not read or make the server's identity in {}: {error}", config.state_dir.display()))?;
    let devices = DeviceRegistry::open(Some(config.state_dir.join("devices.json")))
        .map_err(|error| format!("Could not read the paired devices: {error}"))?;
    let access = ServerAccess {
        devices: Arc::new(devices),
        pairing: Some(PairingDetails::new(
            &identity.server_id,
            &config.server_name,
            &identity.fingerprint,
            config.advertised_port,
            config.advertised_addresses.clone())),
        this_computer: container_gateway(),
    };
    let tls = identity.tls_config()
        .map_err(|error| format!("The server's certificate cannot be served with: {error}"))?;

    info!("{}", config.describe());
    if let Some(advice) = access.pairing.as_ref()
        .and_then(|pairing| pairing.missing_address_advice()) {
        warn!("No address to pair phones with. {advice}");
    }
    let store = Arc::new(config.open_store()?);
    let web_client = config.find_web_client();
    let journal = Arc::new(ChangeJournal::new());
    // A library that is read-only has no folder to watch.
    let watcher = (!store.read_only)
        .then(|| Arc::new(NotesWatcher::new(&store.root, store.clone(), journal.clone())));
    if let Some(watcher) = &watcher {
        watcher.start();
    }
    let app = Arc::new(App::with(store, Arc::new(access), journal, watcher.clone(), web_client));

    let plain = listen_plain(app.clone(), &config.http_host, http_port).await
        .map_err(|error| format!("Could not listen on {}:{http_port}: {error}", config.http_host))?;
    let secure = listen_secure(app, &config.https_host, https_port, tls).await
        .map_err(|error| format!("Could not listen on {}:{https_port}: {error}", config.https_host))?;
    info!("Responding at http://{} and https://{}", plain.address, secure.address);

    let mut terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
        .map_err(|error| error.to_string())?;
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {}
        _ = terminate.recv() => {}
    }
    info!("Stopping");
    plain.stop();
    secure.stop();
    if let Some(watcher) = watcher {
        watcher.stop();
    }
    Ok(())
}

fn main() {
    // For anyone with a link to draw: the code a link makes.
    let arguments: Vec<String> = std::env::args().collect();
    if let [_, flag, text] = arguments.as_slice() {
        match flag.as_str() {
            "--qr-svg" => return print!("{}", korgnote_server::pairing::qr_svg(text, 6).unwrap_or_default()),
            "--qr-terminal" => return print!("{}", korgnote_server::pairing::qr_terminal(text).unwrap_or_default()),
            _ => {}
        }
    }
    // A household's notes do not need a thread for every core, and each one costs memory: four to
    // answer with, and a few more for the disk, which is where the time goes.
    let workers = std::thread::available_parallelism()
        .map(|cores|
            cores.get()).unwrap_or(1).min(4);
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(workers)
        .max_blocking_threads(16)
        .enable_all()
        .build().expect("a runtime to serve with");
    let outcome = runtime.block_on(run());
    // Open change streams would keep the runtime from ending; the process ending closes them.
    runtime.shutdown_background();
    if let Err(message) = outcome {
        error!("{message}");
        std::process::exit(1);
    }
}
