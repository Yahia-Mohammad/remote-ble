//! Wire-compatible byte-array codec.
//!
//! The Kotlin `:protocol` module models binary payloads as `ByteArray`, which
//! `kotlinx.serialization`'s CBOR encoder writes as a CBOR **array of signed
//! integers** — one element per byte, each in the `i8` range (e.g. `0xFF` is
//! encoded as `-1` / CBOR `0x20`, `0x80` as `-128` / CBOR `0x38 0x7f`). It does
//! *not* use a CBOR byte string (major type 2).
//!
//! `serde_bytes` would emit a byte string, and a plain `Vec<u8>` would emit
//! *unsigned* integers (so `0xFF` would be `0x18 0xFF`, which the Kotlin decoder
//! reads as `255` and cannot fit in a signed `Byte`). Either choice breaks
//! interop for any byte >= 0x80. These adapters mirror Kotlin exactly: serialize
//! each byte as an `i8`, and on decode accept any integer (signed or unsigned)
//! and mask it back into a `u8`, so we round-trip with the Kotlin client and
//! tolerate an unsigned peer too.

use serde::de::{self, SeqAccess, Visitor};
use serde::ser::{SerializeMap, SerializeSeq};
use serde::{Deserializer, Serialize, Serializer};
use std::collections::BTreeMap;
use std::fmt;

/// `#[serde(with = "signed_bytes")]` for a `Vec<u8>` field.
pub mod signed_bytes {
    use super::*;

    pub fn serialize<S: Serializer>(bytes: &[u8], serializer: S) -> Result<S::Ok, S::Error> {
        let mut seq = serializer.serialize_seq(Some(bytes.len()))?;
        for &b in bytes {
            seq.serialize_element(&(b as i8))?;
        }
        seq.end()
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(deserializer: D) -> Result<Vec<u8>, D::Error> {
        deserializer.deserialize_any(ByteSeqVisitor)
    }
}

/// `#[serde(with = "signed_bytes_map")]` for a `BTreeMap<i32, Vec<u8>>` field
/// (e.g. advertisement manufacturer data: integer company id -> bytes).
pub mod signed_bytes_map {
    use super::*;

    pub fn serialize<S: Serializer>(
        map: &BTreeMap<i32, Vec<u8>>,
        serializer: S,
    ) -> Result<S::Ok, S::Error> {
        let mut m = serializer.serialize_map(Some(map.len()))?;
        for (k, v) in map {
            m.serialize_entry(k, &SignedBytes(v))?;
        }
        m.end()
    }

    pub fn deserialize<'de, D: Deserializer<'de>>(
        deserializer: D,
    ) -> Result<BTreeMap<i32, Vec<u8>>, D::Error> {
        deserializer.deserialize_map(ByteMapVisitor)
    }
}

/// Serialize-only wrapper that renders a byte slice as signed-byte CBOR.
struct SignedBytes<'a>(&'a [u8]);

impl Serialize for SignedBytes<'_> {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        signed_bytes::serialize(self.0, serializer)
    }
}

/// Deserialize-only wrapper that reads signed-byte CBOR into a `Vec<u8>`.
struct SignedBytesBuf(Vec<u8>);

impl<'de> serde::Deserialize<'de> for SignedBytesBuf {
    fn deserialize<D: Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        signed_bytes::deserialize(deserializer).map(SignedBytesBuf)
    }
}

struct ByteSeqVisitor;

impl<'de> Visitor<'de> for ByteSeqVisitor {
    type Value = Vec<u8>;

    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("an array of byte-sized integers (or a byte string)")
    }

    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Self::Value, A::Error> {
        let mut out = Vec::with_capacity(seq.size_hint().unwrap_or(0));
        while let Some(v) = seq.next_element::<i64>()? {
            out.push(v as u8);
        }
        Ok(out)
    }

    // Tolerate a peer that uses a CBOR byte string for binary payloads.
    fn visit_bytes<E: de::Error>(self, v: &[u8]) -> Result<Self::Value, E> {
        Ok(v.to_vec())
    }

    fn visit_byte_buf<E: de::Error>(self, v: Vec<u8>) -> Result<Self::Value, E> {
        Ok(v)
    }
}

struct ByteMapVisitor;

impl<'de> Visitor<'de> for ByteMapVisitor {
    type Value = BTreeMap<i32, Vec<u8>>;

    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("a map of integer keys to byte arrays")
    }

    fn visit_map<A: serde::de::MapAccess<'de>>(self, mut map: A) -> Result<Self::Value, A::Error> {
        let mut out = BTreeMap::new();
        while let Some((k, v)) = map.next_entry::<i32, SignedBytesBuf>()? {
            out.insert(k, v.0);
        }
        Ok(out)
    }
}
