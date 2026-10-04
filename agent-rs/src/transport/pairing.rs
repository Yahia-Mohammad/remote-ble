//! The agent pairing URI (#39, `docs/agent-conformance-spec.md` §3.2): where the agent listens,
//! its bearer token and the fingerprint to pin, as one value a client takes in by QR code or paste.
//! Not BLE pairing, which bonds a peripheral. Written exactly as the Kotlin `AgentPairing.toUri`
//! writes it, so the two agents print the same URI for the same pairing.
//!
//! ```text
//! remoteble://<host>:<port>?token=<token>&fp=sha256:<hex>
//! ```

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, UdpSocket};

/// The pairing URI. An IPv6 host is bracketed; the token is percent-encoded per RFC 3986, every
/// byte outside the unreserved set; either parameter is left out when absent.
pub fn pairing_uri(
    host: IpAddr,
    port: u16,
    token: Option<&str>,
    fingerprint: Option<&str>,
) -> String {
    let authority = match host {
        IpAddr::V4(v4) => format!("{v4}:{port}"),
        IpAddr::V6(v6) => format!("[{v6}]:{port}"),
    };
    let query: Vec<String> = [
        token.map(|token| format!("token={}", percent_encode(token))),
        fingerprint.map(|fingerprint| format!("fp={fingerprint}")),
    ]
    .into_iter()
    .flatten()
    .collect();
    if query.is_empty() {
        format!("remoteble://{authority}")
    } else {
        format!("remoteble://{authority}?{}", query.join("&"))
    }
}

/// The pairings `--print-pairing` prints, each with the principal it is for: one per credential,
/// the bare `REMOTE_BLE_TOKEN` (principal `None`, stored as "default") first when `bare_token` says
/// it was given, then the named ones in order; or one without a token for an agent that needs none.
/// Matches the Kotlin agent's `ClientCredentials.pairings`.
pub fn pairing_uris(
    host: IpAddr,
    port: u16,
    fingerprint: Option<&str>,
    credentials: &HashMap<String, String>,
    bare_token: bool,
) -> Vec<(Option<String>, String)> {
    if credentials.is_empty() {
        return vec![(None, pairing_uri(host, port, None, fingerprint))];
    }
    let mut named: Vec<(&String, &String)> = credentials
        .iter()
        .filter(|(name, _)| !(bare_token && name.as_str() == "default"))
        .collect();
    named.sort();
    let bare = bare_token
        .then(|| credentials.get("default"))
        .flatten()
        .map(|secret| (None, pairing_uri(host, port, Some(secret), fingerprint)));
    bare.into_iter()
        .chain(named.into_iter().map(|(name, secret)| {
            (
                Some(name.clone()),
                pairing_uri(host, port, Some(secret), fingerprint),
            )
        }))
        .collect()
}

/// The address a client should use: the bound one, or for a wildcard bind the address of the
/// interface carrying the default route, which connecting a UDP socket picks without sending
/// anything. `None` for a wildcard bind with no route: only `--bind <address>` can say then.
/// Matches the Kotlin agent's `pairingHost`.
pub fn pairing_host(bind: IpAddr, routed: impl FnOnce() -> Option<IpAddr>) -> Option<IpAddr> {
    if bind.is_unspecified() {
        routed()
    } else {
        Some(bind)
    }
}

/// The IPv4 address of the interface carrying the default route, if there is one.
pub fn routed_ipv4() -> Option<IpAddr> {
    let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    // TEST-NET-1 (RFC 5737): never answered, and UDP connect sends no packet anyway.
    socket.connect((Ipv4Addr::new(192, 0, 2, 1), 9)).ok()?;
    let local = socket.local_addr().ok()?.ip();
    (!local.is_unspecified()).then_some(local)
}

fn percent_encode(value: &str) -> String {
    value
        .bytes()
        .map(|byte| {
            if byte.is_ascii_alphanumeric() || b"-._~".contains(&byte) {
                (byte as char).to_string()
            } else {
                format!("%{byte:02X}")
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    const FP: &str = "sha256:358407d7ae647b06f91f3a3d3db1ab7879ef416491e260e2b878dbb8183e6ba7";

    #[test]
    fn the_shared_example_is_written_exactly_as_the_kotlin_side_parses_it() {
        // The same string as `AgentPairingTest.SHARED_EXAMPLE` in `:protocol`.
        assert_eq!(
            pairing_uri(
                "192.168.1.20".parse().unwrap(),
                8080,
                Some("a+b&c=d%e f/é"),
                Some(FP)
            ),
            "remoteble://192.168.1.20:8080?token=a%2Bb%26c%3Dd%25e%20f%2F%C3%A9\
             &fp=sha256:358407d7ae647b06f91f3a3d3db1ab7879ef416491e260e2b878dbb8183e6ba7"
        );
    }

    #[test]
    fn ipv6_hosts_are_bracketed_and_absent_parameters_left_out() {
        assert_eq!(
            pairing_uri("fe80::1c2d".parse().unwrap(), 8080, None, Some(FP)),
            format!("remoteble://[fe80::1c2d]:8080?fp={FP}")
        );
        assert_eq!(
            pairing_uri("127.0.0.1".parse().unwrap(), 8080, None, None),
            "remoteble://127.0.0.1:8080"
        );
    }

    #[test]
    fn one_pairing_per_credential_and_one_without_for_a_token_free_agent() {
        let host: IpAddr = "10.0.0.2".parse().unwrap();
        let credentials = HashMap::from([
            ("default".to_string(), "t0ken".to_string()),
            ("zed".to_string(), "z-secret".to_string()),
            ("amy".to_string(), "a-secret".to_string()),
        ]);

        let printed = pairing_uris(host, 8080, None, &credentials, true);
        let labels: Vec<_> = printed.iter().map(|(name, _)| name.clone()).collect();
        assert_eq!(
            labels,
            vec![None, Some("amy".to_string()), Some("zed".to_string())]
        );
        assert_eq!(printed[0].1, "remoteble://10.0.0.2:8080?token=t0ken");

        // A named credential that happens to be called "default" is labelled as such.
        let named_default = HashMap::from([("default".to_string(), "d".to_string())]);
        assert_eq!(
            pairing_uris(host, 8080, None, &named_default, false)[0].0,
            Some("default".to_string())
        );

        assert_eq!(
            pairing_uris(host, 8080, None, &HashMap::new(), false),
            vec![(None, "remoteble://10.0.0.2:8080".to_string())]
        );
    }

    #[test]
    fn a_pairing_names_the_bound_address_or_the_routed_one_for_a_wildcard() {
        let routed: IpAddr = "10.9.9.9".parse().unwrap();
        let bound: IpAddr = "192.168.1.20".parse().unwrap();
        assert_eq!(pairing_host(bound, || Some(routed)), Some(bound));
        assert_eq!(
            pairing_host("0.0.0.0".parse().unwrap(), || Some(routed)),
            Some(routed)
        );
        assert_eq!(
            pairing_host("::".parse().unwrap(), || Some(routed)),
            Some(routed)
        );
        assert_eq!(pairing_host("0.0.0.0".parse().unwrap(), || None), None);
    }
}
