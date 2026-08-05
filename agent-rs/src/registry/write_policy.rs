//! Per-principal write allowlist (U7) — the only *control* on writes, since CLI-side policy is
//! advisory by construction: it lives in a file the calling agent can edit, so anything it refuses
//! can still be done by a second client on the same token. Only a check made here, keyed to the
//! authenticating principal, holds regardless of which client shows up.
//!
//! Mirrors `WritePolicy.kt` in the Kotlin agent field for field; see
//! `docs/proposals/agent-write-policy.md` for the full design rationale.
//!
//! ### Three states, not two
//! - **Not configured** ([`WritePolicy::permissive`]) — every write allowed.
//! - **Configured, principal unlisted** — every write denied.
//! - **Configured, principal listed with an empty rule list** — every write denied, reached
//!   without a special case: an absent or empty rule set both fail every `iter().any()` match.
//!
//! ### Matching
//! Exact, case-insensitive string equality against the wire form of `CharRef`/`DescRef` — the
//! full 128-bit UUID, never the short form — or the wildcard `"*"`. `instance` is deliberately not
//! part of matching. `maximum_bytes: None` is unlimited; `with_response: None` matches either type.

use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};

pub const CURRENT_SCHEMA_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WritePolicyFile {
    pub version: u32,
    #[serde(default)]
    pub principals: HashMap<String, PrincipalPolicy>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct PrincipalPolicy {
    #[serde(default)]
    pub writes: Vec<WriteRule>,
    #[serde(default, rename = "descriptorWrites")]
    pub descriptor_writes: Vec<DescriptorWriteRule>,
    #[serde(default)]
    pub pairing: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct WriteRule {
    pub service: String,
    pub characteristic: String,
    #[serde(default, rename = "maximumBytes")]
    pub maximum_bytes: Option<i32>,
    #[serde(default, rename = "withResponse")]
    pub with_response: Option<bool>,
}

impl WriteRule {
    fn matches_char(&self, service: &str, characteristic: &str) -> bool {
        matches_field(&self.service, service) && matches_field(&self.characteristic, characteristic)
    }

    fn permits(&self, size: usize, with_response: bool) -> bool {
        self.maximum_bytes
            .is_none_or(|max| max >= 0 && size <= max as usize)
            && self
                .with_response
                .is_none_or(|expected| expected == with_response)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct DescriptorWriteRule {
    pub service: String,
    pub characteristic: String,
    pub descriptor: String,
    #[serde(default, rename = "maximumBytes")]
    pub maximum_bytes: Option<i32>,
}

impl DescriptorWriteRule {
    fn matches_desc(&self, service: &str, characteristic: &str, descriptor: &str) -> bool {
        matches_field(&self.service, service)
            && matches_field(&self.characteristic, characteristic)
            && matches_field(&self.descriptor, descriptor)
    }
}

fn matches_field(rule: &str, actual: &str) -> bool {
    rule == "*" || rule.eq_ignore_ascii_case(actual)
}

/// The principal half of a session key (`principal\0client_id`) — the whole string if it carries
/// no separator, which is what an unauthenticated connection's bare client id looks like.
///
/// Local to this module rather than shared with `lease_disclosure`'s own private `split`: neither
/// Rust module centralizes NUL-splitting today (`server.rs`'s `session_key`/`split` don't either),
/// so this doesn't need to invent that centralization just for policy lookups.
pub(crate) fn principal_of(session_key: &str) -> &str {
    session_key.split('\0').next().unwrap_or(session_key)
}

#[cfg(test)]
mod principal_of_tests {
    use super::principal_of;

    #[test]
    fn splits_on_the_session_key_separator() {
        assert_eq!(principal_of("lab-a\0shell-1"), "lab-a");
    }

    #[test]
    fn returns_the_whole_string_when_there_is_no_separator() {
        assert_eq!(principal_of("anon-42"), "anon-42");
    }
}

#[derive(Debug, Clone)]
pub struct WritePolicy {
    principals: Option<HashMap<String, PrincipalPolicy>>,
}

impl WritePolicy {
    /// No policy configured: every write is allowed, matching pre-U7 behaviour.
    pub fn permissive() -> Self {
        Self { principals: None }
    }

    /// Whether this agent enforces a policy at all — surfaced in `agent.status`.
    pub fn enforced(&self) -> bool {
        self.principals.is_some()
    }

    pub fn authorizes_write(
        &self,
        principal: &str,
        service: &str,
        characteristic: &str,
        size: usize,
        with_response: bool,
    ) -> bool {
        let Some(principals) = &self.principals else {
            return true;
        };
        principals.get(principal).is_some_and(|policy| {
            policy.writes.iter().any(|rule| {
                rule.matches_char(service, characteristic) && rule.permits(size, with_response)
            })
        })
    }

    pub fn authorizes_descriptor_write(
        &self,
        principal: &str,
        service: &str,
        characteristic: &str,
        descriptor: &str,
        size: usize,
    ) -> bool {
        let Some(principals) = &self.principals else {
            return true;
        };
        principals.get(principal).is_some_and(|policy| {
            policy.descriptor_writes.iter().any(|rule| {
                rule.matches_desc(service, characteristic, descriptor)
                    && rule
                        .maximum_bytes
                        .is_none_or(|max| max >= 0 && size <= max as usize)
            })
        })
    }

    pub fn authorizes_pairing(&self, principal: &str) -> bool {
        let Some(principals) = &self.principals else {
            return true;
        };
        principals
            .get(principal)
            .is_some_and(|policy| policy.pairing)
    }

    /// Decodes and validates a policy file before the agent starts listening — the operator-token
    /// distinctness check in `main.rs` uses the same "fail hard before the socket opens" posture.
    ///
    /// `known_principals` is the actual set of principals this agent process was configured with;
    /// a policy naming anyone outside it is almost certainly a typo, and starting up with a rule
    /// nobody will ever match is exactly the failure mode a security feature must not tolerate.
    pub fn decode(raw: &str, known_principals: &HashSet<String>) -> Result<Self, String> {
        let file: WritePolicyFile =
            serde_json::from_str(raw).map_err(|e| format!("invalid write policy: {e}"))?;
        if file.version != CURRENT_SCHEMA_VERSION {
            return Err(format!(
                "unsupported write policy version {}; expected {CURRENT_SCHEMA_VERSION}",
                file.version
            ));
        }
        let mut unknown: Vec<&String> = file
            .principals
            .keys()
            .filter(|name| !known_principals.contains(*name))
            .collect();
        if !unknown.is_empty() {
            unknown.sort();
            let names = unknown
                .iter()
                .map(|s| s.as_str())
                .collect::<Vec<_>>()
                .join(", ");
            return Err(format!("write policy names unknown principal(s): {names}"));
        }
        for (principal, policy) in &file.principals {
            for (index, rule) in policy.writes.iter().enumerate() {
                validate_maximum_bytes(
                    rule.maximum_bytes,
                    &format!("{principal}.writes[{index}]"),
                )?;
            }
            for (index, rule) in policy.descriptor_writes.iter().enumerate() {
                validate_maximum_bytes(
                    rule.maximum_bytes,
                    &format!("{principal}.descriptorWrites[{index}]"),
                )?;
            }
        }
        Ok(Self {
            principals: Some(file.principals),
        })
    }
}

fn validate_maximum_bytes(maximum_bytes: Option<i32>, rule: &str) -> Result<(), String> {
    if maximum_bytes.is_some_and(|max| max < 0) {
        Err(format!(
            "write policy {rule} maximumBytes must be non-negative"
        ))
    } else {
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HEART_RATE: &str = "0000180d-0000-1000-8000-00805f9b34fb";
    const CONTROL_POINT: &str = "00002a39-0000-1000-8000-00805f9b34fb";
    const BATTERY: &str = "0000180f-0000-1000-8000-00805f9b34fb";
    const CCCD: &str = "00002902-0000-1000-8000-00805f9b34fb";
    const USER_DESCRIPTION: &str = "00002901-0000-1000-8000-00805f9b34fb";

    fn known(names: &[&str]) -> HashSet<String> {
        names.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn permissive_allows_every_write_and_is_not_enforced() {
        let policy = WritePolicy::permissive();
        assert!(!policy.enforced());
        assert!(policy.authorizes_write("anyone", HEART_RATE, CONTROL_POINT, 1_000_000, false));
        assert!(policy.authorizes_descriptor_write(
            "anyone",
            HEART_RATE,
            CONTROL_POINT,
            CCCD,
            1_000_000
        ));
        assert!(policy.authorizes_pairing("anyone"));
    }

    #[test]
    fn unlisted_principal_is_denied_once_a_policy_is_configured() {
        let policy = WritePolicy::decode(
            r#"{"version":1,"principals":{"lab-a":{"writes":[]}}}"#,
            &known(&["lab-a", "lab-b"]),
        )
        .unwrap();
        assert!(policy.enforced());
        assert!(!policy.authorizes_write("lab-b", HEART_RATE, CONTROL_POINT, 1, true));
    }

    #[test]
    fn empty_rule_list_denies_exactly_like_an_unlisted_principal() {
        let policy = WritePolicy::decode(
            r#"{"version":1,"principals":{"lab-a":{"writes":[]}}}"#,
            &known(&["lab-a"]),
        )
        .unwrap();
        assert!(!policy.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 1, true));
    }

    #[test]
    fn matching_rule_allows_within_its_bounds() {
        let raw = format!(
            r#"{{"version":1,"principals":{{"lab-a":{{"writes":[
                {{"service":"{HEART_RATE}","characteristic":"{CONTROL_POINT}","maximumBytes":1,"withResponse":true}}
            ]}}}}}}"#
        );
        let policy = WritePolicy::decode(&raw, &known(&["lab-a"])).unwrap();
        assert!(policy.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 1, true));
        assert!(!policy.authorizes_write("lab-a", HEART_RATE, BATTERY, 1, true));
        assert!(!policy.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 2, true));
        assert!(!policy.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 1, false));
    }

    #[test]
    fn matching_is_case_insensitive() {
        let raw = format!(
            r#"{{"version":1,"principals":{{"lab-a":{{"writes":[
                {{"service":"{}","characteristic":"{}"}}
            ]}}}}}}"#,
            HEART_RATE.to_uppercase(),
            CONTROL_POINT.to_uppercase()
        );
        let policy = WritePolicy::decode(&raw, &known(&["lab-a"])).unwrap();
        assert!(policy.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 1, true));
    }

    #[test]
    fn wildcard_matches_any_characteristic_on_any_service() {
        let policy = WritePolicy::decode(
            r#"{"version":1,"principals":{"ci":{"writes":[{"service":"*","characteristic":"*","maximumBytes":20}]}}}"#,
            &known(&["ci"]),
        )
        .unwrap();
        assert!(policy.authorizes_write("ci", HEART_RATE, CONTROL_POINT, 20, false));
        assert!(policy.authorizes_write("ci", BATTERY, "anything-at-all", 20, true));
        assert!(!policy.authorizes_write("ci", BATTERY, "anything-at-all", 21, true));
    }

    #[test]
    fn null_maximum_bytes_is_unlimited_and_null_with_response_matches_either() {
        let raw = format!(
            r#"{{"version":1,"principals":{{"lab-a":{{"writes":[{{"service":"{HEART_RATE}","characteristic":"{CONTROL_POINT}","maximumBytes":null,"withResponse":null}}]}}}}}}"#
        );
        let policy = WritePolicy::decode(&raw, &known(&["lab-a"])).unwrap();
        assert!(policy.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 10_000, true));
        assert!(policy.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 10_000, false));
    }

    #[test]
    fn descriptor_writes_are_matched_independently_of_characteristic_writes() {
        let raw = format!(
            r#"{{"version":1,"principals":{{"lab-a":{{
                "writes":[],
                "descriptorWrites":[{{"service":"{HEART_RATE}","characteristic":"{CONTROL_POINT}","descriptor":"{CCCD}","maximumBytes":2}}]
            }}}}}}"#
        );
        let policy = WritePolicy::decode(&raw, &known(&["lab-a"])).unwrap();
        assert!(!policy.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 1, true));
        assert!(policy.authorizes_descriptor_write("lab-a", HEART_RATE, CONTROL_POINT, CCCD, 2));
        assert!(!policy.authorizes_descriptor_write("lab-a", HEART_RATE, CONTROL_POINT, CCCD, 3));
        assert!(!policy.authorizes_descriptor_write(
            "lab-a",
            HEART_RATE,
            CONTROL_POINT,
            USER_DESCRIPTION,
            1
        ));
    }

    #[test]
    fn descriptor_wildcard_is_explicit_and_matches_only_when_configured() {
        let raw = format!(
            r#"{{"version":1,"principals":{{"lab-a":{{"descriptorWrites":[
                {{"service":"{HEART_RATE}","characteristic":"{CONTROL_POINT}","descriptor":"*"}}
            ]}}}}}}"#
        );
        let policy = WritePolicy::decode(&raw, &known(&["lab-a"])).unwrap();
        assert!(policy.authorizes_descriptor_write("lab-a", HEART_RATE, CONTROL_POINT, CCCD, 1));
        assert!(policy.authorizes_descriptor_write(
            "lab-a",
            HEART_RATE,
            CONTROL_POINT,
            USER_DESCRIPTION,
            1
        ));
    }

    #[test]
    fn maximum_bytes_uses_a_signed_nonnegative_32_bit_domain() {
        let raw = format!(
            r#"{{"version":1,"principals":{{"lab-a":{{"writes":[
                {{"service":"{HEART_RATE}","characteristic":"{CONTROL_POINT}","maximumBytes":0}}
            ]}}}}}}"#
        );
        let zero = WritePolicy::decode(&raw, &known(&["lab-a"])).unwrap();
        assert!(zero.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 0, true));
        assert!(!zero.authorizes_write("lab-a", HEART_RATE, CONTROL_POINT, 1, true));
        for invalid in [
            r#"{"version":1,"principals":{"lab-a":{"writes":[{"service":"*","characteristic":"*","maximumBytes":-1}]}}}"#,
            r#"{"version":1,"principals":{"lab-a":{"descriptorWrites":[{"service":"*","characteristic":"*","descriptor":"*","maximumBytes":-1}]}}}"#,
            r#"{"version":1,"principals":{"lab-a":{"writes":[{"service":"*","characteristic":"*","maximumBytes":2147483648}]}}}"#,
        ] {
            assert!(WritePolicy::decode(invalid, &known(&["lab-a"])).is_err());
        }
    }

    #[test]
    fn unknown_fields_are_rejected_at_every_schema_level() {
        for invalid in [
            r#"{"version":1,"principals":{},"unexpected":true}"#,
            r#"{"version":1,"principals":{"lab-a":{"unexpected":true}}}"#,
            r#"{"version":1,"principals":{"lab-a":{"writes":[{"service":"*","characteristic":"*","maximumByte":1}]}}}"#,
            r#"{"version":1,"principals":{"lab-a":{"descriptorWrites":[{"service":"*","characteristic":"*","descriptor":"*","maximumByte":1}]}}}"#,
        ] {
            assert!(WritePolicy::decode(invalid, &known(&["lab-a"])).is_err());
        }
    }

    #[test]
    fn pairing_defaults_to_denied_for_a_listed_principal() {
        let policy = WritePolicy::decode(
            r#"{"version":1,"principals":{"lab-a":{"writes":[]}}}"#,
            &known(&["lab-a"]),
        )
        .unwrap();
        assert!(!policy.authorizes_pairing("lab-a"));
    }

    #[test]
    fn pairing_can_be_explicitly_granted() {
        let policy = WritePolicy::decode(
            r#"{"version":1,"principals":{"lab-a":{"writes":[],"pairing":true}}}"#,
            &known(&["lab-a"]),
        )
        .unwrap();
        assert!(policy.authorizes_pairing("lab-a"));
    }

    #[test]
    fn malformed_json_fails_to_decode() {
        assert!(WritePolicy::decode("not json", &known(&["lab-a"])).is_err());
    }

    #[test]
    fn unsupported_version_fails_to_decode() {
        assert!(
            WritePolicy::decode(r#"{"version":2,"principals":{}}"#, &known(&["lab-a"])).is_err()
        );
    }

    #[test]
    fn a_principal_not_among_the_agents_configured_credentials_fails_to_decode() {
        // A typo'd principal in the policy file is exactly the misconfiguration a security
        // feature must not silently ignore: it must fail startup, not boot with a rule nobody
        // will ever match.
        let result = WritePolicy::decode(
            r#"{"version":1,"principals":{"lab-x":{"writes":[]}}}"#,
            &known(&["lab-a", "lab-b"]),
        );
        assert!(result.is_err());
    }
}
