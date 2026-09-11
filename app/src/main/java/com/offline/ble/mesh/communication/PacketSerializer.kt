package com.offline.ble.mesh.communication

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PacketSerializer {

    data class RawChunk(
        val keyHigh: Long,
        val keyLow: Long,
        val index: Int,
        val total: Int,
        val payload: ByteArray,
        val packetBytes: ByteArray
    )

    fun serializePacket(packet: MeshPacket): ByteArray {
        val json = JSONObject().apply {
            put("mid", packet.messageId)
            put("ts", packet.timestamp)
            put("snd", packet.senderId)
            put("lng", packet.senderLang)
            put("pri", packet.priority)
            put("txt", packet.textContent)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun deserializePacket(bytes: ByteArray): MeshPacket? {
        return try {
            if (bytes.isEmpty()) return null
            val str = String(bytes, Charsets.UTF_8)
            val json = JSONObject(str)
            MeshPacket(
                messageId = json.getString("mid"),
                timestamp = json.getLong("ts"),
                senderId = json.getString("snd"),
                senderLang = json.optString("lng", "en"),
                priority = json.optString("pri", "normal"),
                textContent = json.getString("txt")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractMessageKey(messageId: String): Pair<Long, Long> {
        return try {
            val uuid = UUID.fromString(messageId)
            Pair(uuid.mostSignificantBits, uuid.leastSignificantBits)
        } catch (e: Exception) {
            val md5 = MessageDigest.getInstance("MD5").digest(messageId.toByteArray(Charsets.UTF_8))
            val bb = ByteBuffer.wrap(md5)
            Pair(bb.long, bb.long)
        }
    }

    fun chunkPacket(packet: MeshPacket, maxPayloadSize: Int = CommunicationConstants.MAX_CHUNK_PAYLOAD_SIZE): List<RawChunk> {
        val payloadBytes = serializePacket(packet)
        val (high, low) = extractMessageKey(packet.messageId)
        val totalLength = payloadBytes.size
        val totalChunks = if (totalLength == 0) 1 else ((totalLength + maxPayloadSize - 1) / maxPayloadSize)
        val chunks = ArrayList<RawChunk>(totalChunks)

        for (i in 0 until totalChunks) {
            val offset = i * maxPayloadSize
            val length = kotlin.math.min(maxPayloadSize, totalLength - offset)
            val chunkData = if (length > 0) payloadBytes.copyOfRange(offset, offset + length) else ByteArray(0)

            val rawBuffer = ByteBuffer.allocate(CommunicationConstants.CHUNK_HEADER_SIZE + chunkData.size)
            rawBuffer.putLong(high)
            rawBuffer.putLong(low)
            rawBuffer.putShort(i.toShort())
            rawBuffer.putShort(totalChunks.toShort())
            if (chunkData.isNotEmpty()) {
                rawBuffer.put(chunkData)
            }

            chunks.add(
                RawChunk(
                    keyHigh = high,
                    keyLow = low,
                    index = i,
                    total = totalChunks,
                    payload = chunkData,
                    packetBytes = rawBuffer.array()
                )
            )
        }
        return chunks
    }

    fun parseRawChunk(rawBytes: ByteArray): RawChunk? {
        if (rawBytes.size < CommunicationConstants.CHUNK_HEADER_SIZE) return null
        return try {
            val buffer = ByteBuffer.wrap(rawBytes)
            val high = buffer.long
            val low = buffer.long
            val index = buffer.short.toInt() and 0xFFFF
            val total = buffer.short.toInt() and 0xFFFF
            val payloadSize = rawBytes.size - CommunicationConstants.CHUNK_HEADER_SIZE
            val payload = ByteArray(payloadSize)
            if (payloadSize > 0) {
                buffer.get(payload)
            }
            RawChunk(
                keyHigh = high,
                keyLow = low,
                index = index,
                total = total,
                payload = payload,
                packetBytes = rawBytes
            )
        } catch (e: Exception) {
            null
        }
    }

    class ChunkAssembler(private val timeoutMs: Long = 30000L) {
        private data class Session(
            val totalChunks: Int,
            val chunks: MutableMap<Int, ByteArray> = HashMap(),
            val createdAt: Long = System.currentTimeMillis()
        )

        private val sessions = ConcurrentHashMap<Pair<Long, Long>, Session>()

        fun processChunk(rawBytes: ByteArray): MeshPacket? {
            val chunk = parseRawChunk(rawBytes) ?: return null
            val key = Pair(chunk.keyHigh, chunk.keyLow)

            pruneExpired()

            val session = sessions.compute(key) { _, existing ->
                val current = existing ?: Session(chunk.total)
                current.chunks[chunk.index] = chunk.payload
                current
            } ?: return null

            if (session.chunks.size == session.totalChunks) {
                sessions.remove(key)
                val out = ByteArrayOutputStream()
                for (i in 0 until session.totalChunks) {
                    val part = session.chunks[i] ?: return null
                    out.write(part)
                }
                return deserializePacket(out.toByteArray())
            }
            return null
        }

        private fun pruneExpired() {
            val now = System.currentTimeMillis()
            val it = sessions.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                if (now - entry.value.createdAt > timeoutMs) {
                    it.remove()
                }
            }
        }

        fun clear() {
            sessions.clear()
        }
    }
}