package com.offline.ble.mesh.ble.protocol

import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap

class MessageAssembler(
    private val timeoutMs: Long = 30000L
) {

    private data class AssemblySession(
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = HashMap(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<Pair<Long, Long>, AssemblySession>()

    fun onChunkReceived(rawBytes: ByteArray): MessagePayload? {
        val chunk = MessageChunker.parseChunk(rawBytes) ?: return null
        val key = Pair(chunk.messageKeyHigh, chunk.messageKeyLow)

        cleanExpiredSessions()

        val session = sessions.compute(key) { _, existing ->
            val curr = existing ?: AssemblySession(chunk.totalChunks)
            curr.chunks[chunk.chunkIndex] = chunk.payload
            curr
        } ?: return null

        if (session.chunks.size == session.totalChunks) {
            sessions.remove(key)
            val outputStream = ByteArrayOutputStream()
            for (i in 0 until session.totalChunks) {
                val data = session.chunks[i] ?: return null
                outputStream.write(data)
            }
            return MessagePayload.fromBytes(outputStream.toByteArray())
        }

        return null
    }

    private fun cleanExpiredSessions() {
        val now = System.currentTimeMillis()
        val iterator = sessions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.createdAt > timeoutMs) {
                iterator.remove()
            }
        }
    }

    fun clear() {
        sessions.clear()
    }
}