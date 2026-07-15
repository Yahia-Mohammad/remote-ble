mod ble;
mod protocol;
mod registry;
mod translate;
mod transport;

use clap::Parser;
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;
use tracing_subscriber::EnvFilter;

use ble::backend::BleBackend;
use ble::btleplug_impl::BtleplugBackend;
use protocol::op::DeviceHandle;
use registry::peripheral_lease::{LeaseConfig, PeripheralRegistry};
use transport::server::{AgentServer, ServerConfig};

#[derive(Parser, Debug)]
#[command(author, version, about = "Native cross-platform RemoteBLE Agent", long_about = None)]
struct Args {
    /// Port to listen on for WebSocket client connections
    #[arg(short, long, default_value_t = 8080, env = "PORT")]
    port: u16,

    /// Bearer token required for WebSocket upgrade authentication
    #[arg(short, long, env = "REMOTE_BLE_TOKEN")]
    token: Option<String>,

    /// BLE-disconnect grace window in milliseconds
    #[arg(long, default_value_t = 10000, env = "REMOTE_BLE_LEASE_GRACE_MS")]
    lease_grace_ms: u64,

    /// Transport-drop grace window in milliseconds
    #[arg(long, default_value_t = 10000, env = "REMOTE_BLE_TRANSPORT_GRACE_MS")]
    transport_grace_ms: u64,

    /// How often the active (real GATT round-trip) liveness probe runs, in milliseconds
    #[arg(long, default_value_t = 15000, env = "REMOTE_BLE_LIVENESS_PROBE_MS")]
    liveness_probe_ms: u64,

    /// Identifier strict mode: pass device handles through untranslated so a cross-platform
    /// format mismatch surfaces loudly on the client (dev/CI). Off by default (translation on).
    #[arg(long, default_value_t = false, env = "REMOTE_BLE_STRICT_IDENTIFIERS")]
    strict_identifiers: bool,

    /// Log level: trace, debug, info, warn, error, or off. Overridden by RUST_LOG if set.
    #[arg(long, env = "REMOTE_BLE_LOG")]
    log_level: Option<String>,

    /// Log format: full (default) or json (for journald/Loki setups).
    #[arg(long, default_value = "full", env = "REMOTE_BLE_LOG_FORMAT")]
    log_format: String,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();

    let env_filter = if let Ok(filter) = EnvFilter::try_from_default_env() {
        filter
    } else if let Some(ref level) = args.log_level {
        match level.to_lowercase().as_str() {
            "off" => EnvFilter::new("off"),
            lvl => EnvFilter::new(lvl),
        }
    } else {
        EnvFilter::new("info")
    };

    let subscriber = tracing_subscriber::fmt().with_env_filter(env_filter);

    if args.log_format == "json" {
        subscriber.json().init();
    } else {
        subscriber.init();
    }

    tracing::info!(
        "Starting RemoteBLE Agent (Rust) v{} | log level: {}",
        env!("CARGO_PKG_VERSION"),
        args.log_level.as_deref().unwrap_or("info")
    );

    let lease_config = LeaseConfig {
        default_exclusive: true,
        lease_grace: Duration::from_millis(args.lease_grace_ms),
        transport_grace: Duration::from_millis(args.transport_grace_ms),
        max_slots: 8,
    };

    let registry = PeripheralRegistry::new(lease_config);
    let ble_backend = Arc::new(
        BtleplugBackend::new(
            registry.clone(),
            Duration::from_millis(args.liveness_probe_ms),
        )
        .await?,
    );

    // When a lease's grace window expires, tear down the warm radio link. Wired here (after both
    // exist, since they reference each other) so the registry stays free of any BLE dependency.
    {
        let backend = ble_backend.clone();
        registry.set_teardown(move |handle| {
            let backend = backend.clone();
            async move {
                let device = DeviceHandle { value: handle };
                if let Err(e) = backend.disconnect(&device).await {
                    tracing::warn!("grace-expiry teardown failed for {}: {}", device.value, e);
                }
            }
        });
    }

    let addr = SocketAddr::from(([0, 0, 0, 0], args.port));
    let server_config = ServerConfig {
        addr,
        auth_token: args.token,
        strict_identifiers: Arc::new(std::sync::atomic::AtomicBool::new(args.strict_identifiers)),
    };

    let backend_for_shutdown = ble_backend.clone();
    let server = AgentServer::new(server_config, ble_backend, registry);

    // Run until the accept loop fails (it no longer does on transient errors) or a shutdown
    // signal arrives. On signal, disconnect tracked peripherals so a restart starts clean.
    tokio::select! {
        res = server.run() => res?,
        _ = shutdown_signal() => {
            tracing::info!("Shutdown signal received; disconnecting peripherals and exiting");
            backend_for_shutdown.disconnect_all().await;
        }
    }

    Ok(())
}

/// Resolves on SIGINT (Ctrl-C) or, on Unix, SIGTERM.
async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };

    #[cfg(unix)]
    let terminate = async {
        match tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate()) {
            Ok(mut sig) => {
                sig.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
