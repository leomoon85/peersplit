package com.peersplit.app.util

import java.io.FilterInputStream
import java.io.InputStream

/** Count bytes actually read, including decompressed ZIP/GZIP data. */
class BoundedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    private fun record(n: Int): Int {
        if (n > 0) {
            count += n
            require(count <= limit) { "File exceeds the supported size limit" }
        }
        return n
    }
    override fun read(): Int = super.read().also { if (it != -1) record(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = record(`in`.read(buffer, offset, length))
    override fun skip(n: Long): Long {
        val buffer = ByteArray(minOf(n.coerceAtLeast(0), 8192).toInt())
        var skipped = 0L
        while (skipped < n) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (read < 0) break
            skipped += read
        }
        return skipped
    }
}
