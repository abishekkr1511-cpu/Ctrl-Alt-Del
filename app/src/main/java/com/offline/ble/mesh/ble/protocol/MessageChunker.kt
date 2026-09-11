package com.offline.ble.mesh.ble.protocol

import com.offline.ble.mesh.ble.BleConstants
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

object MessageChunker {

    data class Chunk(
        val messageKeyHigh: Long,
        val messageKeyLow: Long,
        val chunkIndex: Int,
        val totalChunks: Int,
        val payload: ByteArray,
        val rawBytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Chunk
            return messageKeyHigh == other.messageKeyHigh &&
                    messageKeyLow == other.messageKeyLow &&
                    chunkIndex == other.chunkIndex &&
                    totalChunks == other.totalChunks &&
                    payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = messageKeyHigh.hashCode()
            result = 31 * result + messageKeyLow.hashCode()
            result = 31 * result + chunkIndex
            result = 31 * result + totalChunks
            result = 31 * result + payload.contentHashCode()
            return result
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

    fun chunkMessage(
        messageId: String,
        payloadBytes: ByteArray,
        maxPayloadPerChunk: Int = BleConstants.MAX_CHUNK_PAYLOAD_SIZE
    ): List<Chunk> {
        val (high, low) = extractMessageKey(messageId)
        val totalLength = payloadBytes.size
        val totalChunks = if (totalLength == 0) 1 else ((totalLength + maxPayloadPerChunk - 1) / maxPayloadPerChunk)
        val chunks = ArrayList<Chunk>(totalChunks)

        for (i in 0 until totalChunks) {
            val offset = i * maxPayloadPerChunk
            val length = kotlin.math.min(maxPayloadPerChunk, totalLength - offset)
            val chunkData = if (length > 0) {
                payloadBytes.copyOfRange(offset, offset + length)
            } else {
                ByteArray(0)
            }

            val rawBuffer = ByteBuffer.allocate(BleConstants.CHUNK_HEADER_SIZE + chunkData.size)
            rawBuffer.putLong(high)
            rawBuffer.putLong(low)
            rawBuffer.putShort(i.toShort())
            rawBuffer.putShort(totalChunks.toShort())
            if (chunkData.isNotEmpty()) {
                rawBuffer.put(chunkData)
            }

            chunks.add(
                Chunk(
                    messageKeyHigh = high,
                    messageKeyLow = low,
                    chunkIndex = i,
                    totalChunks = totalChunks,
                    payload = chunkData,
                    rawBytes = rawBuffer.array()
                )
            )
        }

        return chunks
    }

    fun parseChunk(rawBytes: ByteArray): Chunk? {
        if (rawBytes.size < BleConstants.CHUNK_HEADER_SIZE) return null
        return try {
            val buffer = ByteBuffer.wrap(rawBytes)
            val high = buffer.long
            val low = buffer.long
            val index = buffer.short.toInt() and 0xFFFF
            val total = buffer.short.toInt() and 0xFFFF
            val payloadSize = rawBytes.size - BleConstants.CHUNK_HEADER_SIZE
            val payload = ByteArray(payloadSize)
            if (payloadSize > 0) {
                buffer.get(payload)
            }
            Chunk(
                messageKeyHigh = high,
                messageKeyLow = low,
                chunkIndex = index,
                totalChunks = total,
                payload = payload,
                rawBytes = rawBytes
            )
        } catch (e: Exception) {
            null
        }
    }
}