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
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AgentError {
    pub kind: ErrorKind,
    #[serde(rename = "gattStatus", skip_serializing_if = "Option::is_none")]
    pub gatt_status: Option<i32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub message: Option<String>,
}

impl AgentError {
    pub fn new(kind: ErrorKind, message: Option<String>) -> Self {
        Self {
            kind,
            gatt_status: None,
            message,
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
