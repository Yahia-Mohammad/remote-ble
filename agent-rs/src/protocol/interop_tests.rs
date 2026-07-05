//! Cross-language wire-compatibility tests.
//!
//! The byte strings below are the *ground truth* produced by the Kotlin
//! `:protocol` module (`kotlinx.serialization` CBOR, `Cbor.Default`) for known
//! frames. These tests decode that Kotlin output with the Rust codec and assert
//! we reconstruct the expected frame — proving the Rust agent and the Kotlin
//! client agree on the wire. They guard the subtle bits: polymorphic framing
//! (`[tag, map]` arrays), enums as SCREAMING_SNAKE_CASE strings, camelCase field
//! names (`gattStatus`), and `ByteArray` as an array of *signed* bytes.
//!
//! To regenerate the ground truth, see `WireDumpTest` on the Kotlin side.

use super::codec::{decode_cbor, encode_cbor};
use super::errors::{AgentError, ErrorKind};
use super::events::{AdvertisementDto, AgentEvent, BleConnState};
use super::frame::Frame;
use super::op::{CharRef, ConnPriority, DeviceHandle, IdentifierFormat, Op};
use super::results::{OpResult, ResultPayload};
use std::collections::{BTreeMap, BTreeSet};

fn unhex(s: &str) -> Vec<u8> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
        .collect()
}

fn dev() -> DeviceHandle {
    DeviceHandle {
        value: "AA:BB:CC:DD:EE:FF".into(),
    }
}

/// Decode Kotlin bytes, assert they match `expected`, then assert that
/// re-encoding with the Rust codec round-trips back to the same frame (Rust's
/// definite-length output must itself be decodable).
fn assert_kotlin_decodes_to(hex: &str, expected: Frame) {
    let decoded = decode_cbor(&unhex(hex)).expect("Rust must decode Kotlin CBOR");
    assert_eq!(decoded, expected, "decoded Kotlin frame mismatch");
    let reencoded = encode_cbor(&expected).expect("Rust must encode");
    let redecoded = decode_cbor(&reencoded).expect("Rust must decode its own output");
    assert_eq!(redecoded, expected, "Rust self round-trip mismatch");
}

#[test]
fn client_hello_default() {
    assert_kotlin_decodes_to(
        "9f6568656c6c6fbfffff",
        Frame::ClientHello {
            min_version: 1,
            max_version: 1,
            capabilities: BTreeSet::new(),
            identifier_format: None,
        },
    );
}

#[test]
fn client_hello_with_caps() {
    let caps: BTreeSet<String> = ["descriptors", "pairing"]
        .iter()
        .map(|s| s.to_string())
        .collect();
    assert_kotlin_decodes_to(
        "9f6568656c6c6fbf6a6d617856657273696f6e026c6361706162696c69746965739f6b64657363726970746f72736770616972696e67ffffff",
        Frame::ClientHello {
            min_version: 1,
            max_version: 2,
            capabilities: caps,
            identifier_format: None,
        },
    );
}

#[test]
fn client_hello_with_identifier_format() {
    // Kotlin ClientHello(capabilities={identifier.translate}, identifierFormat=UUID) — proves the
    // Rust agent decodes the 0.8.0 handshake field and its SCREAMING_SNAKE enum encoding.
    let caps: BTreeSet<String> = ["identifier.translate"]
        .iter()
        .map(|s| s.to_string())
        .collect();
    assert_kotlin_decodes_to(
        "9f6568656c6c6fbf6c6361706162696c69746965739f746964656e7469666965722e7472616e736c617465ff706964656e746966696572466f726d61746455554944ffff",
        Frame::ClientHello {
            min_version: 1,
            max_version: 1,
            capabilities: caps,
            identifier_format: Some(IdentifierFormat::Uuid),
        },
    );
}

#[test]
fn server_hello() {
    let caps: BTreeSet<String> = ["descriptors"].iter().map(|s| s.to_string()).collect();
    assert_kotlin_decodes_to(
        "9f6c7365727665725f68656c6c6fbf6c6361706162696c69746965739f6b64657363726970746f7273ff696167656e74496e666f71626c75652d66616c636f6e2f6d61634f53ffff",
        Frame::ServerHello {
            version: 1,
            capabilities: caps,
            agent_info: Some("blue-falcon/macOS".into()),
        },
    );
}

#[test]
fn cmd_scan_start() {
    assert_kotlin_decodes_to(
        "9f63636d64bf6363696407626f709f6a7363616e2e7374617274bf667363616e496407ffffffff",
        Frame::Command {
            cid: 7,
            op: Op::ScanStart {
                scan_id: 7,
                filters: vec![],
            },
        },
    );
}

#[test]
fn cmd_connect() {
    assert_kotlin_decodes_to(
        "9f63636d64bf6363696404626f709f67636f6e6e656374bf66646576696365bf6576616c75657141413a42423a43433a44443a45453a4646ffffffffff",
        Frame::Command {
            cid: 4,
            op: Op::Connect { device: dev() },
        },
    );
}

#[test]
fn cmd_write_value_is_signed_byte_array() {
    assert_kotlin_decodes_to(
        "9f63636d64bf636369640a626f709f657772697465bf66646576696365bf6576616c75657141413a42423a43433a44443a45453a4646ff6463686172bf6773657276696365637376636e636861726163746572697374696363636872ff6576616c75659f010203ff6c77697468526573706f6e7365f5ffffffff",
        Frame::Command {
            cid: 10,
            op: Op::Write {
                device: dev(),
                char: CharRef {
                    service: "svc".into(),
                    characteristic: "chr".into(),
                    instance: 0,
                },
                value: vec![1, 2, 3],
                with_response: true,
            },
        },
    );
}

#[test]
fn cmd_conn_priority_enum_is_string() {
    assert_kotlin_decodes_to(
        "9f63636d64bf63636964181e626f709f6d636f6e6e2e7072696f72697479bf66646576696365bf6576616c75657141413a42423a43433a44443a45453a4646ff687072696f726974796448494748ffffffff",
        Frame::Command {
            cid: 30,
            op: Op::RequestConnectionPriority {
                device: dev(),
                priority: ConnPriority::High,
            },
        },
    );
}

#[test]
fn reply_ok_null() {
    assert_kotlin_decodes_to(
        "9f657265706c79bf636369640166726573756c749f626f6bbfffffffff",
        Frame::Reply {
            cid: 1,
            result: OpResult::Ok { payload: None },
        },
    );
}

#[test]
fn reply_ok_mtu() {
    assert_kotlin_decodes_to(
        "9f657265706c79bf636369640666726573756c749f626f6bbf677061796c6f61649f636d7475bf636d747518b9ffffffffffff",
        Frame::Reply {
            cid: 6,
            result: OpResult::Ok {
                payload: Some(ResultPayload::Mtu { mtu: 185 }),
            },
        },
    );
}

#[test]
fn reply_err_carries_camelcase_gatt_status() {
    assert_kotlin_decodes_to(
        "9f657265706c79bf636369640866726573756c749f63657272bf656572726f72bf646b696e646a474154545f4552524f526a676174745374617475731885676d6573736167656178ffffffffff",
        Frame::Reply {
            cid: 8,
            result: OpResult::Err {
                error: AgentError {
                    kind: ErrorKind::GattError,
                    gatt_status: Some(133),
                    message: Some("x".into()),
                },
            },
        },
    );
}

#[test]
fn event_conn_state_enum_string() {
    assert_kotlin_decodes_to(
        "9f656576656e74bf656576656e749f6a636f6e6e2e7374617465bf66646576696365bf6576616c75657141413a42423a43433a44443a45453a4646ff65737461746569434f4e4e4543544544ffffffff",
        Frame::Event {
            event: AgentEvent::ConnectionState {
                device: dev(),
                state: BleConnState::Connected,
                reason: None,
            },
        },
    );
}

#[test]
fn reply_ok_bytes_signed_high_bytes() {
    // Kotlin ByteArray [0x00, 0x7f, 0x80, 0xff] -> CBOR signed ints [0, 127, -128, -1].
    assert_kotlin_decodes_to(
        "9f657265706c79bf636369640266726573756c749f626f6bbf677061796c6f61649f656279746573bf6576616c75659f00187f387f20ffffffffffffff",
        Frame::Reply {
            cid: 2,
            result: OpResult::Ok {
                payload: Some(ResultPayload::Bytes {
                    value: vec![0x00, 0x7f, 0x80, 0xff],
                }),
            },
        },
    );
}

#[test]
fn event_notification_signed_high_bytes() {
    assert_kotlin_decodes_to(
        "9f656576656e74bf656576656e749f6c6e6f74696669636174696f6ebf657375624964182a6576616c75659f387f20ffffffffff",
        Frame::Event {
            event: AgentEvent::Notification {
                sub_id: 42,
                value: vec![0x80, 0xff],
            },
        },
    );
}

#[test]
fn event_scan_result_manufacturer_data_signed() {
    let mut mfg = BTreeMap::new();
    mfg.insert(0x4C, vec![0x80, 0xff]);
    assert_kotlin_decodes_to(
        "9f656576656e74bf656576656e749f6b7363616e2e726573756c74bf667363616e4964076d6164766572746973656d656e74bf66646576696365bf6576616c75657141413a42423a43433a44443a45453a4646ff647273736920706d616e75666163747572657244617461bf184c9f387f20ffffffffffffff",
        Frame::Event {
            event: AgentEvent::ScanResult {
                scan_id: 7,
                advertisement: AdvertisementDto {
                    device: dev(),
                    name: None,
                    rssi: -1,
                    service_uuids: vec![],
                    manufacturer_data: mfg,
                },
            },
        },
    );
}
