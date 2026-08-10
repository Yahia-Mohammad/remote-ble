mod ble;
mod protocol;
mod registry;
mod translate;
mod transport;

use clap::Parser;
use std::collections::{HashMap, HashSet};
use std::net::{IpAddr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;
use tracing_subscriber::EnvFilter;

use ble::backend::BleBackend;
use ble::btleplug_impl::BtleplugBackend;
use protocol::op::DeviceHandle;
use registry::peripheral_lease::{LeaseConfig, PeripheralRegistry};
use registry::write_policy::WritePolicy;
use transport::server::{AgentServer, ScanConcurrencyMode, ServerConfig};

#[derive(Parser, Debug)]
#[command(author, version, about = "Native cross-platform RemoteBLE Agent", long_about = None)]
struct Args {
    /// Address to listen on. Loopback is the safe default; choose a LAN address explicitly.
    #[arg(long, default_value = "127.0.0.1", env = "REMOTE_BLE_BIND")]
    bind: IpAddr,

    /// Port to listen on for WebSocket client connections
    #[arg(short, long, default_value_t = 8080, env = "PORT")]
    port: u16,

    /// Bearer token required for WebSocket upgrade authentication
    #[arg(short, long, env = "REMOTE_BLE_TOKEN")]
    token: Option<String>,

    /// Named bearer credentials in `principal=secret,other=secret` form.
    #[arg(long, env = "REMOTE_BLE_TOKENS")]
    tokens: Option<String>,

    /// Operator credential, presented as `X-RemoteBle-Operator: Bearer <secret>` on the upgrade.
    /// Widens what `agent.status` discloses to that session — every lease and its holder — and
    /// grants nothing else. Must be distinct from every client credential, so a normal bearer
    /// token cannot quietly acquire operator reach.
    #[arg(long, env = "REMOTE_BLE_OPERATOR_TOKEN")]
    operator_token: Option<String>,

    /// Path to a per-principal write policy file (U7). Absent means every write is allowed,
    /// matching pre-U7 behaviour; see docs/proposals/agent-write-policy.md for the schema.
    #[arg(long, env = "REMOTE_BLE_POLICY_FILE")]
    policy_file: Option<String>,

    /// Permit an unauthenticated non-loopback listener for local development only.
    #[arg(long, default_value_t = false, env = "REMOTE_BLE_ALLOW_INSECURE_LAN")]
    allow_insecure_lan: bool,

    /// BLE-disconnect grace window in milliseconds
    #[arg(long, default_value_t = 10000, env = "REMOTE_BLE_LEASE_GRACE_MS")]
    lease_grace_ms: u64,

    /// Transport-drop grace window in milliseconds. Two minutes by default: the binding case is a
    /// client with a process-per-command lifecycle (a CLI, a script, a coding agent) whose next
    /// command must resume the same warm link. Lower it on a shared rig, where the trade is that a
    /// peripheral stays leased for the whole window after its holder walks away.
    #[arg(long, default_value_t = 120_000, env = "REMOTE_BLE_TRANSPORT_GRACE_MS")]
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

    /// Short-circuit write-with-response on a connection whose writes have stopped completing,
    /// instead of waiting out the full GATT op timeout on every subsequent write. Workaround for
    /// a btleplug defect (confirmed on hardware, Rig A 2026-07-28): see
    /// `ble::btleplug_impl::DegradedWrites`. Mirrors the Kotlin agent's
    /// `REMOTE_BLE_WRITE_FAIL_FAST`; turn off to get the unmodified (wait-it-out) behavior back.
    // `action = Set` rather than the `bool` default of `SetTrue`, so the flag can actually be
    // turned *off*: a SetTrue flag whose default is already `true` can only ever re-affirm it,
    // and neither `--write-fail-fast false` nor `=false` parses. With Set + an optional value,
    // `--write-fail-fast false`, `--write-fail-fast=false` and the env var all work, while a bare
    // `--write-fail-fast` still means `true`.
    #[arg(
        long,
        action = clap::ArgAction::Set,
        num_args = 0..=1,
        default_value_t = true,
        default_missing_value = "true",
        env = "REMOTE_BLE_WRITE_FAIL_FAST"
    )]
    write_fail_fast: bool,

    /// Scan isolation policy: one physical multiplexed scan (default), a global single slot, or
    /// the backend's independent legacy paths.
    #[arg(long, value_enum, default_value_t = ScanConcurrencyMode::Multiplexed, env = "REMOTE_BLE_SCAN_CONCURRENCY")]
    scan_concurrency: ScanConcurrencyMode,
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
    let credentials = parse_credentials(args.token.as_deref(), args.tokens.as_deref())?;
    validate_operator_token(args.operator_token.as_deref(), &credentials)?;
    validate_bind(&args, !credentials.is_empty())?;

    // Mirrors `authenticate()`'s own resolution: with no credentials configured at all, every
    // connection is the anonymous principal; otherwise it's a named credential. A policy naming
    // anyone else is a typo the agent should refuse to boot on, not silently tolerate.
    let known_principals: HashSet<String> = if credentials.is_empty() {
        HashSet::from(["anonymous".to_string()])
    } else {
        credentials.keys().cloned().collect()
    };
    let write_policy = load_write_policy(args.policy_file.as_deref(), &known_principals)?;

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

    tracing::info!(
        "Degraded-write fail-fast: {} (REMOTE_BLE_WRITE_FAIL_FAST)",
        if args.write_fail_fast { "on" } else { "off" }
    );
    tracing::info!(
        "Scan concurrency: {:?} (REMOTE_BLE_SCAN_CONCURRENCY)",
        args.scan_concurrency
    );

    let registry = PeripheralRegistry::new(lease_config);
    let ble_backend = Arc::new(
        BtleplugBackend::new(
            registry.clone(),
            Duration::from_millis(args.liveness_probe_ms),
            args.write_fail_fast,
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

    let addr = SocketAddr::new(args.bind, args.port);
    let server_config = ServerConfig {
        addr,
        credentials: Arc::new(credentials),
        strict_identifiers: Arc::new(std::sync::atomic::AtomicBool::new(args.strict_identifiers)),
        scan_concurrency: args.scan_concurrency,
        transport_grace: Duration::from_millis(args.transport_grace_ms),
        operator_token: args.operator_token.clone(),
        write_policy,
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

/// The operator secret must not be one of the client secrets.
///
/// Without this a deployment could hand out one token that authenticates a client *and* carries
/// operator disclosure, which is exactly the "a normal bearer token must not silently gain operator
/// access" rule the status contract is built on. Fails startup rather than warning: a
/// misconfiguration here is invisible in normal operation and only shows up as one tenant reading
/// another's client ids. Mirrors the same check in the Kotlin agent's `AgentWebSocketServer`.
fn validate_operator_token(
    operator_token: Option<&str>,
    credentials: &HashMap<String, String>,
) -> Result<(), String> {
    let Some(token) = operator_token else {
        return Ok(());
    };
    if token.trim().is_empty() {
        return Err("operator token must not be blank".into());
    }
    if credentials.values().any(|secret| secret == token) {
        return Err("operator token must be distinct from every client credential".into());
    }
    Ok(())
}

fn validate_bind(args: &Args, has_token: bool) -> Result<(), String> {
    validate_bind_policy(args.bind, has_token, args.allow_insecure_lan)?;
    if !args.bind.is_loopback() && !has_token {
        tracing::warn!(
            "starting unauthenticated non-loopback listener because the insecure development override is enabled"
        );
    }
    Ok(())
}

fn parse_credentials(
    token: Option<&str>,
    named: Option<&str>,
) -> Result<HashMap<String, String>, String> {
    let mut credentials = HashMap::new();
    if let Some(named) = named.filter(|value| !value.trim().is_empty()) {
        for entry in named.split(',') {
            let Some((principal, secret)) = entry.split_once('=') else {
                return Err("REMOTE_BLE_TOKENS entries must use principal=secret".into());
            };
            if principal.trim().is_empty()
                || secret.trim().is_empty()
                || principal.len() > 128
                || secret.len() > 512
                || principal.contains('\0')
            {
                return Err(
                    "credential names and secrets must be non-empty and within size limits".into(),
                );
            }
            if credentials
                .insert(principal.to_string(), secret.to_string())
                .is_some()
            {
                return Err("REMOTE_BLE_TOKENS contains duplicate principals".into());
            }
            if credentials
                .values()
                .filter(|existing| *existing == secret)
                .count()
                > 1
            {
                return Err("REMOTE_BLE_TOKENS contains duplicate secrets".into());
            }
        }
    }
    if let Some(token) = token.filter(|value| !value.trim().is_empty()) {
        if credentials.contains_key("default") {
            return Err(
                "REMOTE_BLE_TOKEN cannot be combined with a named credential called 'default'"
                    .into(),
            );
        }
        if credentials.values().any(|secret| secret == token) {
            return Err("REMOTE_BLE_TOKEN cannot reuse a named credential secret".into());
        }
        credentials.insert("default".to_string(), token.to_string());
    }
    Ok(credentials)
}

/// Resolve the policy before the backend or listener starts: a nonblank unreadable or malformed
/// policy must fail the process closed rather than accidentally booting permissive.
fn load_write_policy(
    path: Option<&str>,
    known_principals: &HashSet<String>,
) -> Result<WritePolicy, std::io::Error> {
    match path {
        Some(path) if path.trim().is_empty() => {
            tracing::warn!(
                "write policy path is blank; treating it as unconfigured (write policy is permissive)"
            );
            Ok(WritePolicy::permissive())
        }
        Some(path) => {
            let raw = std::fs::read_to_string(path)?;
            WritePolicy::decode(&raw, known_principals).map_err(std::io::Error::other)
        }
        None => Ok(WritePolicy::permissive()),
    }
}

fn validate_bind_policy(
    bind: IpAddr,
    has_token: bool,
    allow_insecure_lan: bool,
) -> Result<(), String> {
    if bind.is_multicast() {
        return Err(format!("refusing multicast bind address {bind}"));
    }
    if !bind.is_loopback() && !has_token && !allow_insecure_lan {
        return Err("non-loopback bind requires REMOTE_BLE_TOKEN/--token; use \
             REMOTE_BLE_ALLOW_INSECURE_LAN=true only for local development"
            .into());
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

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::{Ipv4Addr, Ipv6Addr};

    #[test]
    fn bind_policy_allows_loopback_and_authenticated_lan() {
        assert!(validate_bind_policy(IpAddr::V4(Ipv4Addr::LOCALHOST), false, false).is_ok());
        assert!(validate_bind_policy(IpAddr::V4(Ipv4Addr::UNSPECIFIED), true, false).is_ok());
    }

    #[test]
    fn bind_policy_rejects_open_lan_and_multicast() {
        assert!(validate_bind_policy(IpAddr::V4(Ipv4Addr::UNSPECIFIED), false, false).is_err());
        assert!(
            validate_bind_policy(IpAddr::V4(Ipv4Addr::new(224, 0, 0, 1)), true, false).is_err()
        );
        assert!(validate_bind_policy(IpAddr::V6(Ipv6Addr::LOCALHOST), false, false).is_ok());
    }

    #[test]
    fn named_credentials_parse_without_exposing_names_to_clients() {
        let credentials = parse_credentials(Some("legacy"), Some("lab=one,staging=two")).unwrap();
        assert_eq!(credentials.len(), 3);
        assert!(parse_credentials(Some("legacy"), Some("default=other")).is_err());
        assert!(parse_credentials(None, Some("alpha=same,beta=same")).is_err());
    }

    #[test]
    fn policy_file_loader_is_permissive_only_when_absent_and_fails_closed_otherwise() {
        let known = HashSet::from(["lab-a".to_string()]);
        assert!(!load_write_policy(None, &known).unwrap().enforced());
        assert!(!load_write_policy(Some(""), &known).unwrap().enforced());
        assert!(!load_write_policy(Some(" \t "), &known).unwrap().enforced());

        let missing =
            std::env::temp_dir().join(format!("remoteble-missing-policy-{}", std::process::id()));
        assert!(load_write_policy(missing.to_str(), &known).is_err());

        let malformed =
            std::env::temp_dir().join(format!("remoteble-invalid-policy-{}", std::process::id()));
        std::fs::write(
            &malformed,
            r#"{"version":1,"principals":{"lab-a":{"writes":[]}}}"#,
        )
        .unwrap();
        assert!(
            load_write_policy(malformed.to_str(), &known)
                .unwrap()
                .enforced()
        );
        std::fs::write(&malformed, "not json").unwrap();
        let result = load_write_policy(malformed.to_str(), &known);
        let _ = std::fs::remove_file(malformed);
        assert!(result.is_err());
    }
}
