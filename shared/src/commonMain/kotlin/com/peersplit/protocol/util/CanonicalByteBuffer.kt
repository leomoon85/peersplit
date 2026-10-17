package com.peersplit.protocol.util

/**
 * Cross-platform, deterministic binary buffer for canonical serialization.
 * Explicitly encodes integers in big-endian two's complement and strings as length-prefixed UTF-8.
 */
class CanonicalByteBuffer(initialCapacity: Int = 256) {
    private var buffer = ByteArray(initialCapacity)
    var size: Int = 0
        private set

    private fun ensureCapacity(additional: Int) {
        val required = size + additional
        if (required > buffer.size) {
            var newCap = buffer.size * 2
            while (newCap < required) newCap *= 2
            val newBuf = ByteArray(newCap)
            buffer.copyInto(newBuf, 0, 0, size)
            buffer = newBuf
        }
    }

    fun writeByte(v: Byte) {
        ensureCapacity(1)
        buffer[size++] = v
    }

    fun writeByte(v: Int) {
        writeByte(v.toByte())
    }

    fun writeBytes(bytes: ByteArray) {
        ensureCapacity(bytes.size)
        bytes.copyInto(buffer, size, 0, bytes.size)
        size += bytes.size
    }

    fun writeInt(v: Int) {
        ensureCapacity(4)
        buffer[size++] = (v ushr 24).toByte()
        buffer[size++] = (v ushr 16).toByte()
        buffer[size++] = (v ushr 8).toByte()
        buffer[size++] = v.toByte()
    }

    fun writeLong(v: Long) {
        ensureCapacity(8)
        buffer[size++] = (v ushr 56).toByte()
        buffer[size++] = (v ushr 48).toByte()
        buffer[size++] = (v ushr 40).toByte()
        buffer[size++] = (v ushr 32).toByte()
        buffer[size++] = (v ushr 24).toByte()
        buffer[size++] = (v ushr 16).toByte()
        buffer[size++] = (v ushr 8).toByte()
        buffer[size++] = v.toByte()
    }

    fun writeString(s: String) {
        val bytes = s.encodeToByteArray()
        writeInt(bytes.size)
        writeBytes(bytes)
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
