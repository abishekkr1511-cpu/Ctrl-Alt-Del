package com.offline.ble.mesh.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PacketSerializerTest {

    @Test
    fun testMeshPacketSerializationAndDeserialization() {
        val original = MeshPacket(
            messageId = UUID.randomUUID().toString(),
            timestamp = 1725800000000L,
            senderId = "node-alpha-1234",
            senderLang = "ta",
            priority = "urgent",
            textContent = "வணக்கம், இது ஒரு ஆஃப்லைன் செய்தி"
        )

        val bytes = PacketSerializer.serializePacket(original)
        assertTrue(bytes.isNotEmpty())

        val deserialized = PacketSerializer.deserializePacket(bytes)
        assertNotNull(deserialized)
        assertEquals(original.messageId, deserialized!!.messageId)
        assertEquals(original.timestamp, deserialized.timestamp)
        assertEquals(original.senderId, deserialized.senderId)
        assertEquals(original.senderLang, deserialized.senderLang)
        assertEquals(original.priority, deserialized.priority)
        assertEquals(original.textContent, deserialized.textContent)
    }

    @Test
    fun testMalformedBytesReturnNullSafely() {
        val invalidBytes = "Not a valid json string {[]}".toByteArray(Charsets.UTF_8)
        val result = PacketSerializer.deserializePacket(invalidBytes)
        assertNull(result)

        val emptyResult = PacketSerializer.deserializePacket(ByteArray(0))
        assertNull(emptyResult)
    }

    @Test
    fun testMultiChunkPacketAssembly() {
        val largeText = (1..50).joinToString(";") { "TextPayloadChunkFragment#$it-Data" }
        val packet = MeshPacket(
            messageId = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            senderId = "sender-555",
            senderLang = "en",
            priority = "normal",
            textContent = largeText
        )

        // Chunk with small 80-byte slices
        val chunks = PacketSerializer.chunkPacket(packet, maxPayloadSize = 80)
        assertTrue(chunks.size > 15)

        val assembler = PacketSerializer.ChunkAssembler()
        var assembledPacket: MeshPacket? = null

        for (i in chunks.indices) {
            val res = assembler.processChunk(chunks[i].packetBytes)
            if (i == chunks.size - 1) {
                assembledPacket = res
            } else {
                assertNull(res)
            }
        }

        assertNotNull(assembledPacket)
        assertEquals(packet.messageId, assembledPacket!!.messageId)
        assertEquals(packet.textContent, assembledPacket.textContent)
        assertEquals(packet.senderId, assembledPacket.senderId)
    }
}