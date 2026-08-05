//! The `agent.status` reply DTOs — the Rust mirror of Kotlin's `AgentStatus.kt`.
//!
//! Disclosure is scoped to the caller, decided by [`crate::registry::lease_disclosure`]: an ordinary
//! caller sees the leases its own session key holds plus [`AgentStatusDto::other_leases`], and a
//! caller that presented operator scope on the upgrade sees every lease and its holder.
//!
//! ### Why the skip conditions are not decoration
//! The Kotlin codec is `Cbor.Default`, which has `encodeDefaults = false` and so omits any field
//! equal to its declared default. Every `skip_serializing_if` below mirrors one of those defaults,
//! so the two reference agents emit the **same bytes** for the same state rather than merely two
//! forms a decoder happens to accept. A client diffing one agent's status against the other's is a
//! check we want to be meaningful.

use serde::{Deserialize, Serialize};

use super::frame::PROTOCOL_VERSION;

fn is_false(value: &bool) -> bool {
    !*value
}

fn is_zero(value: &i32) -> bool {
    *value == 0
}

fn is_current_protocol_version(value: &i32) -> bool {
    *value == PROTOCOL_VERSION
}

fn default_protocol_version() -> i32 {
    PROTOCOL_VERSION
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentStatusDto {
    /// The same human-readable engine/platform label `ServerHello` carries.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_info: Option<String>,
    #[serde(
        default = "default_protocol_version",
        skip_serializing_if = "is_current_protocol_version"
    )]
    pub protocol_version: i32,
    /// Milliseconds since this agent process began serving.
    pub uptime_ms: i64,
    pub settings: StatusSettingsDto,
    pub slots: StatusSlotsDto,
    pub connected_clients: i32,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub leases: Vec<LeaseStatusDto>,
    /// Leases held by someone else and so absent from `leases`. Always 0 under operator scope.
    #[serde(default, skip_serializing_if = "is_zero")]
    pub other_leases: i32,
    #[serde(default, skip_serializing_if = "is_false")]
    pub operator_scope: bool,
}

/// The ownership configuration this process is actually running with, not its defaults.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StatusSettingsDto {
    pub lease_grace_ms: i64,
    pub transport_grace_ms: i64,
    pub exclusive_by_default: bool,
    /// The scan-isolation policy, as its lowercased mode name.
    pub scan_concurrency: String,
    pub strict_identifiers: bool,
    /// Whether a per-principal write policy is configured and enforced by this agent.
    #[serde(default, skip_serializing_if = "is_false")]
    pub write_policy_enforced: bool,
}

/// Host slot occupancy: agent-global and lease-aware, the same accounting `SlotState` reports.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StatusSlotsDto {
    pub free: i32,
    pub total: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LeaseStatusDto {
    /// In the caller's own identifier format, so it is routable in the caller's next op.
    pub handle: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    /// The holder under the disclosure policy; None when the caller may not see it at all.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub holder: Option<String>,
    #[serde(default, skip_serializing_if = "is_false")]
    pub mine: bool,
    pub connected: bool,
    pub in_grace: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub remaining_grace_ms: Option<i64>,
}
