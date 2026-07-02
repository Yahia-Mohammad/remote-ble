use super::errors::AgentError;
use super::op::DeviceHandle;
use super::results::BleBondState;
use serde::de::{self, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use std::collections::BTreeMap;
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BleConnState {
    Connecting,
    Connected,
    Disconnecting,
    Disconnected,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AdvertisementDto {
    pub device: DeviceHandle,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
    pub rssi: i32,
    #[serde(default, rename = "serviceUuids")]
    pub service_uuids: Vec<String>,
    #[serde(
        default,
        rename = "manufacturerData",
        with = "crate::protocol::bytes::signed_bytes_map"
    )]
    pub manufacturer_data: BTreeMap<i32, Vec<u8>>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AgentEvent {
    ScanResult {
        scan_id: i64,
        advertisement: AdvertisementDto,
    },
    ScanResultBatch {
        scan_id: i64,
        advertisements: Vec<AdvertisementDto>,
    },
    Notification {
        sub_id: i64,
        value: Vec<u8>,
    },
    ConnectionState {
        device: DeviceHandle,
        state: BleConnState,
        reason: Option<AgentError>,
    },
    BondState {
        device: DeviceHandle,
        state: BleBondState,
        reason: Option<AgentError>,
    },
    SlotState {
        free: i32,
        total: i32,
    },
}

#[derive(Serialize, Deserialize)]
struct ScanResultPayload {
    #[serde(rename = "scanId")]
    scan_id: i64,
    advertisement: AdvertisementDto,
}

#[derive(Serialize, Deserialize)]
struct ScanBatchPayload {
    #[serde(rename = "scanId")]
    scan_id: i64,
    advertisements: Vec<AdvertisementDto>,
}

#[derive(Serialize, Deserialize)]
struct NotificationPayload {
    #[serde(rename = "subId")]
    sub_id: i64,
    #[serde(with = "crate::protocol::bytes::signed_bytes")]
    value: Vec<u8>,
}

#[derive(Serialize, Deserialize)]
struct ConnStatePayload {
    device: DeviceHandle,
    state: BleConnState,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<AgentError>,
}

#[derive(Serialize, Deserialize)]
struct BondStatePayload {
    device: DeviceHandle,
    state: BleBondState,
    #[serde(skip_serializing_if = "Option::is_none")]
    reason: Option<AgentError>,
}

#[derive(Serialize, Deserialize)]
struct SlotStatePayload {
    free: i32,
    total: i32,
}

impl Serialize for AgentEvent {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        use serde::ser::SerializeSeq;
        let mut seq = serializer.serialize_seq(Some(2))?;
        match self {
            AgentEvent::ScanResult {
                scan_id,
                advertisement,
            } => {
                seq.serialize_element("scan.result")?;
                seq.serialize_element(&ScanResultPayload {
                    scan_id: *scan_id,
                    advertisement: advertisement.clone(),
                })?;
            }
            AgentEvent::ScanResultBatch {
                scan_id,
                advertisements,
            } => {
                seq.serialize_element("scan.batch")?;
                seq.serialize_element(&ScanBatchPayload {
                    scan_id: *scan_id,
                    advertisements: advertisements.clone(),
                })?;
            }
            AgentEvent::Notification { sub_id, value } => {
                seq.serialize_element("notification")?;
                seq.serialize_element(&NotificationPayload {
                    sub_id: *sub_id,
                    value: value.clone(),
                })?;
            }
            AgentEvent::ConnectionState {
                device,
                state,
                reason,
            } => {
                seq.serialize_element("conn.state")?;
                seq.serialize_element(&ConnStatePayload {
                    device: device.clone(),
                    state: *state,
                    reason: reason.clone(),
                })?;
            }
            AgentEvent::BondState {
                device,
                state,
                reason,
            } => {
                seq.serialize_element("bond.state")?;
                seq.serialize_element(&BondStatePayload {
                    device: device.clone(),
                    state: *state,
                    reason: reason.clone(),
                })?;
            }
            AgentEvent::SlotState { free, total } => {
                seq.serialize_element("conn.slots")?;
                seq.serialize_element(&SlotStatePayload {
                    free: *free,
                    total: *total,
                })?;
            }
        }
        seq.end()
    }
}

impl<'de> Deserialize<'de> for AgentEvent {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct AgentEventVisitor;

        impl<'de> Visitor<'de> for AgentEventVisitor {
            type Value = AgentEvent;

            fn expecting(&self, formatter: &mut fmt::Formatter) -> fmt::Result {
                formatter.write_str("a 2-element sequence [tag, payload]")
            }

            fn visit_seq<A>(self, mut seq: A) -> Result<Self::Value, A::Error>
            where
                A: SeqAccess<'de>,
            {
                let tag: String = seq
                    .next_element()?
                    .ok_or_else(|| de::Error::invalid_length(0, &self))?;

                match tag.as_str() {
                    "scan.result" => {
                        let p: ScanResultPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(AgentEvent::ScanResult {
                            scan_id: p.scan_id,
                            advertisement: p.advertisement,
                        })
                    }
                    "scan.batch" => {
                        let p: ScanBatchPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(AgentEvent::ScanResultBatch {
                            scan_id: p.scan_id,
                            advertisements: p.advertisements,
                        })
                    }
                    "notification" => {
                        let p: NotificationPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(AgentEvent::Notification {
                            sub_id: p.sub_id,
                            value: p.value,
                        })
                    }
                    "conn.state" => {
                        let p: ConnStatePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(AgentEvent::ConnectionState {
                            device: p.device,
                            state: p.state,
                            reason: p.reason,
                        })
                    }
                    "bond.state" => {
                        let p: BondStatePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(AgentEvent::BondState {
                            device: p.device,
                            state: p.state,
                            reason: p.reason,
                        })
                    }
                    "conn.slots" => {
                        let p: SlotStatePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(AgentEvent::SlotState {
                            free: p.free,
                            total: p.total,
                        })
                    }
                    _ => Err(de::Error::unknown_variant(
                        &tag,
                        &[
                            "scan.result",
                            "scan.batch",
                            "notification",
                            "conn.state",
                            "bond.state",
                            "conn.slots",
                        ],
                    )),
                }
            }
        }

        deserializer.deserialize_seq(AgentEventVisitor)
    }
}
