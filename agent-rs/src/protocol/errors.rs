use serde::{Deserialize, Serialize};
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorKind {
    ConnectionFailed,
    Disconnected,
    GattError,
    ReadFailed,
    WriteFailed,
    CharacteristicNotFound,
    NotConnected,
    UnknownDevice,
    NoConnectionSlot,
    PeripheralBusy,
    AgentBusy,
    ScanUnavailable,
    InvalidRequest,
    Unsupported,
    Timeout,
    TransportLost,
    PolicyDenied,
}

/// Who holds a leased peripheral, on `PERIPHERAL_BUSY`. Mirrors `LeaseHolder` in the Kotlin
/// protocol; `client_id` is `None` where the caller is not entitled to see it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LeaseHolder {
    pub principal: String,
    #[serde(rename = "clientId", skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AgentError {
    pub kind: ErrorKind,
    #[serde(rename = "gattStatus", skip_serializing_if = "Option::is_none")]
    pub gatt_status: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
    /// Populated only for a client that negotiated `lease.holder` — an unknown key would fail a
    /// v1 client's decode outright, so this is skipped rather than sent as null. `skip_serializing_if`
    /// mirrors Kotlin's `encodeDefaults = false` field by field, which is what makes "diff the two
    /// agents' replies" a real check.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub holder: Option<LeaseHolder>,
}

impl AgentError {
    pub fn new(kind: ErrorKind, message: Option<String>) -> Self {
        Self {
            kind,
            gatt_status: None,
            message,
            holder: None,
        }
    }

    /// A `PERIPHERAL_BUSY` error naming the holder, for a client that negotiated `lease.holder`.
    pub fn peripheral_busy(message: String, holder: Option<LeaseHolder>) -> Self {
        Self {
            kind: ErrorKind::PeripheralBusy,
            gatt_status: None,
            message: Some(message),
            holder,
        }
    }

    /// Construct an error carrying the raw GATT status from the radio. Used by
    /// backends that can surface a stack-level status code (none of the current
    /// btleplug paths expose one, hence currently unused).
    #[allow(dead_code)]
    pub fn with_gatt(kind: ErrorKind, gatt_status: i32, message: Option<String>) -> Self {
        Self {
            kind,
            gatt_status: Some(gatt_status),
            message,
            holder: None,
        }
    }
}

impl fmt::Display for AgentError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if let Some(msg) = &self.message {
            write!(f, "{:?}: {}", self.kind, msg)
        } else {
            write!(f, "{:?}", self.kind)
        }
    }
}

impl std::error::Error for AgentError {}
