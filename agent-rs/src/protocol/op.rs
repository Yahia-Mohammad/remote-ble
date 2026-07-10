use serde::de::{self, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use std::fmt;

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct DeviceHandle {
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct CharRef {
    pub service: String,
    pub characteristic: String,
    #[serde(default)]
    pub instance: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct DescRef {
    pub service: String,
    pub characteristic: String,
    pub descriptor: String,
    #[serde(default)]
    pub instance: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScanFilter {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub service: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConnPriority {
    Balanced,
    High,
    LowPower,
}

/// Portable primary of `Op::SetConnParams`. Supersedes `ConnPriority`; matches Kotlin's
/// `ConnProfile` ordering/naming exactly for byte-parity.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ConnProfile {
    LowLatency,
    Balanced,
    LowPower,
}

/// Reserved fine-grained hint accompanying a `ConnProfile`. No shipping engine honors this;
/// btleplug advertises no interval/priority control at all, so agent-rs is codec-parity only.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ConnParamHint {
    pub min_interval_ms: f64,
    pub max_interval_ms: f64,
    pub latency: i32,
    pub supervision_timeout_ms: i32,
}

/// The format a client's Kable `Identifier` can hold on its local platform (mirrors Kotlin
/// `IdentifierFormat`). Declared in `ClientHello` so an agent that negotiated
/// `identifier.translate` can mint device handles in the client's native format.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum IdentifierFormat {
    String,
    Uuid,
    MacAddress,
    BluezJson,
}

#[derive(Debug, Clone, PartialEq)]
pub enum Op {
    ScanStart {
        scan_id: i64,
        filters: Vec<ScanFilter>,
    },
    ScanStop {
        scan_id: i64,
    },
    Connect {
        device: DeviceHandle,
    },
    Disconnect {
        device: DeviceHandle,
    },
    Discover {
        device: DeviceHandle,
    },
    Read {
        device: DeviceHandle,
        char: CharRef,
    },
    Write {
        device: DeviceHandle,
        char: CharRef,
        value: Vec<u8>,
        with_response: bool,
    },
    ObserveStart {
        sub_id: i64,
        device: DeviceHandle,
        char: CharRef,
    },
    ObserveStop {
        sub_id: i64,
    },
    RequestMtu {
        device: DeviceHandle,
        mtu: i32,
    },
    ReadDescriptor {
        device: DeviceHandle,
        desc: DescRef,
    },
    WriteDescriptor {
        device: DeviceHandle,
        desc: DescRef,
        value: Vec<u8>,
    },
    Pair {
        device: DeviceHandle,
    },
    Unpair {
        device: DeviceHandle,
    },
    RequestConnectionPriority {
        device: DeviceHandle,
        priority: ConnPriority,
    },
    ReadRssi {
        device: DeviceHandle,
    },
    SetConnParams {
        device: DeviceHandle,
        profile: ConnProfile,
        hint: Option<ConnParamHint>,
    },
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ScanStartPayload {
    #[serde(rename = "scanId")]
    scan_id: i64,
    #[serde(default)]
    filters: Vec<ScanFilter>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ScanStopPayload {
    #[serde(rename = "scanId")]
    scan_id: i64,
}

#[derive(Serialize, Deserialize)]
struct DevicePayload {
    device: DeviceHandle,
}

#[derive(Serialize, Deserialize)]
struct ReadPayload {
    device: DeviceHandle,
    char: CharRef,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct WritePayload {
    device: DeviceHandle,
    char: CharRef,
    #[serde(with = "crate::protocol::bytes::signed_bytes")]
    value: Vec<u8>,
    #[serde(rename = "withResponse")]
    with_response: bool,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ObserveStartPayload {
    #[serde(rename = "subId")]
    sub_id: i64,
    device: DeviceHandle,
    char: CharRef,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ObserveStopPayload {
    #[serde(rename = "subId")]
    sub_id: i64,
}

#[derive(Serialize, Deserialize)]
struct MtuPayload {
    device: DeviceHandle,
    mtu: i32,
}

#[derive(Serialize, Deserialize)]
struct ReadDescPayload {
    device: DeviceHandle,
    desc: DescRef,
}

#[derive(Serialize, Deserialize)]
struct WriteDescPayload {
    device: DeviceHandle,
    desc: DescRef,
    #[serde(with = "crate::protocol::bytes::signed_bytes")]
    value: Vec<u8>,
}

#[derive(Serialize, Deserialize)]
struct ConnPriorityPayload {
    device: DeviceHandle,
    priority: ConnPriority,
}

#[derive(Serialize, Deserialize)]
struct SetConnParamsPayload {
    device: DeviceHandle,
    profile: ConnProfile,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    hint: Option<ConnParamHint>,
}

impl Serialize for Op {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        use serde::ser::SerializeSeq;
        let mut seq = serializer.serialize_seq(Some(2))?;
        match self {
            Op::ScanStart { scan_id, filters } => {
                seq.serialize_element("scan.start")?;
                seq.serialize_element(&ScanStartPayload {
                    scan_id: *scan_id,
                    filters: filters.clone(),
                })?;
            }
            Op::ScanStop { scan_id } => {
                seq.serialize_element("scan.stop")?;
                seq.serialize_element(&ScanStopPayload { scan_id: *scan_id })?;
            }
            Op::Connect { device } => {
                seq.serialize_element("connect")?;
                seq.serialize_element(&DevicePayload {
                    device: device.clone(),
                })?;
            }
            Op::Disconnect { device } => {
                seq.serialize_element("disconnect")?;
                seq.serialize_element(&DevicePayload {
                    device: device.clone(),
                })?;
            }
            Op::Discover { device } => {
                seq.serialize_element("discover")?;
                seq.serialize_element(&DevicePayload {
                    device: device.clone(),
                })?;
            }
            Op::Read { device, char } => {
                seq.serialize_element("read")?;
                seq.serialize_element(&ReadPayload {
                    device: device.clone(),
                    char: char.clone(),
                })?;
            }
            Op::Write {
                device,
                char,
                value,
                with_response,
            } => {
                seq.serialize_element("write")?;
                seq.serialize_element(&WritePayload {
                    device: device.clone(),
                    char: char.clone(),
                    value: value.clone(),
                    with_response: *with_response,
                })?;
            }
            Op::ObserveStart {
                sub_id,
                device,
                char,
            } => {
                seq.serialize_element("observe.start")?;
                seq.serialize_element(&ObserveStartPayload {
                    sub_id: *sub_id,
                    device: device.clone(),
                    char: char.clone(),
                })?;
            }
            Op::ObserveStop { sub_id } => {
                seq.serialize_element("observe.stop")?;
                seq.serialize_element(&ObserveStopPayload { sub_id: *sub_id })?;
            }
            Op::RequestMtu { device, mtu } => {
                seq.serialize_element("mtu")?;
                seq.serialize_element(&MtuPayload {
                    device: device.clone(),
                    mtu: *mtu,
                })?;
            }
            Op::ReadDescriptor { device, desc } => {
                seq.serialize_element("desc.read")?;
                seq.serialize_element(&ReadDescPayload {
                    device: device.clone(),
                    desc: desc.clone(),
                })?;
            }
            Op::WriteDescriptor {
                device,
                desc,
                value,
            } => {
                seq.serialize_element("desc.write")?;
                seq.serialize_element(&WriteDescPayload {
                    device: device.clone(),
                    desc: desc.clone(),
                    value: value.clone(),
                })?;
            }
            Op::Pair { device } => {
                seq.serialize_element("pair")?;
                seq.serialize_element(&DevicePayload {
                    device: device.clone(),
                })?;
            }
            Op::Unpair { device } => {
                seq.serialize_element("unpair")?;
                seq.serialize_element(&DevicePayload {
                    device: device.clone(),
                })?;
            }
            Op::RequestConnectionPriority { device, priority } => {
                seq.serialize_element("conn.priority")?;
                seq.serialize_element(&ConnPriorityPayload {
                    device: device.clone(),
                    priority: *priority,
                })?;
            }
            Op::ReadRssi { device } => {
                seq.serialize_element("rssi")?;
                seq.serialize_element(&DevicePayload {
                    device: device.clone(),
                })?;
            }
            Op::SetConnParams {
                device,
                profile,
                hint,
            } => {
                seq.serialize_element("conn.params")?;
                seq.serialize_element(&SetConnParamsPayload {
                    device: device.clone(),
                    profile: *profile,
                    hint: *hint,
                })?;
            }
        }
        seq.end()
    }
}

impl<'de> Deserialize<'de> for Op {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct OpVisitor;

        impl<'de> Visitor<'de> for OpVisitor {
            type Value = Op;

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
                    "scan.start" => {
                        let p: ScanStartPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::ScanStart {
                            scan_id: p.scan_id,
                            filters: p.filters,
                        })
                    }
                    "scan.stop" => {
                        let p: ScanStopPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::ScanStop { scan_id: p.scan_id })
                    }
                    "connect" => {
                        let p: DevicePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::Connect { device: p.device })
                    }
                    "disconnect" => {
                        let p: DevicePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::Disconnect { device: p.device })
                    }
                    "discover" => {
                        let p: DevicePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::Discover { device: p.device })
                    }
                    "read" => {
                        let p: ReadPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::Read {
                            device: p.device,
                            char: p.char,
                        })
                    }
                    "write" => {
                        let p: WritePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::Write {
                            device: p.device,
                            char: p.char,
                            value: p.value,
                            with_response: p.with_response,
                        })
                    }
                    "observe.start" => {
                        let p: ObserveStartPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::ObserveStart {
                            sub_id: p.sub_id,
                            device: p.device,
                            char: p.char,
                        })
                    }
                    "observe.stop" => {
                        let p: ObserveStopPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::ObserveStop { sub_id: p.sub_id })
                    }
                    "mtu" => {
                        let p: MtuPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::RequestMtu {
                            device: p.device,
                            mtu: p.mtu,
                        })
                    }
                    "desc.read" => {
                        let p: ReadDescPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::ReadDescriptor {
                            device: p.device,
                            desc: p.desc,
                        })
                    }
                    "desc.write" => {
                        let p: WriteDescPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::WriteDescriptor {
                            device: p.device,
                            desc: p.desc,
                            value: p.value,
                        })
                    }
                    "pair" => {
                        let p: DevicePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::Pair { device: p.device })
                    }
                    "unpair" => {
                        let p: DevicePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::Unpair { device: p.device })
                    }
                    "conn.priority" => {
                        let p: ConnPriorityPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::RequestConnectionPriority {
                            device: p.device,
                            priority: p.priority,
                        })
                    }
                    "rssi" => {
                        let p: DevicePayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::ReadRssi { device: p.device })
                    }
                    "conn.params" => {
                        let p: SetConnParamsPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Op::SetConnParams {
                            device: p.device,
                            profile: p.profile,
                            hint: p.hint,
                        })
                    }
                    _ => Err(de::Error::unknown_variant(
                        &tag,
                        &[
                            "scan.start",
                            "scan.stop",
                            "connect",
                            "disconnect",
                            "discover",
                            "read",
                            "write",
                            "observe.start",
                            "observe.stop",
                            "mtu",
                            "desc.read",
                            "desc.write",
                            "pair",
                            "unpair",
                            "conn.priority",
                            "rssi",
                            "conn.params",
                        ],
                    )),
                }
            }
        }

        deserializer.deserialize_seq(OpVisitor)
    }
}
