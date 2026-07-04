use super::errors::AgentError;
use serde::de::{self, SeqAccess, Visitor};
use serde::{Deserialize, Deserializer, Serialize, Serializer};
use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum BleBondState {
    None,
    Bonding,
    Bonded,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CharNode {
    pub uuid: String,
    pub properties: i32,
    #[serde(default)]
    pub descriptors: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServiceNode {
    pub uuid: String,
    pub characteristics: Vec<CharNode>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ResultPayload {
    Bytes { value: Vec<u8> },
    Services { services: Vec<ServiceNode> },
    Mtu { mtu: i32 },
    Bond { state: BleBondState },
}

#[derive(Serialize, Deserialize)]
struct BytesPayload {
    #[serde(with = "crate::protocol::bytes::signed_bytes")]
    value: Vec<u8>,
}

#[derive(Serialize, Deserialize)]
struct ServicesPayload {
    services: Vec<ServiceNode>,
}

#[derive(Serialize, Deserialize)]
struct MtuPayload {
    mtu: i32,
}

#[derive(Serialize, Deserialize)]
struct BondPayload {
    state: BleBondState,
}

impl Serialize for ResultPayload {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        use serde::ser::SerializeSeq;
        let mut seq = serializer.serialize_seq(Some(2))?;
        match self {
            ResultPayload::Bytes { value } => {
                seq.serialize_element("bytes")?;
                seq.serialize_element(&BytesPayload {
                    value: value.clone(),
                })?;
            }
            ResultPayload::Services { services } => {
                seq.serialize_element("services")?;
                seq.serialize_element(&ServicesPayload {
                    services: services.clone(),
                })?;
            }
            ResultPayload::Mtu { mtu } => {
                seq.serialize_element("mtu")?;
                seq.serialize_element(&MtuPayload { mtu: *mtu })?;
            }
            ResultPayload::Bond { state } => {
                seq.serialize_element("bond")?;
                seq.serialize_element(&BondPayload { state: *state })?;
            }
        }
        seq.end()
    }
}

impl<'de> Deserialize<'de> for ResultPayload {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct ResultPayloadVisitor;

        impl<'de> Visitor<'de> for ResultPayloadVisitor {
            type Value = ResultPayload;

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
                    "bytes" => {
                        let p: BytesPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(ResultPayload::Bytes { value: p.value })
                    }
                    "services" => {
                        let p: ServicesPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(ResultPayload::Services {
                            services: p.services,
                        })
                    }
                    "mtu" => {
                        let p: MtuPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(ResultPayload::Mtu { mtu: p.mtu })
                    }
                    "bond" => {
                        let p: BondPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(ResultPayload::Bond { state: p.state })
                    }
                    _ => Err(de::Error::unknown_variant(
                        &tag,
                        &["bytes", "services", "mtu", "bond"],
                    )),
                }
            }
        }

        deserializer.deserialize_seq(ResultPayloadVisitor)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum OpResult {
    Ok { payload: Option<ResultPayload> },
    Err { error: AgentError },
}

#[derive(Serialize, Deserialize)]
struct OkPayload {
    #[serde(skip_serializing_if = "Option::is_none")]
    payload: Option<ResultPayload>,
}

#[derive(Serialize, Deserialize)]
struct ErrPayload {
    error: AgentError,
}

impl Serialize for OpResult {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        use serde::ser::SerializeSeq;
        let mut seq = serializer.serialize_seq(Some(2))?;
        match self {
            OpResult::Ok { payload } => {
                seq.serialize_element("ok")?;
                seq.serialize_element(&OkPayload {
                    payload: payload.clone(),
                })?;
            }
            OpResult::Err { error } => {
                seq.serialize_element("err")?;
                seq.serialize_element(&ErrPayload {
                    error: error.clone(),
                })?;
            }
        }
        seq.end()
    }
}

impl<'de> Deserialize<'de> for OpResult {
    fn deserialize<D>(deserializer: D) -> Result<Self, D::Error>
    where
        D: Deserializer<'de>,
    {
        struct OpResultVisitor;

        impl<'de> Visitor<'de> for OpResultVisitor {
            type Value = OpResult;

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
                    "ok" => {
                        let p: OkPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(OpResult::Ok { payload: p.payload })
                    }
                    "err" => {
                        let p: ErrPayload = seq
                            .next_element()?
                            .ok_or_else(|| de::Error::invalid_length(1, &self))?;
                        Ok(OpResult::Err { error: p.error })
                    }
                    _ => Err(de::Error::unknown_variant(&tag, &["ok", "err"])),
                }
            }
        }

        deserializer.deserialize_seq(OpResultVisitor)
    }
}

impl OpResult {
    pub fn ok(payload: Option<ResultPayload>) -> Self {
        OpResult::Ok { payload }
    }

    pub fn err(error: AgentError) -> Self {
        OpResult::Err { error }
    }

    /// Reply for a backend op that returns no payload (connect, write, observe…).
    pub fn from_unit(result: Result<(), AgentError>) -> Self {
        match result {
            Ok(()) => OpResult::ok(None),
            Err(e) => OpResult::err(e),
        }
    }

    /// Reply for a backend op that returns a payload (discover, read, mtu…).
    pub fn from_payload(result: Result<ResultPayload, AgentError>) -> Self {
        match result {
            Ok(payload) => OpResult::ok(Some(payload)),
            Err(e) => OpResult::err(e),
        }
    }
}
