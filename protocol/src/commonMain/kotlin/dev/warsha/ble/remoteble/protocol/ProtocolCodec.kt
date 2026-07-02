package dev.warsha.ble.remoteble.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json

/**
 * Encodes/decodes a [Frame] to/from a self-describing byte array. The session
 * and transport layers stay codec-agnostic: swap CBOR for JSON (or anything
 * else) without touching them.
 *
 * The polymorphic discriminators come from the `@SerialName` values on the wire
 * hierarchy — those are the frozen wire identity.
 */
interface ProtocolCodec {
    fun encode(frame: Frame): ByteArray
    fun decode(bytes: ByteArray): Frame
}

/**
 * Production codec: CBOR. The `ByteArray` payloads in reads/writes/notifications
 * make a binary format the right default; JSON is for debugging only.
 */
class CborProtocolCodec : ProtocolCodec {
    // Kept off the public API so consumers don't inherit CBOR's experimental opt-in.
    @OptIn(ExperimentalSerializationApi::class)
    private val cbor: Cbor = Cbor.Default

    @OptIn(ExperimentalSerializationApi::class)
    override fun encode(frame: Frame): ByteArray = cbor.encodeToByteArray(frame)

    @OptIn(ExperimentalSerializationApi::class)
    override fun decode(bytes: ByteArray): Frame = cbor.decodeFromByteArray(bytes)
}

/** Debug codec: human-readable JSON over UTF-8 bytes. */
class JsonProtocolCodec(
    private val json: Json = Json.Default,
) : ProtocolCodec {
    override fun encode(frame: Frame): ByteArray =
        json.encodeToString(Frame.serializer(), frame).encodeToByteArray()

    override fun decode(bytes: ByteArray): Frame =
        json.decodeFromString(Frame.serializer(), bytes.decodeToString())
}
