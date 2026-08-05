use std::collections::BTreeSet;

use crate::protocol::frame::capabilities;
use crate::protocol::op::IdentifierFormat;
use crate::translate::HandleTranslator;

/// The fields of one `ClientHello`, as destructured from the frame.
pub struct HelloRequest {
    pub min_version: i32,
    pub max_version: i32,
    pub wanted: BTreeSet<String>,
    pub identifier_format: Option<IdentifierFormat>,
}

/// The result of selecting one implementation version from a peer's advertised range. Kept
/// separate from capability negotiation so invalid/no-overlap hellos cannot accidentally become a
/// successful v1 handshake.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProtocolVersionSelection {
    Selected(i32),
    InvalidRange,
    NoCompatibleVersion,
}

pub fn select_protocol_version(min_version: i32, max_version: i32) -> ProtocolVersionSelection {
    if min_version > max_version {
        ProtocolVersionSelection::InvalidRange
    } else if (min_version..=max_version).contains(&crate::protocol::frame::PROTOCOL_VERSION) {
        ProtocolVersionSelection::Selected(crate::protocol::frame::PROTOCOL_VERSION)
    } else {
        ProtocolVersionSelection::NoCompatibleVersion
    }
}

/// Per-connection handshake state (spec §5.3): negotiation is **fixed by the first
/// `ClientHello`** — first hello wins. A repeated hello is answered idempotently with the first
/// negotiation and never renegotiates: a changed set could un-gate event types the client's
/// decode loop no longer expects. A reconnect is a new connection (and a fresh [Negotiation]),
/// so it negotiates afresh. Mirrors the Kotlin agent's `BleAgent.respondHello`.
#[derive(Default)]
pub struct Negotiation {
    negotiated: Option<BTreeSet<String>>,
}

impl Negotiation {
    pub fn new() -> Self {
        Self::default()
    }

    /// The capability set a `ServerHello` answers with — the first hello's negotiation, or the
    /// empty v1 baseline before any hello.
    fn capabilities(&self) -> BTreeSet<String> {
        self.negotiated.clone().unwrap_or_default()
    }

    /// Handles one `ClientHello`, returning the capability set for the `ServerHello` reply.
    ///
    /// On the **first** hello: negotiates `clientWanted ∩ agentSupported` — [supported] is what
    /// the backend actually implements, so a client never negotiates a capability the agent
    /// would then answer `UNSUPPORTED`, plus [capabilities::AGENT_CAPABILITIES], which are
    /// radio-independent and so unconditional — then configures [translator] **exactly once** and primes it with
    /// the client's still-warm lease handles so a reconciling client's replayed translated
    /// handles route (see the identifier-translation proposal §"Reconnect & reconcile"). The
    /// [supported] and [warm_leases] providers are invoked only on this path.
    ///
    /// On a **repeated** hello: logs what the client asked for (the divergence being silently
    /// ignored is exactly what an operator needs when debugging that client) and restates the
    /// first negotiation. Nothing is reconfigured.
    pub fn on_hello(
        &mut self,
        hello: HelloRequest,
        translator: &HandleTranslator,
        supported: impl FnOnce() -> Vec<String>,
        warm_leases: impl FnOnce() -> Vec<String>,
    ) -> BTreeSet<String> {
        if self.negotiated.is_none() {
            let mut agent_supported: BTreeSet<String> = supported().into_iter().collect();
            // Agent-level capabilities are radio-independent, so they are added here rather than
            // asked of the backend: [supported] describes what this host's radio can do, and no
            // answer it could give should be able to withhold one of these.
            agent_supported.extend(
                capabilities::AGENT_CAPABILITIES
                    .iter()
                    .map(|capability| capability.to_string()),
            );
            let caps: BTreeSet<String> = hello
                .wanted
                .intersection(&agent_supported)
                .cloned()
                .collect();
            translator.configure(
                hello.identifier_format,
                caps.contains(capabilities::IDENTIFIER_TRANSLATION),
            );
            translator.prime(&warm_leases());
            self.negotiated = Some(caps);
        } else {
            tracing::info!(
                "repeated hello ignored (negotiation fixed): client asked \
                 v{}..{}, capabilities={:?}, identifier_format={:?}",
                hello.min_version,
                hello.max_version,
                hello.wanted,
                hello.identifier_format,
            );
        }
        self.capabilities()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::events::{AdvertisementDto, AgentEvent};
    use crate::protocol::op::DeviceHandle;
    use crate::translate::synthesize;
    use std::sync::Arc;
    use std::sync::atomic::AtomicBool;

    const REAL: &str = "some-native-radio-handle-1234";

    fn translator() -> HandleTranslator {
        // BluezJson agent + Uuid client is a genuine format mismatch, so translation engages.
        HandleTranslator::new(
            IdentifierFormat::BluezJson,
            Arc::new(AtomicBool::new(false)),
        )
    }

    fn hello(wanted: &[&str], format: Option<IdentifierFormat>) -> HelloRequest {
        HelloRequest {
            min_version: 1,
            max_version: 1,
            wanted: wanted.iter().map(|s| s.to_string()).collect(),
            identifier_format: format,
        }
    }

    #[test]
    fn protocol_version_selection_rejects_invalid_and_incompatible_ranges() {
        assert_eq!(
            select_protocol_version(1, 1),
            ProtocolVersionSelection::Selected(1)
        );
        assert_eq!(
            select_protocol_version(2, 1),
            ProtocolVersionSelection::InvalidRange
        );
        assert_eq!(
            select_protocol_version(2, 3),
            ProtocolVersionSelection::NoCompatibleVersion
        );
    }

    #[test]
    fn version_01_shared_conformance_vectors() {
        // Kept under the Kotlin JVM test resources so both adapters consume exactly one fixture.
        let vectors = include_str!(
            "../../../client-sdk/src/jvmTest/resources/conformance/0.9.1-version-vectors.txt"
        );
        for line in vectors
            .lines()
            .filter(|line| !line.is_empty() && !line.starts_with('#'))
        {
            let fields: Vec<_> = line.split('|').collect();
            assert_eq!(fields.len(), 4, "malformed vector: {line}");
            assert_eq!(fields[0], "VERSION-01");
            let minimum = fields[1].parse::<i32>().unwrap();
            let maximum = fields[2].parse::<i32>().unwrap();
            let actual = match select_protocol_version(minimum, maximum) {
                ProtocolVersionSelection::Selected(version) => format!("selected:{version}"),
                ProtocolVersionSelection::InvalidRange => "invalid-range".to_owned(),
                ProtocolVersionSelection::NoCompatibleVersion => "no-compatible-version".to_owned(),
            };
            assert_eq!(fields[3], actual, "{line}");
        }
    }

    fn caps(names: &[&str]) -> BTreeSet<String> {
        names.iter().map(|s| s.to_string()).collect()
    }

    fn translation_active(t: &HandleTranslator) -> bool {
        let event = t.to_client_event(AgentEvent::ScanResult {
            scan_id: 1,
            advertisement: AdvertisementDto {
                device: DeviceHandle {
                    value: REAL.to_string(),
                },
                name: None,
                rssi: -50,
                service_uuids: vec![],
                manufacturer_data: Default::default(),
            },
        });
        match event {
            AgentEvent::ScanResult { advertisement, .. } => advertisement.device.value != REAL,
            _ => unreachable!(),
        }
    }

    #[test]
    fn first_hello_negotiates_the_intersection_and_enables_translation() {
        let t = translator();
        let mut neg = Negotiation::new();
        let negotiated = neg.on_hello(
            hello(
                &[capabilities::IDENTIFIER_TRANSLATION, "descriptors", "rssi"],
                Some(IdentifierFormat::Uuid),
            ),
            &t,
            || vec!["descriptors".to_string(), "pairing".to_string()],
            Vec::new,
        );
        // wanted ∩ (backend + agent-level identifier.translate): rssi unsupported, pairing unwanted.
        assert_eq!(
            negotiated,
            caps(&[capabilities::IDENTIFIER_TRANSLATION, "descriptors"])
        );
        assert!(translation_active(&t));
    }

    #[test]
    fn first_hello_primes_the_translator_from_warm_leases() {
        let t = translator();
        let mut neg = Negotiation::new();
        neg.on_hello(
            hello(
                &[capabilities::IDENTIFIER_TRANSLATION],
                Some(IdentifierFormat::Uuid),
            ),
            &t,
            Vec::new,
            || vec![REAL.to_string()],
        );
        // The mapping exists before any event has carried the handle: a reconciling client's
        // replayed (previously-issued, synthesized) handle routes to the real device.
        let issued = synthesize(IdentifierFormat::Uuid, REAL);
        assert_eq!(t.to_real(&issued), REAL);
    }

    #[test]
    fn repeated_hello_restates_the_first_negotiation_and_reconfigures_nothing() {
        let t = translator();
        let mut neg = Negotiation::new();
        let first = neg.on_hello(
            hello(
                &[capabilities::IDENTIFIER_TRANSLATION],
                Some(IdentifierFormat::Uuid),
            ),
            &t,
            Vec::new,
            Vec::new,
        );
        assert!(translation_active(&t));

        // A divergent second hello (no capabilities, no format): answered with the FIRST
        // negotiation; had it renegotiated, configure(None, false) would disable translation.
        // The providers must not even be consulted.
        let second = neg.on_hello(
            hello(&[], None),
            &t,
            || panic!("supported() must not run on a repeated hello"),
            || panic!("warm_leases() must not run on a repeated hello"),
        );
        assert_eq!(second, first);
        assert!(translation_active(&t), "translator was reconfigured");
    }

    #[test]
    fn agent_level_capabilities_are_offered_whatever_the_backend_reports() {
        let t = translator();
        let mut neg = Negotiation::new();
        let wanted: Vec<&str> = capabilities::AGENT_CAPABILITIES.to_vec();
        let negotiated = neg.on_hello(
            hello(&wanted, Some(IdentifierFormat::Uuid)),
            &t,
            // A backend that implements nothing. Agent-level capabilities are radio-independent,
            // so this must not narrow them by even one — the rule that keeps two agents on the
            // same host from answering a client differently.
            Vec::new,
            Vec::new,
        );
        assert_eq!(negotiated, caps(&wanted));
    }

    #[test]
    fn before_any_hello_the_baseline_is_empty() {
        assert!(Negotiation::new().capabilities().is_empty());
    }
}
