//! Agent-side device-handle translation (capability `identifier.translate`), the Rust parity of the
//! Kotlin `HandleTranslator`. It rewrites every outgoing real radio handle into the client's
//! declared [`IdentifierFormat`] so the client can build a native Kable `Identifier`, and
//! reverse-maps incoming ops back to the real handle so they still route to the radio.
//!
//! Translation is active only when the client negotiated the capability, strict mode is off, and the
//! client's format genuinely can't hold the agent's native handle (see [`needs_rewrite`]). Otherwise
//! every call is an identity pass-through. Synthesis uses a non-cryptographic digest — handles are
//! opaque routing tokens, not secrets, and each agent only ever translates its own radio's handles,
//! so cross-language reproducibility isn't required, only per-agent determinism.

use crate::protocol::events::{AdvertisementDto, AgentEvent};
use crate::protocol::op::{DeviceHandle, IdentifierFormat, Op};
use parking_lot::Mutex;
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

/// Reverse-map cap; the eldest client handle is evicted past this so a client scanning a crowded
/// area can't grow the map without bound. Connected peripherals are re-inserted on rediscovery.
const MAX_ENTRIES: usize = 4096;

/// The [`IdentifierFormat`] this agent's platform (btleplug host) mints handles in.
pub fn agent_identifier_format() -> IdentifierFormat {
    match std::env::consts::OS {
        "macos" | "ios" => IdentifierFormat::Uuid,
        "windows" => IdentifierFormat::MacAddress,
        // Linux (bluez) and any other host default to the bluez PeripheralId JSON shape.
        _ => IdentifierFormat::BluezJson,
    }
}

/// True when a handle minted in `agent` format can't be held as-is by a client declaring `client`
/// format and so must be synthesized. `STRING` (Android) holds any string; `BLUEZ_JSON` is the
/// stubbed synthesizer (pass through → the Linux-host-JVM client falls back to `.handle`); a client
/// already speaking the agent's format needs no rewrite.
pub fn needs_rewrite(client: IdentifierFormat, agent: IdentifierFormat) -> bool {
    match client {
        IdentifierFormat::String | IdentifierFormat::BluezJson => false,
        _ => client != agent,
    }
}

struct Inner {
    /// `Some(format)` once a handshake configured active translation; `None` = identity.
    synth: Option<IdentifierFormat>,
    reverse: HashMap<String, String>,
    order: VecDeque<String>,
}

pub struct HandleTranslator {
    agent_format: IdentifierFormat,
    strict: Arc<AtomicBool>,
    inner: Mutex<Inner>,
}

impl HandleTranslator {
    pub fn new(agent_format: IdentifierFormat, strict: Arc<AtomicBool>) -> Self {
        Self {
            agent_format,
            strict,
            inner: Mutex::new(Inner {
                synth: None,
                reverse: HashMap::new(),
                order: VecDeque::new(),
            }),
        }
    }

    /// Apply the client's handshake: enable translation iff it negotiated the capability and its
    /// format actually needs a rewrite. The reverse map is deliberately **preserved**: its entries
    /// only ever map a client-facing handle back to the real radio handle, which stays correct
    /// across a reconfigure — clearing them would break op routing for every handle the client
    /// already holds. (Translators are per-connection, so in practice this runs once per socket;
    /// preserving the map keeps any future second caller safe by construction.)
    pub fn configure(&self, client_format: Option<IdentifierFormat>, negotiated: bool) {
        let synth = match client_format {
            Some(fmt) if negotiated && needs_rewrite(fmt, self.agent_format) => Some(fmt),
            _ => None,
        };
        self.inner.lock().synth = synth;
    }

    /// Real radio handle → the handle the client sees. Records the reverse mapping for routing.
    ///
    /// Public within the crate because handles now leave by two doors: inside an `AgentEvent` (see
    /// [Self::to_client_event]) and inside an `agent.status` reply's lease rows. Both must mint the
    /// client-facing form and register the reverse mapping — a handle a client reads from a status
    /// reply has to be routable in its next op.
    pub(crate) fn to_client(&self, real: &str) -> String {
        if self.strict.load(Ordering::Relaxed) {
            return real.to_string();
        }
        let mut inner = self.inner.lock();
        let fmt = match inner.synth {
            Some(f) => f,
            None => return real.to_string(),
        };
        let client = synthesize(fmt, real);
        if client != real {
            if !inner.reverse.contains_key(&client) {
                inner.order.push_back(client.clone());
                if inner.order.len() > MAX_ENTRIES
                    && let Some(old) = inner.order.pop_front()
                {
                    inner.reverse.remove(&old);
                }
            }
            inner.reverse.insert(client.clone(), real.to_string());
        }
        client
    }

    /// Pre-populates the reverse map for `real_handles` by re-running the deterministic
    /// synthesis — exactly the mapping an outgoing event would record. Called on the handshake
    /// with the real handles this client's leases still hold (a reconnect within the transport
    /// grace), so an op replayed with a previously-issued translated handle routes again even
    /// though this fresh connection has emitted no event for it yet. No-op when not translating.
    pub fn prime(&self, real_handles: &[String]) {
        for real in real_handles {
            let _ = self.to_client(real);
        }
    }

    /// Client-facing handle → the real radio handle (identity when unmapped/untranslated).
    pub fn to_real(&self, client: &str) -> String {
        self.inner
            .lock()
            .reverse
            .get(client)
            .cloned()
            .unwrap_or_else(|| client.to_string())
    }

    /// Drop the reverse entries for a real handle once its peripheral is fully released.
    pub fn evict(&self, real: &str) {
        let mut inner = self.inner.lock();
        let stale: Vec<String> = inner
            .reverse
            .iter()
            .filter(|(_, v)| v.as_str() == real)
            .map(|(k, _)| k.clone())
            .collect();
        for k in stale {
            inner.reverse.remove(&k);
            inner.order.retain(|x| x != &k);
        }
    }

    /// Reverse-translate the client-facing handle in an incoming op back to the real radio handle.
    pub fn to_real_op(&self, op: Op) -> Op {
        map_op_device(op, |d| DeviceHandle {
            value: self.to_real(&d.value),
        })
    }

    /// Forward-translate the real handle an outgoing event carries into the client's format.
    pub fn to_client_event(&self, event: AgentEvent) -> AgentEvent {
        match event {
            AgentEvent::ScanResult {
                scan_id,
                advertisement,
            } => AgentEvent::ScanResult {
                scan_id,
                advertisement: self.translate_ad(advertisement),
            },
            AgentEvent::ScanResultBatch {
                scan_id,
                advertisements,
            } => AgentEvent::ScanResultBatch {
                scan_id,
                advertisements: advertisements
                    .into_iter()
                    .map(|a| self.translate_ad(a))
                    .collect(),
            },
            AgentEvent::ConnectionState {
                device,
                state,
                reason,
            } => AgentEvent::ConnectionState {
                device: DeviceHandle {
                    value: self.to_client(&device.value),
                },
                state,
                reason,
            },
            AgentEvent::BondState {
                device,
                state,
                reason,
            } => AgentEvent::BondState {
                device: DeviceHandle {
                    value: self.to_client(&device.value),
                },
                state,
                reason,
            },
            // No handle to translate.
            other @ (AgentEvent::Notification { .. } | AgentEvent::SlotState { .. }) => other,
        }
    }

    fn translate_ad(&self, ad: AdvertisementDto) -> AdvertisementDto {
        AdvertisementDto {
            device: DeviceHandle {
                value: self.to_client(&ad.device.value),
            },
            ..ad
        }
    }
}

/// Returns `op` with its [`DeviceHandle`] replaced by `f` applied to it; device-less ops unchanged.
fn map_op_device(op: Op, f: impl Fn(DeviceHandle) -> DeviceHandle) -> Op {
    match op {
        Op::Connect { device } => Op::Connect { device: f(device) },
        Op::Disconnect { device } => Op::Disconnect { device: f(device) },
        Op::Discover { device } => Op::Discover { device: f(device) },
        Op::Read { device, char } => Op::Read {
            device: f(device),
            char,
        },
        Op::Write {
            device,
            char,
            value,
            with_response,
        } => Op::Write {
            device: f(device),
            char,
            value,
            with_response,
        },
        Op::ObserveStart {
            sub_id,
            device,
            char,
        } => Op::ObserveStart {
            sub_id,
            device: f(device),
            char,
        },
        Op::RequestMtu { device, mtu } => Op::RequestMtu {
            device: f(device),
            mtu,
        },
        Op::ReadDescriptor { device, desc } => Op::ReadDescriptor {
            device: f(device),
            desc,
        },
        Op::WriteDescriptor {
            device,
            desc,
            value,
        } => Op::WriteDescriptor {
            device: f(device),
            desc,
            value,
        },
        Op::Pair { device } => Op::Pair { device: f(device) },
        Op::Unpair { device } => Op::Unpair { device: f(device) },
        Op::RequestConnectionPriority { device, priority } => Op::RequestConnectionPriority {
            device: f(device),
            priority,
        },
        Op::ReadRssi { device } => Op::ReadRssi { device: f(device) },
        Op::SetConnParams {
            device,
            profile,
            hint,
        } => Op::SetConnParams {
            device: f(device),
            profile,
            hint,
        },
        other @ (Op::ScanStart { .. }
        | Op::ScanStop { .. }
        | Op::ObserveStop { .. }
        | Op::AgentStatus) => other,
    }
}

/// Deterministically maps a real handle to a valid string of the target `format`.
pub fn synthesize(format: IdentifierFormat, real: &str) -> String {
    match format {
        IdentifierFormat::Uuid => to_uuid_string(digest128(real)),
        IdentifierFormat::MacAddress => to_mac_string(digest128(real)),
        // STRING / BLUEZ_JSON never reach here (needs_rewrite == false); stay identity-safe.
        IdentifierFormat::String | IdentifierFormat::BluezJson => real.to_string(),
    }
}

/// 16 bytes of deterministic digest over `input` via two independently-seeded 64-bit FNV-1a passes.
fn digest128(input: &str) -> [u8; 16] {
    let bytes = input.as_bytes();
    let hi = fnv1a64(bytes, 0xcbf2_9ce4_8422_2325);
    let lo = fnv1a64(bytes, 0x9e37_79b9_7f4a_7c15);
    let mut out = [0u8; 16];
    out[..8].copy_from_slice(&hi.to_be_bytes());
    out[8..].copy_from_slice(&lo.to_be_bytes());
    out
}

fn fnv1a64(data: &[u8], seed: u64) -> u64 {
    let mut h = seed;
    for &b in data {
        h ^= b as u64;
        h = h.wrapping_mul(0x0000_0100_0000_01b3);
    }
    h
}

/// RFC-4122-shaped UUID (version 5 / variant bits set) from 16 digest bytes.
fn to_uuid_string(mut d: [u8; 16]) -> String {
    d[6] = (d[6] & 0x0f) | 0x50; // version 5
    d[8] = (d[8] & 0x3f) | 0x80; // RFC-4122 variant
    let hex: String = d.iter().map(|b| format!("{:02x}", b)).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[0..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..32]
    )
}

/// Colon-separated MAC from the first 6 digest bytes, marked locally-administered unicast.
fn to_mac_string(d: [u8; 16]) -> String {
    let first = (d[0] & 0xfe) | 0x02; // clear multicast bit, set locally-administered
    let octets = [first, d[1], d[2], d[3], d[4], d[5]];
    octets
        .iter()
        .map(|b| format!("{:02X}", b))
        .collect::<Vec<_>>()
        .join(":")
}

#[cfg(test)]
mod tests {
    use super::*;

    const REAL: &str = "some-native-radio-handle-1234";

    fn scan_result(handle: &str) -> AgentEvent {
        AgentEvent::ScanResult {
            scan_id: 1,
            advertisement: AdvertisementDto {
                device: DeviceHandle {
                    value: handle.to_string(),
                },
                name: None,
                rssi: -50,
                service_uuids: vec![],
                manufacturer_data: Default::default(),
            },
        }
    }

    fn emitted_device(event: &AgentEvent) -> String {
        match event {
            AgentEvent::ScanResult { advertisement, .. } => advertisement.device.value.clone(),
            _ => panic!("expected ScanResult"),
        }
    }

    fn configured(
        client: Option<IdentifierFormat>,
        agent: IdentifierFormat,
        negotiated: bool,
        strict: bool,
    ) -> HandleTranslator {
        let t = HandleTranslator::new(agent, Arc::new(AtomicBool::new(strict)));
        t.configure(client, negotiated);
        t
    }

    #[test]
    fn needs_rewrite_matrix() {
        assert!(!needs_rewrite(
            IdentifierFormat::String,
            IdentifierFormat::Uuid
        ));
        assert!(!needs_rewrite(
            IdentifierFormat::BluezJson,
            IdentifierFormat::Uuid
        ));
        assert!(!needs_rewrite(
            IdentifierFormat::Uuid,
            IdentifierFormat::Uuid
        ));
        assert!(needs_rewrite(
            IdentifierFormat::Uuid,
            IdentifierFormat::BluezJson
        ));
        assert!(needs_rewrite(
            IdentifierFormat::MacAddress,
            IdentifierFormat::Uuid
        ));
    }

    #[test]
    fn synthesize_uuid_well_formed_and_deterministic() {
        let a = synthesize(IdentifierFormat::Uuid, REAL);
        assert_eq!(a, synthesize(IdentifierFormat::Uuid, REAL));
        assert_ne!(a, synthesize(IdentifierFormat::Uuid, "other"));
        let parts: Vec<&str> = a.split('-').collect();
        assert_eq!(
            parts.iter().map(|p| p.len()).collect::<Vec<_>>(),
            vec![8, 4, 4, 4, 12]
        );
        assert!(a.chars().all(|c| c.is_ascii_hexdigit() || c == '-'));
        assert_eq!(&parts[2][0..1], "5", "version 5 nibble");
        assert!(matches!(&parts[3][0..1], "8" | "9" | "a" | "b"));
    }

    #[test]
    fn synthesize_mac_well_formed() {
        let mac = synthesize(IdentifierFormat::MacAddress, REAL);
        let octets: Vec<&str> = mac.split(':').collect();
        assert_eq!(octets.len(), 6);
        assert!(octets.iter().all(|o| o.len() == 2));
        let first = u8::from_str_radix(octets[0], 16).unwrap();
        assert_eq!(first & 0x02, 0x02);
        assert_eq!(first & 0x01, 0x00);
    }

    #[test]
    fn synthesize_identity_formats_pass_through() {
        assert_eq!(synthesize(IdentifierFormat::String, REAL), REAL);
        assert_eq!(synthesize(IdentifierFormat::BluezJson, REAL), REAL);
    }

    #[test]
    fn active_translator_rewrites_and_reverse_maps() {
        let t = configured(
            Some(IdentifierFormat::Uuid),
            IdentifierFormat::BluezJson,
            true,
            false,
        );
        let emitted = emitted_device(&t.to_client_event(scan_result(REAL)));
        assert_ne!(emitted, REAL);
        assert!(emitted.contains('-'));
        assert_eq!(t.to_real(&emitted), REAL);
        assert_eq!(t.to_real("unknown"), "unknown");
    }

    #[test]
    fn prime_reseeds_mappings_for_replayed_handles() {
        let t = configured(
            Some(IdentifierFormat::Uuid),
            IdentifierFormat::BluezJson,
            true,
            false,
        );
        // A fresh connection's translator starts with an empty reverse map, so a handle
        // issued by the previous connection can't route...
        let issued = synthesize(IdentifierFormat::Uuid, REAL);
        assert_eq!(t.to_real(&issued), issued);
        // ...until prime() re-derives it from the real handles the registry kept warm.
        t.prime(&[REAL.to_string()]);
        assert_eq!(t.to_real(&issued), REAL);
    }

    #[test]
    fn prime_is_identity_when_not_translating() {
        let t = configured(
            Some(IdentifierFormat::Uuid),
            IdentifierFormat::BluezJson,
            false,
            false,
        );
        t.prime(&[REAL.to_string()]);
        // Nothing recorded: an untranslated client replays real handles, which pass through.
        assert_eq!(t.to_real(REAL), REAL);
    }

    #[test]
    fn reconfiguring_preserves_the_reverse_map() {
        // A reconfigure must never orphan handles the client already holds: reverse entries
        // map a client-facing handle back to the real radio handle, which stays correct no
        // matter what the synthesis format changes to. (The connection loop additionally
        // enforces first-hello-wins, so production reconfigures don't happen — this pins the
        // translator as safe on its own, without relying on that loop discipline.)
        let t = configured(
            Some(IdentifierFormat::Uuid),
            IdentifierFormat::BluezJson,
            true,
            false,
        );
        let emitted = emitted_device(&t.to_client_event(scan_result(REAL)));
        assert_eq!(t.to_real(&emitted), REAL);

        t.configure(Some(IdentifierFormat::MacAddress), true);
        assert_eq!(
            t.to_real(&emitted),
            REAL,
            "reverse mapping must survive reconfigure"
        );
    }

    #[test]
    fn inactive_when_capability_not_negotiated() {
        let t = configured(
            Some(IdentifierFormat::Uuid),
            IdentifierFormat::BluezJson,
            false,
            false,
        );
        assert_eq!(emitted_device(&t.to_client_event(scan_result(REAL))), REAL);
    }

    #[test]
    fn strict_mode_passes_through() {
        let t = configured(
            Some(IdentifierFormat::Uuid),
            IdentifierFormat::BluezJson,
            true,
            true,
        );
        assert_eq!(emitted_device(&t.to_client_event(scan_result(REAL))), REAL);
    }

    #[test]
    fn android_client_never_rewrites() {
        let t = configured(
            Some(IdentifierFormat::String),
            IdentifierFormat::Uuid,
            true,
            false,
        );
        assert_eq!(emitted_device(&t.to_client_event(scan_result(REAL))), REAL);
    }

    #[test]
    fn evict_drops_reverse_mapping() {
        let t = configured(
            Some(IdentifierFormat::Uuid),
            IdentifierFormat::BluezJson,
            true,
            false,
        );
        let emitted = emitted_device(&t.to_client_event(scan_result(REAL)));
        assert_eq!(t.to_real(&emitted), REAL);
        t.evict(REAL);
        assert_eq!(t.to_real(&emitted), emitted);
    }

    #[test]
    fn reverse_op_translation() {
        let t = configured(
            Some(IdentifierFormat::Uuid),
            IdentifierFormat::BluezJson,
            true,
            false,
        );
        let emitted = emitted_device(&t.to_client_event(scan_result(REAL)));
        let op = t.to_real_op(Op::Connect {
            device: DeviceHandle {
                value: emitted.clone(),
            },
        });
        match op {
            Op::Connect { device } => assert_eq!(device.value, REAL),
            _ => panic!("wrong op"),
        }
    }
}
