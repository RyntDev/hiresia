package com.hiresia.audioplayer.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

data class ReplayGainInfo(
    val trackGainDb: Float = 0.0f,
    val albumGainDb: Float = 0.0f,
    val trackPeak: Float = 1.0f,
    val albumPeak: Float = 1.0f,
    val hasTags: Boolean = false
)

object ReplayGainParser {
    private const val TAG = "ReplayGainParser"
    private const val MAX_ID3_TAG_BYTES = 512 * 1024
    private const val MAX_VORBIS_BLOCK_BYTES = 256 * 1024

    fun parse(context: Context, path: String, rawPath: String = ""): ReplayGainInfo {
        return try {
            openStream(context, path, rawPath)?.use { input ->
                parseStream(input)
            } ?: ReplayGainInfo()
        } catch (e: Exception) {
            Log.d(TAG, "ReplayGain parse skipped: ${e.message}")
            ReplayGainInfo()
        }
    }

    private fun openStream(context: Context, path: String, rawPath: String): InputStream? {
        return try {
            if (path.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(path))
            } else {
                val f = File(path).takeIf { it.exists() && it.canRead() }
                    ?: rawPath.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() && it.canRead() }
                f?.inputStream()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseStream(input: InputStream): ReplayGainInfo {
        val header = readExact(input, 10) ?: return ReplayGainInfo()
        return when {
            header[0] == 'f'.code.toByte() &&
                header[1] == 'L'.code.toByte() &&
                header[2] == 'a'.code.toByte() &&
                header[3] == 'C'.code.toByte() -> parseFlac(input, header)

            header[0] == 'I'.code.toByte() &&
                header[1] == 'D'.code.toByte() &&
                header[2] == '3'.code.toByte() -> parseId3v2(input, header)

            else -> ReplayGainInfo()
        }
    }

    private fun parseFlac(input: InputStream, first10: ByteArray): ReplayGainInfo {
        // first10[0..3] = "fLaC", first10[4..7] = first metadata block header, first10[8..9] = first 2 bytes of payload
        var blockHeader = byteArrayOf(first10[4], first10[5], first10[6], first10[7])
        var firstPayloadPrefix = byteArrayOf(first10[8], first10[9])
        var isFirstBlock = true

        while (true) {
            val headerByte0 = blockHeader[0].toInt() and 0xFF
            val isLast = (headerByte0 and 0x80) != 0
            val blockType = headerByte0 and 0x7F
            val length = ((blockHeader[1].toInt() and 0xFF) shl 16) or
                ((blockHeader[2].toInt() and 0xFF) shl 8) or
                (blockHeader[3].toInt() and 0xFF)

            if (length < 0 || length > MAX_VORBIS_BLOCK_BYTES) {
                return ReplayGainInfo()
            }

            if (blockType == 4) { // VORBIS_COMMENT
                val payload = ByteArray(length)
                var offset = 0
                if (isFirstBlock) {
                    val copyLen = minOf(2, length)
                    System.arraycopy(firstPayloadPrefix, 0, payload, 0, copyLen)
                    offset = copyLen
                }
                if (offset < length && !readInto(input, payload, offset, length - offset)) {
                    return ReplayGainInfo()
                }
                return parseVorbisCommentPayload(payload)
            } else {
                val skipBytes = if (isFirstBlock) (length - 2).coerceAtLeast(0) else length
                isFirstBlock = false
                if (!skipFully(input, skipBytes.toLong())) return ReplayGainInfo()
            }

            if (isLast) break
            blockHeader = readExact(input, 4) ?: break
        }
        return ReplayGainInfo()
    }

    private fun parseVorbisCommentPayload(payload: ByteArray): ReplayGainInfo {
        var pos = 0
        fun readLeInt(): Int? {
            if (pos + 4 > payload.size) return null
            val v = (payload[pos].toInt() and 0xFF) or
                ((payload[pos + 1].toInt() and 0xFF) shl 8) or
                ((payload[pos + 2].toInt() and 0xFF) shl 16) or
                ((payload[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        val vendorLen = readLeInt() ?: return ReplayGainInfo()
        if (vendorLen < 0 || pos + vendorLen > payload.size) return ReplayGainInfo()
        pos += vendorLen

        val userCommentCount = readLeInt() ?: return ReplayGainInfo()
        if (userCommentCount < 0 || userCommentCount > 4096) return ReplayGainInfo()

        val acc = ReplayGainAccumulator()
        for (i in 0 until userCommentCount) {
            val len = readLeInt() ?: break
            if (len < 0 || pos + len > payload.size) break
            val comment = String(payload, pos, len, StandardCharsets.UTF_8)
            pos += len
            val eqIdx = comment.indexOf('=')
            if (eqIdx > 0) {
                val key = comment.substring(0, eqIdx).trim()
                val value = comment.substring(eqIdx + 1).trim()
                acc.consume(key, value)
            }
        }
        return acc.build()
    }

    private fun parseId3v2(input: InputStream, header: ByteArray): ReplayGainInfo {
        val majorVersion = header[3].toInt() and 0xFF
        val flags = header[5].toInt() and 0xFF
        val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
            ((header[7].toInt() and 0x7F) shl 14) or
            ((header[8].toInt() and 0x7F) shl 7) or
            (header[9].toInt() and 0x7F)
        if (tagSize <= 0 || majorVersion < 3) return ReplayGainInfo()

        val bytesToRead = minOf(tagSize, MAX_ID3_TAG_BYTES)
        val tagData = ByteArray(bytesToRead)
        if (!readInto(input, tagData, 0, bytesToRead)) return ReplayGainInfo()

        var pos = 0
        if (majorVersion == 3 && (flags and 0x40) != 0 && pos + 4 <= tagData.size) {
            val extSize = readBeInt(tagData, pos)
            pos += 4 + extSize.coerceAtLeast(0)
        } else if (majorVersion >= 4 && (flags and 0x40) != 0 && pos + 4 <= tagData.size) {
            val extSize = readSynchsafeInt(tagData, pos)
            pos += extSize.coerceAtLeast(4)
        }

        val acc = ReplayGainAccumulator()
        while (pos + 10 <= tagData.size) {
            if (tagData[pos].toInt() == 0) break // padding
            val frameId = String(tagData, pos, 4, StandardCharsets.ISO_8859_1)
            val frameSize = if (majorVersion >= 4) {
                readSynchsafeInt(tagData, pos + 4)
            } else {
                readBeInt(tagData, pos + 4)
            }
            pos += 10
            if (frameSize <= 0 || pos + frameSize > tagData.size) break

            if (frameId == "TXXX") {
                val encodingByte = tagData[pos].toInt() and 0xFF
                val textBytes = tagData.copyOfRange(pos + 1, pos + frameSize)
                val (desc, value) = decodeId3Txxx(encodingByte, textBytes)
                if (desc.isNotEmpty() && value.isNotEmpty()) {
                    acc.consume(desc, value)
                }
            }
            pos += frameSize
        }
        return acc.build()
    }

    private fun decodeId3Txxx(encoding: Int, data: ByteArray): Pair<String, String> {
        if (data.isEmpty()) return "" to ""
        return when (encoding) {
            0 -> splitSingleByte(data, StandardCharsets.ISO_8859_1)
            3 -> splitSingleByte(data, StandardCharsets.UTF_8)
            1 -> splitUtf16(data, withBom = true)
            2 -> splitUtf16(data, withBom = false)
            else -> "" to ""
        }
    }

    private fun splitSingleByte(data: ByteArray, charset: Charset): Pair<String, String> {
        val nulIdx = data.indexOf(0.toByte())
        if (nulIdx < 0) return "" to ""
        val key = String(data, 0, nulIdx, charset).trim()
        val valStart = nulIdx + 1
        var valEnd = data.indexOfFrom(0.toByte(), valStart)
        if (valEnd < 0) valEnd = data.size
        val value = String(data, valStart, (valEnd - valStart).coerceAtLeast(0), charset).trim()
        return key to value
    }

    private fun splitUtf16(data: ByteArray, withBom: Boolean): Pair<String, String> {
        var idx = 0
        while (idx + 1 < data.size) {
            if (data[idx].toInt() == 0 && data[idx + 1].toInt() == 0) break
            idx += 2
        }
        if (idx + 1 >= data.size) return "" to ""
        val charset = if (withBom) StandardCharsets.UTF_16 else StandardCharsets.UTF_16BE
        val key = String(data, 0, idx, charset).trim('\u0000', ' ')
        val valStart = idx + 2
        val value = if (valStart < data.size) {
            String(data, valStart, data.size - valStart, charset).trim('\u0000', ' ')
        } else ""
        return key to value
    }

    private class ReplayGainAccumulator {
        var trackGain = 0.0f
        var albumGain = 0.0f
        var trackPeak = 1.0f
        var albumPeak = 1.0f
        var found = false

        fun consume(rawKey: String, rawValue: String) {
            val key = rawKey.trim().lowercase()
            val cleaned = rawValue.trim()
                .replace("db", "", ignoreCase = true)
                .replace(',', '.')
                .trim()
            val num = cleaned.toFloatOrNull() ?: return
            when (key) {
                "replaygain_track_gain" -> {
                    trackGain = num.coerceIn(-30.0f, 30.0f)
                    found = true
                }
                "replaygain_album_gain" -> {
                    albumGain = num.coerceIn(-30.0f, 30.0f)
                    found = true
                }
                "replaygain_track_peak" -> {
                    if (num > 0.0f && num < 4.0f) {
                        trackPeak = num
                        found = true
                    }
                }
                "replaygain_album_peak" -> {
                    if (num > 0.0f && num < 4.0f) {
                        albumPeak = num
                        found = true
                    }
                }
            }
        }

        fun build(): ReplayGainInfo {
            if (!found) return ReplayGainInfo()
            val effectiveAlbumGain = if (albumGain != 0.0f) albumGain else trackGain
            val effectiveAlbumPeak = if (albumPeak != 1.0f) albumPeak else trackPeak
            return ReplayGainInfo(
                trackGainDb = trackGain,
                albumGainDb = effectiveAlbumGain,
                trackPeak = trackPeak,
                albumPeak = effectiveAlbumPeak,
                hasTags = true
            )
        }
    }

    private fun readBeInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)
    }

    private fun readSynchsafeInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0x7F) shl 21) or
            ((buf[offset + 1].toInt() and 0x7F) shl 14) or
            ((buf[offset + 2].toInt() and 0x7F) shl 7) or
            (buf[offset + 3].toInt() and 0x7F)
    }

    private fun ByteArray.indexOfFrom(target: Byte, start: Int): Int {
        for (i in start until size) {
            if (this[i] == target) return i
        }
        return -1
    }

    private fun readExact(input: InputStream, count: Int): ByteArray? {
        val out = ByteArray(count)
        return if (readInto(input, out, 0, count)) out else null
    }

    private fun readInto(input: InputStream, dst: ByteArray, offset: Int, len: Int): Boolean {
        var total = 0
        while (total < len) {
            val n = input.read(dst, offset + total, len - total)
            if (n < 0) return false
            total += n
        }
        return true
    }

    private fun skipFully(input: InputStream, bytes: Long): Boolean {
        var rem = bytes
        while (rem > 0) {
            val skipped = input.skip(rem)
            if (skipped > 0) {
                rem -= skipped
            } else {
                val b = input.read()
                if (b < 0) return false
                rem -= 1
            }
        }
        return true
    }
}

