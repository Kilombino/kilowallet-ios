//! Arti, the Tor Project's Tor in Rust, inside Kilowallet iOS.
//!
//! Kilowallet Android runs Arti's own program (`arti proxy -c arti.toml`, see the Android app's
//! `chain/Tor.kt`). iOS lets no app start a program, so here the same Arti (tag and features in
//! `tor-engine/ENGINE`) runs as a library inside the app: the same steps `arti proxy` takes in
//! `crates/arti/src/subcommands/proxy.rs`, with the settings Android writes to arti.toml:
//!
//! - one SOCKS port on 127.0.0.1 and no DNS port;
//! - `.onion` addresses allowed;
//! - cache and state under the folder the app gives (`cache/`, `state/`);
//! - `storage.permissions.dangerously_trust_everyone`: the app's sandbox, not Unix permissions up
//!   the path, keeps others out;
//! - log at `info`, with safe logging on (no addresses in it), appended to the file the app gives.
//!
//! As Android's program does, it says "Sufficiently bootstrapped; proxy now functional." once
//! the SOCKS port can carry traffic. The app reads the log for that line, for the last line
//! worth showing, and for "corrupted data in persistent state" (it then wipes `state/` before the
//! next start, as Android does).
//!
//! C functions (kilombino_tor.h): `kilombino_tor_start`, `kilombino_tor_stop`,
//! `kilombino_tor_state`, `kilombino_tor_free`.

use std::ffi::{CStr, CString, c_char};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicI32, Ordering};
use std::sync::{Mutex, Once, mpsc};
use std::thread::JoinHandle;

use anyhow::Context;
use arti::proxy::{ListenProtocols, run_proxy_with_listeners};
use arti_client::config::TorClientConfigBuilder;
use arti_client::{BootstrapBehavior, HasKind, TorClient};
use futures::{FutureExt, StreamExt};
use tor_rtcompat::{NetStreamProvider, PreferredRuntime, TcpListenOptions};
use tracing::{error, info};

/// 0: not running. 1: running, not yet able to carry traffic. 2: bootstrapped (the SOCKS port works).
static STATE: AtomicI32 = AtomicI32::new(0);

struct Running {
    /// Taken when the app asks it to stop.
    stop: Option<futures::channel::oneshot::Sender<()>>,
    thread: JoinHandle<()>,
}

static RUN: Mutex<Option<Running>> = Mutex::new(None);

/// Where log lines go: the file of the current run (the app's tor.log).
static LOG: Mutex<Option<std::fs::File>> = Mutex::new(None);
static LOGGING: Once = Once::new();

struct LogWriter;

impl Write for LogWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if let Ok(mut f) = LOG.lock() {
            if let Some(f) = f.as_mut() {
                let _ = f.write_all(buf);
            }
        }
        Ok(buf.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        if let Ok(mut f) = LOG.lock() {
            if let Some(f) = f.as_mut() {
                let _ = f.flush();
            }
        }
        Ok(())
    }
}

/// One subscriber for the whole process (tracing allows only one): `info`, no colours, into [LOG].
fn install_logging() {
    LOGGING.call_once(|| {
        let _ = tracing_subscriber::fmt()
            .with_writer(|| LogWriter)
            .with_ansi(false)
            .with_max_level(tracing::Level::INFO)
            .try_init();
    });
}

fn open_log(path: &str) -> std::io::Result<()> {
    let p = PathBuf::from(path);
    if let Some(parent) = p.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let f = std::fs::OpenOptions::new().create(true).append(true).open(p)?;
    *LOG.lock().unwrap_or_else(|e| e.into_inner()) = Some(f);
    Ok(())
}

fn c_string(s: String) -> *mut c_char {
    CString::new(s.replace('\0', " ")).map(CString::into_raw).unwrap_or(std::ptr::null_mut())
}

/// Starts Arti: a SOCKS proxy on 127.0.0.1:`port`, its cache and state under `dir`, its log
/// appended to `log_path`. Returns NULL once the port is listening (Arti then connects to the
/// Tor network on its own thread; [kilombino_tor_state] says when it can carry traffic), or why
/// it could not start (free it with [kilombino_tor_free]). A run being stopped is waited for.
///
/// # Safety
/// `dir` and `log_path` are NUL-terminated UTF-8 strings.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn kilombino_tor_start(dir: *const c_char, log_path: *const c_char, port: u16) -> *mut c_char {
    if dir.is_null() || log_path.is_null() || port == 0 {
        return c_string("bad arguments".into());
    }
    let dir = match unsafe { CStr::from_ptr(dir) }.to_str() {
        Ok(s) => PathBuf::from(s),
        Err(_) => return c_string("the folder is not UTF-8".into()),
    };
    let log_path = match unsafe { CStr::from_ptr(log_path) }.to_str() {
        Ok(s) => s.to_owned(),
        Err(_) => return c_string("the log path is not UTF-8".into()),
    };
    let mut run = RUN.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(r) = run.take() {
        if r.thread.is_finished() || r.stop.is_none() {
            // Ended, or on its way out: let it finish (it gives the port and the state lock back).
            let _ = r.thread.join();
        } else {
            *run = Some(r);
            return c_string("Tor is already running".into());
        }
    }
    if let Err(e) = open_log(&log_path) {
        return c_string(format!("cannot write the log: {e}"));
    }
    install_logging();
    STATE.store(1, Ordering::SeqCst);
    let (stop_tx, stop_rx) = futures::channel::oneshot::channel::<()>();
    let (bound_tx, bound_rx) = mpsc::channel::<Result<(), String>>();
    let thread = std::thread::Builder::new().name("arti".into()).spawn(move || {
        let mut bound = Some(bound_tx);
        // A tokio runtime of our own, so stopping can end every task Arti spawned (they hold the
        // client and its lock on state/); arti's runtime wraps it without owning it. Arti's
        // rate-limited messages are left unthrottled (no global runtime is installed for them:
        // it would outlive this one).
        let result = match tokio::runtime::Builder::new_multi_thread().enable_all().thread_name("arti-worker").build() {
            Ok(tokio_rt) => {
                let r = tokio_rt.block_on(async {
                    let rt = PreferredRuntime::current().context("cannot wrap the async runtime")?;
                    run_proxy(rt, dir, port, stop_rx, &mut bound).await
                });
                tokio_rt.shutdown_timeout(std::time::Duration::from_secs(5));
                r
            }
            Err(e) => Err(anyhow::Error::new(e).context("cannot create the async runtime")),
        };
        if let Err(e) = &result {
            let why = describe(e);
            error!("Arti stopped: {why}");
            if let Some(b) = bound.take() {
                let _ = b.send(Err(why));
            }
        }
        STATE.store(0, Ordering::SeqCst);
    });
    let thread = match thread {
        Ok(t) => t,
        Err(e) => {
            STATE.store(0, Ordering::SeqCst);
            return c_string(format!("cannot start a thread: {e}"));
        }
    };
    let answer = bound_rx.recv().unwrap_or_else(|_| Err("Arti stopped before listening".into()));
    *run = Some(Running { stop: Some(stop_tx), thread });
    match answer {
        Ok(()) => std::ptr::null_mut(),
        Err(why) => c_string(why),
    }
}

/// An error and its causes on one line, with Arti's kind of error when it has one (Android's
/// program prints "corrupted data in persistent state" that way, and the app looks for it).
fn describe(e: &anyhow::Error) -> String {
    let mut s = format!("{e:#}");
    for cause in e.chain() {
        if let Some(a) = cause.downcast_ref::<arti_client::Error>() {
            let kind = a.kind().to_string();
            if !s.contains(&kind) {
                s.push_str(&format!(" ({kind})"));
            }
            break;
        }
    }
    s
}

/// What `arti proxy` does (subcommands/proxy.rs `run_proxy`), for one SOCKS port and nothing else.
async fn run_proxy(
    rt: PreferredRuntime,
    dir: PathBuf,
    port: u16,
    stop: futures::channel::oneshot::Receiver<()>,
    bound: &mut Option<mpsc::Sender<Result<(), String>>>,
) -> anyhow::Result<()> {
    info!("Starting Arti {} in proxy mode on 127.0.0.1:{port} ...", env!("CARGO_PKG_VERSION"));
    let mut b = TorClientConfigBuilder::from_directories(dir.join("state"), dir.join("cache"));
    b.storage().permissions().dangerously_trust_everyone();
    b.address_filter().allow_onion_addrs(true);
    let config = b.build().context("Arti's settings")?;
    // OnDemand: connections that arrive while it is still bootstrapping wait for it.
    let client = TorClient::with_runtime(rt.clone())
        .config(config)
        .bootstrap_behavior(BootstrapBehavior::OnDemand)
        .create_unbootstrapped_async()
        .await?;
    // The port is bound here, as arti's bind_proxy does, so a port in use is told to the app at once.
    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], port));
    let listener = rt
        .listen(&addr, &TcpListenOptions::default())
        .await
        .with_context(|| format!("Can't listen on {addr}"))?;
    info!("Listening on {addr:?}");
    let proxy = run_proxy_with_listeners(client.isolated_client(), vec![listener], ListenProtocols::SocksOnly, None);
    if let Some(b) = bound.take() {
        let _ = b.send(Ok(()));
    }
    // How far it has got, for the app to show while it connects (each change once).
    let mut events = client.bootstrap_events();
    let progress = async move {
        let mut last = String::new();
        while let Some(s) = events.next().await {
            let line = s.to_string();
            if line != last {
                info!("Bootstrap status: {line}");
                last = line;
            }
        }
        futures::future::pending::<()>().await
    };
    let boot = async {
        client.bootstrap().await?;
        info!("Sufficiently bootstrapped; proxy now functional.");
        STATE.store(2, Ordering::SeqCst);
        futures::future::pending::<anyhow::Result<()>>().await
    };
    futures::select!(
        _ = stop.fuse() => { info!("Stopping: asked by the app."); Ok(()) },
        r = proxy.fuse() => r.context("socks proxy died unexpectedly"),
        r = boot.fuse() => r.context("bootstrap"),
        _ = progress.fuse() => Ok(()),
    )
}

/// Asks Arti to stop; returns at once (the next start waits for it to be gone).
#[unsafe(no_mangle)]
pub extern "C" fn kilombino_tor_stop() {
    let mut run = RUN.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(r) = run.as_mut() {
        if let Some(s) = r.stop.take() {
            let _ = s.send(());
        }
    }
}

/// 0: not running. 1: running, still connecting to the Tor network. 2: the SOCKS port carries traffic.
#[unsafe(no_mangle)]
pub extern "C" fn kilombino_tor_state() -> i32 {
    STATE.load(Ordering::SeqCst)
}

/// Frees a string returned by [kilombino_tor_start].
///
/// # Safety
/// `s` is NULL or a string [kilombino_tor_start] returned, freed once.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn kilombino_tor_free(s: *mut c_char) {
    if !s.is_null() {
        drop(unsafe { CString::from_raw(s) });
    }
}
