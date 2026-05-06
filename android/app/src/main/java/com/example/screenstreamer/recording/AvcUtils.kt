package com.example.screenstreamer.recording

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object AvcUtils {
    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun byteBufferToArray(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }

    fun csdToAnnexB(csd0: ByteArray?, csd1: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        if (csd0 != null && csd0.isNotEmpty()) {
            out.write(startCode)
            out.write(stripStartCode(csd0))
        }
        if (csd1 != null && csd1.isNotEmpty()) {
            out.write(startCode)
            out.write(stripStartCode(csd1))
        }
        return out.toByteArray()
    }

    fun sampleToAnnexB(sample: ByteArray, csd0: ByteArray?, csd1: ByteArray?, keyFrame: Boolean): ByteArray {
        val body = if (hasStartCode(sample)) sample else convertLengthPrefixed(sample)
        if (!keyFrame) return body
        val config = csdToAnnexB(csd0, csd1)
        if (config.isEmpty()) return body
        return ByteArrayOutputStream().apply {
            write(config)
            write(body)
        }.toByteArray()
    }

    private fun convertLengthPrefixed(sample: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        while (offset + 4 <= sample.size) {
            val length = ((sample[offset].toInt() and 0xFF) shl 24) or
                ((sample[offset + 1].toInt() and 0xFF) shl 16) or
                ((sample[offset + 2].toInt() and 0xFF) shl 8) or
                (sample[offset + 3].toInt() and 0xFF)
            if (length <= 0 || offset + 4 + length > sample.size) {
                return sample
            }
            out.write(startCode)
            out.write(sample, offset + 4, length)
            offset += 4 + length
        }
        return if (out.size() == 0) sample else out.toByteArray()
    }

    private fun hasStartCode(sample: ByteArray): Boolean {
        return sample.size >= 4 &&
            sample[0] == 0.toByte() &&
            sample[1] == 0.toByte() &&
            sample[2] == 0.toByte() &&
            sample[3] == 1.toByte()
    }

    private fun stripStartCode(bytes: ByteArray): ByteArray {
        if (hasStartCode(bytes)) return bytes.copyOfRange(4, bytes.size)
        if (bytes.size >= 3 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte()) {
            return bytes.copyOfRange(3, bytes.size)
        }
        return bytes
    }
}
