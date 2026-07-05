use super::frame::Frame;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum CodecError {
    #[error("CBOR serialization error: {0}")]
    CborSer(String),
    #[error("CBOR deserialization error: {0}")]
    CborDe(String),
}

pub fn encode_cbor(frame: &Frame) -> Result<Vec<u8>, CodecError> {
    let mut bytes = Vec::new();
    ciborium::into_writer(frame, &mut bytes).map_err(|e| CodecError::CborSer(e.to_string()))?;
    Ok(bytes)
}

pub fn decode_cbor(bytes: &[u8]) -> Result<Frame, CodecError> {
    ciborium::from_reader(bytes).map_err(|e| CodecError::CborDe(e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{
        events::{AgentEvent, BleConnState},
        frame::capabilities,
        op::{DeviceHandle, Op},
        results::{OpResult, ResultPayload},
    };
    use std::collections::BTreeSet;

    #[test]
    fn test_client_hello_roundtrip() {
        let mut caps = BTreeSet::new();
        caps.insert(capabilities::DESCRIPTORS.to_string());
        let hello = Frame::ClientHello {
            min_version: 1,
            max_version: 1,
            capabilities: caps,
            identifier_format: None,
        };

        let encoded = encode_cbor(&hello).unwrap();
        let decoded = decode_cbor(&encoded).unwrap();
        assert_eq!(hello, decoded);
    }

    #[test]
    fn test_command_roundtrip() {
        let cmd = Frame::Command {
            cid: 42,
            op: Op::Connect {
                device: DeviceHandle {
                    value: "AA:BB:CC:DD:EE:FF".into(),
                },
            },
        };

        let encoded = encode_cbor(&cmd).unwrap();
        let decoded = decode_cbor(&encoded).unwrap();
        assert_eq!(cmd, decoded);
    }

    #[test]
    fn test_reply_bytes_roundtrip() {
        let reply = Frame::Reply {
            cid: 42,
            result: OpResult::Ok {
                payload: Some(ResultPayload::Bytes {
                    value: vec![0x01, 0x02, 0x03],
                }),
            },
        };

        let encoded = encode_cbor(&reply).unwrap();
        let decoded = decode_cbor(&encoded).unwrap();
        assert_eq!(reply, decoded);
    }

    #[test]
    fn test_event_conn_state_roundtrip() {
        let event = Frame::Event {
            event: AgentEvent::ConnectionState {
                device: DeviceHandle {
                    value: "1234-5678".into(),
                },
                state: BleConnState::Connected,
                reason: None,
            },
        };

        let encoded = encode_cbor(&event).unwrap();
        let decoded = decode_cbor(&encoded).unwrap();
        assert_eq!(event, decoded);
    }
}
