package com.offline.ble.mesh.protocol

import com.offline.ble.mesh.ble.protocol.AckPayload
import com.offline.ble.mesh.ble.protocol.MessageAssembler
import com.offline.ble.mesh.ble.protocol.MessageChunker
import com.offline.ble.mesh.ble.protocol.MessagePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class MessageChunkerTest {

    @Test
    fun testSmallMessageSingleChunk() {
        val msgId = UUID.randomUUID().toString()
        val payload = MessagePayload(
            messageId = msgId,
            senderId = "sender-1234",
            receiverId = "receiver-5678",
            timestamp = 1700000000000L,
            content = "Hello offline world!"
        )
        val bytes = payload.toBytes()

        val chunks = MessageChunker.chunkMessage(msgId, bytes, maxPayloadPerChunk = 180)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(1, chunks[0].totalChunks)

        val assembler = MessageAssembler()
        val result = assembler.onChunkReceived(chunks[0].rawBytes)
        assertNotNull(result)
        assertEquals(payload.messageId, result!!.messageId)
        assertEquals(payload.content, result.content)
        assertEquals(payload.senderId, result.senderId)
        assertEquals(payload.receiverId, result.receiverId)
    }

    @Test
    fun testLargeMessageMultipleChunksReassembly() {
        val msgId = UUID.randomUUID().toString()
        val largeContent = StringBuilder().apply {
            for (i in 1..200) {
                append("ChunkMultiTest-$i-OfflineBLE;")
            }
        }.toString()

        val payload = MessagePayload(
            messageId = msgId,
            senderId = "node-alpha",
            receiverId = "node-beta",
            timestamp = 1700000000000L,
            content = largeContent,
            messageType = "DATA",
            hopCount = 1,
            ttl = 4
        )
        val bytes = payload.toBytes()
        assertTrue(bytes.size > 2000)

        // Chunk with small 100 byte chunks to test multi-chunk fragmentation
        val chunks = MessageChunker.chunkMessage(msgId, bytes, maxPayloadPerChunk = 100)
        assertTrue(chunks.size > 20)

        val assembler = MessageAssembler()
        var assembled: MessagePayload? = null

        for (i in 0 until chunks.size) {
            val res = assembler.onChunkReceived(chunks[i].rawBytes)
            if (i == chunks.size - 1) {
                assembled = res
            } else {
                assertNull(res)
            }
        }

        assertNotNull(assembled)
        assertEquals(payload.messageId, assembled!!.messageId)
        assertEquals(payload.content, assembled.content)
        assertEquals(payload.messageType, assembled.messageType)
        assertEquals(payload.hopCount, assembled.hopCount)
        assertEquals(payload.ttl, assembled.ttl)
    }

    @Test
    fun testAckPayloadSerialization() {
        val msgId = UUID.randomUUID().toString()
        val ack = AckPayload(
            messageId = msgId,
            receiverId = "receiver-test",
            timestamp = 1712345678900L
        )

        val bytes = ack.toBytes()
        val parsed = AckPayload.fromBytes(bytes)
        assertNotNull(parsed)
        assertEquals(ack.messageId, parsed!!.messageId)
        assertEquals(ack.receiverId, parsed.receiverId)
        assertEquals(ack.timestamp, parsed.timestamp)
    }
}