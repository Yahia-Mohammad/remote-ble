//! The agent's persistent TLS identity (#39, `docs/proposals/agent-transport-encryption.md`).
//!
//! An ECDSA P-256 key and its self-signed certificate, stored as one PEM file (a PKCS#8 key, then
//! the certificate). The format, the default location and `REMOTE_BLE_IDENTITY_FILE` match the
//! Kotlin JVM agent's `AgentIdentityStore`, so the two desktop agents on one host present one
//! identity. Clients pin [AgentIdentity::fingerprint]: the SHA-256 of the key's
//! SubjectPublicKeyInfo, written `sha256:<64 lowercase hex>`.

use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};
use std::sync::Arc;

use rcgen::{
    CertificateParams, DistinguishedName, DnType, ExtendedKeyUsagePurpose, IsCa, KeyPair,
    KeyUsagePurpose, PKCS_ECDSA_P256_SHA256, PublicKeyData, SerialNumber,
};
use rustls::pki_types::pem::PemObject;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use time::{Duration, OffsetDateTime};

/// The DNS name every agent certificate carries. Agents are reached by IP addresses that change,
/// so their certificates cannot name them, while TLS clients (Ktor's CIO among them) verify the
/// server name regardless of the trust decision. A pinning client presents this name; the pin
/// remains the identity check. `.invalid` is reserved (RFC 2606). Same value as the Kotlin
/// `AGENT_TLS_SERVER_NAME`.
pub const TLS_SERVER_NAME: &str = "agent.remoteble.invalid";

pub const FILE_NAME: &str = "agent-identity.pem";

#[derive(Debug, thiserror::Error)]
pub enum IdentityError {
    #[error("agent identity at {path}: {source}")]
    Io { path: PathBuf, source: io::Error },
    #[error("agent identity at {path} is unusable: {reason}")]
    Invalid { path: PathBuf, reason: String },
    #[error("cannot create an agent identity: {0}")]
    Generate(String),
    #[error("cannot build the TLS configuration: {0}")]
    Tls(String),
}

pub struct AgentIdentity {
    pub certificate: CertificateDer<'static>,
    pub key: PrivatePkcs8KeyDer<'static>,
    /// `sha256:<hex>` of the SPKI: the value a client pins.
    pub fingerprint: String,
}

/// The per-user default, matching the JVM agent: `~/Library/Application Support/RemoteBLE/` on
/// macOS, `%APPDATA%\RemoteBLE\` on Windows, `$XDG_CONFIG_HOME/remoteble/` (or
/// `~/.config/remoteble/`) elsewhere.
pub fn default_path(os: &str, env: impl Fn(&str) -> Option<String>, home: &Path) -> PathBuf {
    let set = |name: &str| env(name).filter(|value| !value.trim().is_empty());
    let dir = match os {
        "macos" => home
            .join("Library")
            .join("Application Support")
            .join("RemoteBLE"),
        "windows" => set("APPDATA")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.to_path_buf())
            .join("RemoteBLE"),
        _ => set("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".config"))
            .join("remoteble"),
    };
    dir.join(FILE_NAME)
}

/// [default_path] for the running host.
pub fn default_path_for_host() -> PathBuf {
    let home = std::env::var_os(if cfg!(windows) { "USERPROFILE" } else { "HOME" })
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."));
    default_path(std::env::consts::OS, |name| std::env::var(name).ok(), &home)
}

/// Loads the identity at `path`, creating it on first use. `reset` discards an existing one
/// first, which changes the fingerprint and makes every paired client fail with an identity error
/// until it pairs again: that is the point of a reset.
pub fn load_or_create(path: &Path, reset: bool) -> Result<AgentIdentity, IdentityError> {
    if reset {
        remove(path)?;
    }
    if !path.exists() {
        write(path, &generate()?)?;
        // Another agent process may have created it in the same instant; whichever file won the
        // publish is the identity, so read it back rather than trusting the in-memory copy.
        let created = read(path)?;
        tracing::info!(
            "created agent identity {} at {}",
            created.fingerprint,
            path.display()
        );
        return Ok(created);
    }
    read(path)
}

/// Deletes the identity file if present; `Ok(true)` when something was removed.
pub fn remove(path: &Path) -> Result<bool, IdentityError> {
    match fs::remove_file(path) {
        Ok(()) => Ok(true),
        Err(e) if e.kind() == io::ErrorKind::NotFound => Ok(false),
        Err(source) => Err(IdentityError::Io {
            path: path.to_path_buf(),
            source,
        }),
    }
}

/// A rustls server configuration presenting `identity`. TLS 1.3 and 1.2 are both enabled; 1.2 is
/// not legacy tolerance, since Ktor's CIO client, the SDK's JVM engine, speaks nothing newer.
pub fn server_config(identity: &AgentIdentity) -> Result<Arc<rustls::ServerConfig>, IdentityError> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let config = rustls::ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13, &rustls::version::TLS12])
        .map_err(|e| IdentityError::Tls(e.to_string()))?
        .with_no_client_auth()
        .with_single_cert(
            vec![identity.certificate.clone()],
            PrivateKeyDer::Pkcs8(identity.key.clone_key()),
        )
        .map_err(|e| IdentityError::Tls(e.to_string()))?;
    Ok(Arc::new(config))
}

/// `sha256:<hex>` of a SubjectPublicKeyInfo.
pub fn fingerprint_of_spki(spki: &[u8]) -> String {
    let digest = ring::digest::digest(&ring::digest::SHA256, spki);
    let hex: String = digest
        .as_ref()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect();
    format!("sha256:{hex}")
}

/// A fresh identity as PEM: a PKCS#8 key followed by its certificate.
fn generate() -> Result<String, IdentityError> {
    let failed = |e: rcgen::Error| IdentityError::Generate(e.to_string());
    let key = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256).map_err(failed)?;
    let mut params = CertificateParams::new(vec![TLS_SERVER_NAME.to_string()]).map_err(failed)?;
    let mut name = DistinguishedName::new();
    name.push(DnType::CommonName, "RemoteBLE Agent");
    params.distinguished_name = name;
    params.is_ca = IsCa::ExplicitNoCa;
    // digitalSignature only: an ECDHE key exchange is authenticated by a signature.
    params.key_usages = vec![KeyUsagePurpose::DigitalSignature];
    params.extended_key_usages = vec![ExtendedKeyUsagePurpose::ServerAuth];
    // A day back, so a client whose clock runs slightly behind still sees a valid start; no
    // well-defined expiry (RFC 5280 §4.1.2.5), since clients pin the key and ignore validity.
    params.not_before = OffsetDateTime::now_utc() - Duration::days(1);
    params.not_after = time::macros::datetime!(9999-12-31 23:59:59 UTC);
    let mut serial = [0u8; 16];
    ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut serial)
        .map_err(|_| IdentityError::Generate("no system randomness".into()))?;
    // A positive serial: a set top bit would encode as a negative INTEGER.
    serial[0] &= 0x7F;
    params.serial_number = Some(SerialNumber::from_slice(&serial));
    let certificate = params.self_signed(&key).map_err(failed)?;
    Ok(format!("{}{}", key.serialize_pem(), certificate.pem()))
}

fn read(path: &Path) -> Result<AgentIdentity, IdentityError> {
    let invalid = |reason: String| IdentityError::Invalid {
        path: path.to_path_buf(),
        reason,
    };
    let pem = fs::read(path).map_err(|source| IdentityError::Io {
        path: path.to_path_buf(),
        source,
    })?;
    let key = PrivatePkcs8KeyDer::from_pem_slice(&pem)
        .map_err(|e| invalid(format!("no PKCS#8 PRIVATE KEY block ({e})")))?;
    let certificate = CertificateDer::from_pem_slice(&pem)
        .map_err(|e| invalid(format!("no CERTIFICATE block ({e})")))?;
    let pair = KeyPair::try_from(&key).map_err(|e| invalid(format!("unusable key ({e})")))?;
    // The fingerprint is the certificate's SPKI, since that is what a client hashes; a key that
    // does not belong to the certificate would make every handshake fail, so refuse it here.
    let spki = certificate_spki(&certificate)
        .ok_or_else(|| invalid("the certificate is not a readable X.509 certificate".into()))?;
    if pair.subject_public_key_info() != spki {
        return Err(invalid(
            "the private key does not belong to the certificate".into(),
        ));
    }
    let fingerprint = fingerprint_of_spki(spki);
    Ok(AgentIdentity {
        fingerprint,
        certificate,
        key,
    })
}

/// Publishes `pem` at `path` without ever replacing an existing file, so a concurrent creator's
/// identity is kept. A rename would not do: on POSIX it silently replaces the target. A hard link
/// is atomic and fails if the name exists; where links are unsupported, the fallback refuses an
/// existing target with only a check-then-rename window left. The file is owner-only on Unix.
fn write(path: &Path, pem: &str) -> Result<(), IdentityError> {
    let io_error = |source: io::Error| IdentityError::Io {
        path: path.to_path_buf(),
        source,
    };
    let dir = path
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .unwrap_or_else(|| Path::new("."));
    fs::create_dir_all(dir).map_err(io_error)?;
    // Random, not the PID: containers sharing a volume each run the agent as PID 1.
    let mut nonce = [0u8; 8];
    ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut nonce)
        .map_err(|_| IdentityError::Generate("no system randomness".into()))?;
    let nonce: String = nonce.iter().map(|byte| format!("{byte:02x}")).collect();
    let temp = dir.join(format!(".agent-identity.{nonce}.tmp"));
    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    std::os::unix::fs::OpenOptionsExt::mode(&mut options, 0o600);
    // Opened before the cleanup below can run, so a failure here never removes another's file.
    let mut file = options.open(&temp).map_err(io_error)?;
    let result = (|| {
        file.write_all(pem.as_bytes())?;
        file.sync_all()?;
        // Closed before publishing: Windows refuses to rename an open file.
        drop(file);
        match fs::hard_link(&temp, path) {
            Ok(()) => Ok(()),
            Err(e) if e.kind() == io::ErrorKind::AlreadyExists => Ok(()), // lost the race
            Err(_) if path.exists() => Ok(()),
            Err(_) => fs::rename(&temp, path),
        }
    })();
    let _ = fs::remove_file(&temp);
    result.map_err(io_error)
}

/// The SubjectPublicKeyInfo inside a DER certificate: the seventh field of `tbsCertificate`, after
/// the explicit version that every agent certificate carries. A minimal, bounds-checked DER walk,
/// enough for the certificates the two agents make; `None` for anything else.
pub fn certificate_spki(certificate: &[u8]) -> Option<&[u8]> {
    /// (header length, content length) of the TLV at the start of `bytes`.
    fn header(bytes: &[u8]) -> Option<(usize, usize)> {
        let first = *bytes.get(1)? as usize;
        if first < 0x80 {
            return Some((2, first));
        }
        let count = first & 0x7F;
        if count == 0 || count > 4 {
            return None;
        }
        let len = bytes
            .get(2..2 + count)?
            .iter()
            .fold(0usize, |acc, b| (acc << 8) | *b as usize);
        Some((2 + count, len))
    }
    let (outer, _) = header(certificate)?;
    let tbs = certificate.get(outer..)?;
    let (tbs_header, _) = header(tbs)?;
    let mut rest = tbs.get(tbs_header..)?;
    for _ in 0..6 {
        let (h, len) = header(rest)?;
        rest = rest.get(h + len..)?;
    }
    let (h, len) = header(rest)?;
    if rest.first() != Some(&0x30) {
        return None; // an SPKI is a SEQUENCE
    }
    rest.get(..h + len)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "remoteble-identity-{name}-{}-{}",
            std::process::id(),
            OffsetDateTime::now_utc().unix_timestamp_nanos()
        ));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn default_paths_follow_each_platforms_convention_and_match_the_jvm_agent() {
        let env = |name: &str| match name {
            "APPDATA" => Some(r"C:\Users\a\AppData\Roaming".to_string()),
            "XDG_CONFIG_HOME" => Some("/xdg".to_string()),
            _ => None,
        };
        let home = Path::new("/home/a");
        assert_eq!(
            default_path("macos", env, home),
            PathBuf::from("/home/a/Library/Application Support/RemoteBLE/agent-identity.pem")
        );
        assert_eq!(
            default_path("linux", env, home),
            PathBuf::from("/xdg/remoteble/agent-identity.pem")
        );
        assert_eq!(
            default_path("linux", |_| None, home),
            PathBuf::from("/home/a/.config/remoteble/agent-identity.pem")
        );
        assert_eq!(
            default_path("windows", env, home),
            PathBuf::from(r"C:\Users\a\AppData\Roaming")
                .join("RemoteBLE")
                .join(FILE_NAME)
        );
    }

    #[test]
    fn the_identity_is_stable_across_loads_and_changes_on_reset() {
        let dir = temp_dir("stable");
        let path = dir.join("nested").join(FILE_NAME);

        let first = load_or_create(&path, false).unwrap();
        let again = load_or_create(&path, false).unwrap();
        let reset = load_or_create(&path, true).unwrap();

        assert_eq!(first.fingerprint, again.fingerprint);
        assert_eq!(first.certificate, again.certificate);
        assert_ne!(first.fingerprint, reset.fingerprint);
        assert!(remove(&path).unwrap());
        assert!(!remove(&path).unwrap());
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn the_fingerprint_is_the_sha256_of_the_certificates_spki() {
        let dir = temp_dir("spki");
        let identity = load_or_create(&dir.join(FILE_NAME), false).unwrap();

        assert_eq!(
            identity.fingerprint,
            fingerprint_of_spki(certificate_spki(&identity.certificate).unwrap())
        );
        assert!(identity.fingerprint.starts_with("sha256:"));
        assert_eq!(identity.fingerprint.len(), "sha256:".len() + 64);
        fs::remove_dir_all(dir).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn the_file_is_readable_by_its_owner_only_and_no_temp_file_remains() {
        use std::os::unix::fs::PermissionsExt;
        let dir = temp_dir("perms");
        let path = dir.join(FILE_NAME);
        load_or_create(&path, false).unwrap();

        assert_eq!(
            fs::metadata(&path).unwrap().permissions().mode() & 0o777,
            0o600
        );
        let names: Vec<_> = fs::read_dir(&dir)
            .unwrap()
            .map(|entry| entry.unwrap().file_name())
            .collect();
        assert_eq!(names, vec![std::ffi::OsString::from(FILE_NAME)]);
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn another_writers_temp_file_neither_blocks_creation_nor_is_removed() {
        let dir = temp_dir("temp");
        // What a second container sharing the volume, also PID 1, would have open.
        let foreign = dir.join(format!(".agent-identity.{}.tmp", std::process::id()));
        fs::write(&foreign, "in progress").unwrap();

        load_or_create(&dir.join(FILE_NAME), false).unwrap();

        assert_eq!(fs::read_to_string(&foreign).unwrap(), "in progress");
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn a_key_that_does_not_belong_to_the_certificate_is_refused() {
        let dir = temp_dir("mismatch");
        let (a, b) = (dir.join("a.pem"), dir.join("b.pem"));
        load_or_create(&a, false).unwrap();
        load_or_create(&b, false).unwrap();
        let (a_pem, b_pem) = (
            fs::read_to_string(&a).unwrap(),
            fs::read_to_string(&b).unwrap(),
        );
        let key_of_a = &a_pem[..a_pem.find("-----BEGIN CERTIFICATE-----").unwrap()];
        let cert_of_b = &b_pem[b_pem.find("-----BEGIN CERTIFICATE-----").unwrap()..];
        let mixed = dir.join("mixed.pem");
        fs::write(&mixed, format!("{key_of_a}{cert_of_b}")).unwrap();

        match load_or_create(&mixed, false) {
            Err(IdentityError::Invalid { reason, .. }) => {
                assert!(reason.contains("does not belong"), "{reason}")
            }
            other => panic!(
                "expected a refusal, got {:?}",
                other.map(|id| id.fingerprint)
            ),
        }
        fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn certificate_spki_returns_none_for_anything_that_is_not_a_certificate() {
        assert_eq!(certificate_spki(&[]), None);
        assert_eq!(certificate_spki(&[0x30, 0x03, 0x02, 0x01, 0x00]), None);
        assert_eq!(
            certificate_spki(&[0x30, 0x84, 0xff, 0xff, 0xff, 0xff]),
            None
        );
    }

    #[test]
    fn the_server_config_accepts_the_identity() {
        let dir = temp_dir("config");
        let identity = load_or_create(&dir.join(FILE_NAME), false).unwrap();
        assert!(server_config(&identity).is_ok());
        fs::remove_dir_all(dir).unwrap();
    }
}
