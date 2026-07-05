use super::events::AgentEvent;
use super::op::{IdentifierFormat, Op};
use super::results::OpResult;
use serde::de::{self, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use std::collections::BTreeSet;
use std::fmt;

pub const PROTOCOL_VERSION: i32 = 1;

/// The complete capability vocabulary (mirrors Kotlin `Capabilities`). The agent
/// only *advertises* a capability once it's implemented end-to-end (see
/// `BleBackend::capabilities`), so several of these are defined but not yet
/// advertised — they pin the wire-stable names for when support lands.
#[allow(dead_code)]
pub mod capabilities {
    pub const DESCRIPTORS: &str = "descriptors";
    pub const PAIRING: &str = "pairing";
    pub const CONNECTION_SLOTS: &str = "slots";
    pub const CONN_PRIORITY: &str = "conn.priority";
    pub const SCAN_BATCH: &str = "scan.batch";
    pub const IDENTIFIER_TRANSLATION: &str = "identifier.translate";
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Frame {
    Command {
        cid: i64,
        op: Op,
    },
    Reply {
        cid: i64,
        result: OpResult,
    },
    Event {
        event: AgentEvent,
    },
    ClientHello {
        min_version: i32,
        max_version: i32,
        capabilities: BTreeSet<String>,
        identifier_format: Option<IdentifierFormat>,
    },
    ServerHello {
        version: i32,
        capabilities: BTreeSet<String>,
        agent_info: Option<String>,
    },
}

#[derive(Serialize, Deserialize)]
struct CommandPayload {
    cid: i64,
    op: Op,
}

#[derive(Serialize, Deserialize)]
struct ReplyPayload {
    cid: i64,
    result: OpResult,
}

#[derive(Serialize, Deserialize)]
struct EventPayload {
    event: AgentEvent,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ClientHelloPayload {
    #[serde(default = "default_version", rename = "minVersion")]
    min_version: i32,
    #[serde(default = "default_version", rename = "maxVersion")]
    max_version: i32,
    #[serde(default)]
    capabilities: BTreeSet<String>,
    #[serde(
        default,
        skip_serializing_if = "Option::is_none",
        rename = "identifierFormat"
    )]
    identifier_format: Option<IdentifierFormat>,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ServerHelloPayload {
    #[serde(default = "default_version")]
    version: i32,
    #[serde(default)]
    capabilities: BTreeSet<String>,
    #[serde(skip_serializing_if = "Option::is_none", rename = "agentInfo")]
    agent_info: Option<String>,
}

fn default_version() -> i32 {
    PROTOCOL_VERSION
}

impl Serialize for Frame {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        use serde::ser::SerializeSeq;
        let mut seq = serializer.serialize_seq(Some(2))?;
        match self {
            Frame::Command { cid, op } => {
                seq.serialize_element("cmd")?;
                seq.serialize_element(&CommandPayload {
                    cid: *cid,
                    op: op.clone(),
                })?;
            }
            Frame::Reply { cid, result } => {
                seq.serialize_element("reply")?;
                seq.serialize_element(&ReplyPayload {
                    cid: *cid,
                    result: result.clone(),
                })?;
            }
            Frame::Event { event } => {
                seq.serialize_element("event")?;
                seq.serialize_element(&EventPayload {
                    event: event.clone(),
                })?;
            }
            Frame::ClientHello {
                min_version,
                max_version,
                capabilities,
                identifier_format,
            } => {
                seq.serialize_element("hello")?;
                seq.serialize_element(&ClientHelloPayload {
                    min_version: *min_version,
                    max_version: *max_version,
                    capabilities: capabilities.clone(),
                    identifier_format: *identifier_format,
                })?;
            }
            Frame::ServerHello {
                version,
                capabilities,
                agent_info,
            } => {
                seq.serialize_element("server_hello")?;
                seq.serialize_element(&ServerHelloPayload {
                    version: *version,
                    capabilities: capabilities.clone(),
                    agent_info: agent_info.clone(),
                })?;
            }
        }
        seq.end()
    }
}

impl<'de> Deserialize<'de> for Frame {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct FrameVisitor;

        impl<'de> Visitor<'de> for FrameVisitor {
            type Value = Frame;

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
                    "cmd" => {
                        let p: CommandPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Frame::Command {
                            cid: p.cid,
                            op: p.op,
                        })
                    }
                    "reply" => {
                        let p: ReplyPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Frame::Reply {
                            cid: p.cid,
                            result: p.result,
                        })
                    }
                    "event" => {
                        let p: EventPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Frame::Event { event: p.event })
                    }
                    "hello" => {
                        let p: ClientHelloPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Frame::ClientHello {
                            min_version: p.min_version,
                            max_version: p.max_version,
                            capabilities: p.capabilities,
                            identifier_format: p.identifier_format,
                        })
                    }
                    "server_hello" => {
                        let p: ServerHelloPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(Frame::ServerHello {
                            version: p.version,
                            capabilities: p.capabilities,
                            agent_info: p.agent_info,
                        })
                    }
                    _ => Err(de::Error::unknown_variant(
                        &tag,
                        &["cmd", "reply", "event", "hello", "server_hello"],
                    )),
                }
            }
        }

        deserializer.deserialize_seq(FrameVisitor)
    }
}
