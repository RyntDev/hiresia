package com.hiresia.audioplayer.engine

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import android.util.Log
import com.hiresia.audioplayer.model.AudioTrack
import com.hiresia.audioplayer.native.AudioUtilsJNI
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

object AudioDecoder {
    private const val TAG = "AudioDecoder"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var decodeJob: Job? = null
    private val decodeMutex = Mutex()
    @Volatile private var isDecoding = false
    @Volatile private var isPaused = false
    @Volatile private var seekRequestedMs: Long? = null

    private var currentTrack: AudioTrack? = null

    fun isRunning(): Boolean = decodeJob?.isActive == true

    fun playTrack(
        track: AudioTrack,
        startPositionMs: Long = 0L,
        startPaused: Boolean = false,
        onTrackComplete: () -> Unit
    ) {
        isDecoding = false
        val previousJob = decodeJob
        previousJob?.cancel()

        decodeJob = scope.launch {
            previousJob?.join()
            decodeMutex.withLock {
                currentTrack = track
                isDecoding = true
                isPaused = startPaused
                seekRequestedMs = if (startPositionMs > 0L) startPositionMs else null

                try {
                    val isContentUri = track.path.startsWith("content://")
                    val file = if (!isContentUri) File(track.path) else null
                    val canRead = if (isContentUri) true else (file != null && file.exists() && file.canRead())

                    if (canRead) {
                        if (isNativeLosslessCandidate(track) && decodeNativeLossless(track, onTrackComplete)) {
                            return@withLock
                        }
                        if (!isDecoding || !isActive) return@withLock
                        Log.i(TAG, "Decoding media file via MediaCodec (bit-exact int32): ${track.path}")
                        decodeRealFile(track, onTrackComplete)
                    } else {
                        Log.i(TAG, "Generating high-fidelity audiophile synthesizer for demo: ${track.title}")
                        generateSyntheticStream(track, onTrackComplete)
                    }
                } catch (e: CancellationException) {
                    Log.d(TAG, "Decode coroutine cancelled normally for ${track.title}")
                } catch (e: Exception) {
                    Log.e(TAG, "Playback decode error, falling back to harmonic generator", e)
                    try {
                        if (isDecoding && isActive) {
                            generateSyntheticStream(track, onTrackComplete)
                        }
                    } catch (ignored: Exception) {}
                }
            }
        }
    }

    fun pause() {
        isPaused = true
    }

    fun resume() {
        isPaused = false
    }

    fun seekTo(positionMs: Long) {
        seekRequestedMs = positionMs
    }

    fun stop() {
        isDecoding = false
        isPaused = false
        seekRequestedMs = null
        decodeJob?.cancel()
        decodeJob = null
    }

    suspend fun stopAndJoin() {
        isDecoding = false
        isPaused = false
        seekRequestedMs = null
        val job = decodeJob
        decodeJob = null
        job?.cancelAndJoin()
    }

    private fun isNativeLosslessCandidate(track: AudioTrack): Boolean {
        if (track.isDsd) return true
        val lowerPath = (track.rawPath.ifEmpty { track.path }).lowercase()
        val lowerTitle = track.title.lowercase()
        val nativeExts = arrayOf(
            ".flac", ".wav", ".dsf", ".dff",
            ".aiff", ".aif", ".alac", ".m4a",
            ".wv", ".tta", ".ogg", ".mp3"
        )
        return nativeExts.any { lowerPath.endsWith(it) || lowerTitle.endsWith(it) }
    }

    private suspend fun decodeNativeLossless(track: AudioTrack, onTrackComplete: () -> Unit): Boolean {
        var pfd: ParcelFileDescriptor? = null
        var opened = false
        try {
            val hint = track.rawPath.ifEmpty { track.path }
            if (track.path.startsWith("content://")) {
                val uri = android.net.Uri.parse(track.path)
                pfd = com.hiresia.audioplayer.HiresiaApplication.instance.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    opened = AudioUtilsJNI.AudioServer_nativeDecoderOpenFd(pfd.fd, hint)
                }
                if (!opened && track.rawPath.isNotEmpty() && File(track.rawPath).canRead()) {
                    opened = AudioUtilsJNI.AudioServer_nativeDecoderOpenPath(track.rawPath)
                }
            } else {
                opened = AudioUtilsJNI.AudioServer_nativeDecoderOpenPath(track.path)
            }

            if (!opened) {
                return false
            }

            val nativeSr = AudioUtilsJNI.AudioServer_nativeDecoderGetSampleRate()
            val nativeBits = AudioUtilsJNI.AudioServer_nativeDecoderGetBitDepth()
            val nativeCh = AudioUtilsJNI.AudioServer_nativeDecoderGetChannels()
            val nativeIsDsd = AudioUtilsJNI.AudioServer_nativeDecoderIsDsd()
            val nativeDsdRate = AudioUtilsJNI.AudioServer_nativeDecoderGetDsdRate()

            Log.i(TAG, "Native C++ Lossless Decoder ACTIVE: ${track.title} ($nativeSr Hz, $nativeBits-bit, $nativeCh ch, DSD=$nativeIsDsd / DSD$nativeDsdRate)")

            // Sync exact decoded native format to AudioEngine & UI (preserves 24-bit/32-bit/192kHz+/DoP!)
            if (nativeSr > 0) {
                val effectiveRate = if (nativeIsDsd && AudioUtilsJNI.AudioServer_getDsdMode() == 0) {
                    176400
                } else {
                    nativeSr
                }
                AudioUtilsJNI.AudioServer_updateAudioFormat(effectiveRate, nativeBits, nativeCh)
                withContext(Dispatchers.Main) {
                    AudioPlayerController.updateCurrentTrackFormat(effectiveRate, nativeBits, nativeCh)
                }
            }

            // Apply any initial restore seek BEFORE pausing/decoding so resume plays from exact saved timestamp
            seekRequestedMs?.let { initialTargetMs ->
                seekRequestedMs = null
                if (initialTargetMs > 0L) {
                    AudioUtilsJNI.AudioServer_nativeDecoderSeek(initialTargetMs)
                }
            }

            var isEos = false
            while (currentCoroutineContext().isActive && isDecoding && !isEos) {
                if (isPaused) {
                    delay(25)
                    continue
                }

                // Backpressure: keep ~1s buffered in native int32 ring buffer
                while (currentCoroutineContext().isActive && isDecoding && !isPaused && AudioUtilsJNI.AudioServer_getQueuedFrames() > 48000) {
                    delay(15)
                }

                seekRequestedMs?.let { targetMs ->
                    seekRequestedMs = null
                    AudioUtilsJNI.AudioServer_nativeDecoderSeek(targetMs)
                }

                if (!currentCoroutineContext().isActive || !isDecoding) break
                val framesDecoded = AudioUtilsJNI.AudioServer_nativeDecoderReadAndQueue(2048)
                if (framesDecoded <= 0) {
                    isEos = true
                }
            }

            // Drain remaining audio in ring buffer cleanly
            var drainWaitMs = 0
            while (currentCoroutineContext().isActive && isDecoding && !isPaused && AudioUtilsJNI.AudioServer_getQueuedFrames() > 32 && drainWaitMs < 2500) {
                delay(30)
                drainWaitMs += 30
            }

            if (currentCoroutineContext().isActive && isEos && isDecoding) {
                withContext(Dispatchers.Main) {
                    onTrackComplete()
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Native lossless decoder error for ${track.title}, falling back to MediaCodec: ${e.message}")
            return false
        } finally {
            try { AudioUtilsJNI.AudioServer_nativeDecoderClose() } catch (ignored: Exception) {}
            try { pfd?.close() } catch (ignored: Exception) {}
        }
    }

    private suspend fun decodeRealFile(track: AudioTrack, onTrackComplete: () -> Unit) {
        var pfd: ParcelFileDescriptor? = null
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            if (track.path.startsWith("content://")) {
                val uri = android.net.Uri.parse(track.path)
                try {
                    extractor.setDataSource(com.hiresia.audioplayer.HiresiaApplication.instance, uri, null)
                } catch (e: Exception) {
                    try {
                        pfd = com.hiresia.audioplayer.HiresiaApplication.instance.contentResolver.openFileDescriptor(uri, "r")
                        if (pfd != null) {
                            extractor.setDataSource(pfd.fileDescriptor)
                        } else if (track.rawPath.isNotEmpty()) {
                            extractor.setDataSource(track.rawPath)
                        }
                    } catch (e2: Exception) {
                        if (track.rawPath.isNotEmpty() && File(track.rawPath).canRead()) {
                            extractor.setDataSource(track.rawPath)
                        } else {
                            throw e2
                        }
                    }
                }
            } else {
                extractor.setDataSource(track.path)
            }
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                Log.w(TAG, "No audio track found in ${track.path}, falling back to synth")
                generateSyntheticStream(track, onTrackComplete)
                return
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isInputEos = false
            var isOutputEos = false

            var decoderChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else track.channels.coerceAtLeast(1)

            var decoderSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else track.sampleRate

            // Standard Android MediaCodec decoder output default is 16-bit integer PCM
            var decoderEncoding = 2 // AudioFormat.ENCODING_PCM_16BIT

            // If real file sample rate differs from what AudioEngine was prepared with, update immediately!
            if (decoderSampleRate > 0 && decoderSampleRate != track.sampleRate) {
                val bitDepth = if (track.bitDepth >= 24) track.bitDepth else 16
                Log.i(TAG, "Audio file native rate ($decoderSampleRate Hz) differs from track (${track.sampleRate} Hz). Syncing AudioEngine!")
                AudioUtilsJNI.AudioServer_updateAudioFormat(decoderSampleRate, bitDepth, decoderChannels)
                withContext(Dispatchers.Main) {
                    AudioPlayerController.updateCurrentTrackFormat(decoderSampleRate, bitDepth, decoderChannels)
                }
            }

            // Apply any initial restore seek BEFORE pausing/decoding so resume plays from exact saved timestamp
            seekRequestedMs?.let { initialTargetMs ->
                seekRequestedMs = null
                if (initialTargetMs > 0L) {
                    extractor.seekTo(initialTargetMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                }
            }

            val intPcmBuffer = IntArray(4096 * 2)

            while (currentCoroutineContext().isActive && isDecoding && !isOutputEos) {
                if (isPaused) {
                    delay(25)
                    continue
                }

                // If audio ring buffer has plenty of frames (> 48,000 frames), wait gently
                while (currentCoroutineContext().isActive && isDecoding && !isPaused && AudioUtilsJNI.AudioServer_getQueuedFrames() > 48000) {
                    delay(15)
                }

                // Handle Seek
                seekRequestedMs?.let { targetMs ->
                    seekRequestedMs = null
                    extractor.seekTo(targetMs * 1000L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    codec.flush()
                    isInputEos = false
                }

                // Feed input buffers
                if (!isInputEos) {
                    val inputBufIndex = codec.dequeueInputBuffer(5000L)
                    if (inputBufIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIndex)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputBufIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputEos = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inputBufIndex, 0, sampleSize, pts, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain output buffers
                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, 5000L)
                if (outputBufIndex >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEos = true
                    }

                    if (bufferInfo.size > 0) {
                        val outBuf = codec.getOutputBuffer(outputBufIndex)
                        if (outBuf != null) {
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)

                            var bytesPerSample = when (decoderEncoding) {
                                4 -> 4   // AudioFormat.ENCODING_PCM_FLOAT
                                22 -> 4  // AudioFormat.ENCODING_PCM_32BIT
                                21 -> 3  // AudioFormat.ENCODING_PCM_24BIT_PACKED
                                else -> 2 // AudioFormat.ENCODING_PCM_16BIT
                            }

                            // Sanity check: buffer size must be divisible by frame size
                            if (decoderChannels > 0 && (bufferInfo.size % (decoderChannels * bytesPerSample)) != 0) {
                                if (bufferInfo.size % (decoderChannels * 2) == 0) {
                                    bytesPerSample = 2
                                    decoderEncoding = 2
                                }
                            }

                            val frameBytes = (decoderChannels * bytesPerSample).coerceAtLeast(1)
                            var intIdx = 0

                            // Fully drain outBuf directly into bit-exact left-justified int32 PCM!
                            while (outBuf.remaining() >= frameBytes) {
                                if (intIdx + 2 > intPcmBuffer.size) {
                                    AudioUtilsJNI.AudioServer_queueAudioInt32(intPcmBuffer, intIdx / 2)
                                    intIdx = 0
                                }

                                val left: Int = when {
                                    bytesPerSample == 4 && decoderEncoding == 4 -> {
                                        (outBuf.float.coerceIn(-1.0f, 1.0f).toDouble() * 2147483647.0).toInt()
                                    }
                                    bytesPerSample == 4 -> outBuf.int
                                    bytesPerSample == 3 -> {
                                        val b0 = outBuf.get().toInt() and 0xFF
                                        val b1 = outBuf.get().toInt() and 0xFF
                                        val b2 = outBuf.get().toInt() and 0xFF
                                        (b2 shl 24) or (b1 shl 16) or (b0 shl 8)
                                    }
                                    else -> outBuf.short.toInt() shl 16
                                }

                                val right: Int = if (decoderChannels >= 2) {
                                    when {
                                        bytesPerSample == 4 && decoderEncoding == 4 -> {
                                            (outBuf.float.coerceIn(-1.0f, 1.0f).toDouble() * 2147483647.0).toInt()
                                        }
                                        bytesPerSample == 4 -> outBuf.int
                                        bytesPerSample == 3 -> {
                                            val b0 = outBuf.get().toInt() and 0xFF
                                            val b1 = outBuf.get().toInt() and 0xFF
                                            val b2 = outBuf.get().toInt() and 0xFF
                                            (b2 shl 24) or (b1 shl 16) or (b0 shl 8)
                                        }
                                        else -> outBuf.short.toInt() shl 16
                                    }
                                } else {
                                    left
                                }

                                if (decoderChannels > 2) {
                                    val skipBytes = (decoderChannels - 2) * bytesPerSample
                                    if (outBuf.remaining() >= skipBytes) {
                                        outBuf.position(outBuf.position() + skipBytes)
                                    }
                                }

                                intPcmBuffer[intIdx++] = left
                                intPcmBuffer[intIdx++] = right
                            }

                            if (intIdx > 0) {
                                AudioUtilsJNI.AudioServer_queueAudioInt32(intPcmBuffer, intIdx / 2)
                                intIdx = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputBufIndex, false)
                } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Log.i(TAG, "Decoder output format changed: $newFormat")
                    var formatChanged = false
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        val newCh = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (newCh != decoderChannels && newCh > 0) {
                            decoderChannels = newCh
                            formatChanged = true
                        }
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        val newEnc = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        if (newEnc != decoderEncoding && newEnc > 0) {
                            decoderEncoding = newEnc
                            formatChanged = true
                        }
                    } else if (newFormat.containsKey("bits-per-sample")) {
                        val bps = newFormat.getInteger("bits-per-sample")
                        val newEnc = when (bps) {
                            24 -> 21
                            32 -> 22
                            else -> 2
                        }
                        if (newEnc != decoderEncoding) {
                            decoderEncoding = newEnc
                            formatChanged = true
                        }
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val newSr = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (newSr > 0 && newSr != decoderSampleRate) {
                            decoderSampleRate = newSr
                            formatChanged = true
                        }
                    }
                    if (formatChanged) {
                        val bitDepth = when (decoderEncoding) {
                            21 -> 24
                            22, 4 -> 32
                            else -> if (track.bitDepth >= 24) track.bitDepth else 16
                        }
                        Log.i(TAG, "Syncing AudioEngine format: $decoderSampleRate Hz, $bitDepth-bit, $decoderChannels ch")
                        AudioUtilsJNI.AudioServer_updateAudioFormat(decoderSampleRate, bitDepth, decoderChannels)
                        withContext(Dispatchers.Main) {
                            AudioPlayerController.updateCurrentTrackFormat(decoderSampleRate, bitDepth, decoderChannels)
                        }
                    }
                } else if (outputBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    delay(2)
                }
            }

            // Wait for remaining audio in the ring buffer to finish playing
            var drainWaitMs = 0
            while (currentCoroutineContext().isActive && isDecoding && !isPaused && AudioUtilsJNI.AudioServer_getQueuedFrames() > 32 && drainWaitMs < 2000) {
                delay(30)
                drainWaitMs += 30
            }

            if (currentCoroutineContext().isActive && isOutputEos && isDecoding) {
                withContext(Dispatchers.Main) {
                    onTrackComplete()
                }
            }

        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {}
            try {
                extractor.release()
            } catch (e: Exception) {}
            try {
                pfd?.close()
            } catch (e: Exception) {}
        }
    }

    private suspend fun generateSyntheticStream(track: AudioTrack, onTrackComplete: () -> Unit) {
        val sampleRate = if (track.sampleRate in 44100..384000) track.sampleRate else 48000
        val blockSize = 512
        val floatBuf = FloatArray(blockSize * 2)

        var sampleIndex = 0L
        val totalSamples = (track.durationMs * sampleRate) / 1000L

        // Musical frequencies (Harmonious Audiophile Chords: F#min7 / A Major / E chord)
        val chordFreqs = when (track.id.toInt() % 4) {
            0 -> floatArrayOf(220.0f, 277.18f, 329.63f, 440.0f) // A Major
            1 -> floatArrayOf(185.0f, 220.0f, 277.18f, 370.0f)  // F# Minor
            2 -> floatArrayOf(196.0f, 246.94f, 293.66f, 392.0f) // G Major
            else -> floatArrayOf(164.81f, 207.65f, 246.94f, 329.63f) // E Major
        }

        while (currentCoroutineContext().isActive && isDecoding && (totalSamples <= 0 || sampleIndex < totalSamples)) {
            if (isPaused) {
                seekRequestedMs?.let { targetMs ->
                    seekRequestedMs = null
                    sampleIndex = (targetMs * sampleRate) / 1000L
                }
                delay(20)
                continue
            }

            seekRequestedMs?.let { targetMs ->
                seekRequestedMs = null
                sampleIndex = (targetMs * sampleRate) / 1000L
            }

            var bufIdx = 0
            for (f in 0 until blockSize) {
                val t = (sampleIndex + f).toDouble() / sampleRate
                val envelope = 0.85 + 0.15 * sin(2.0 * Math.PI * 0.25 * t)

                var sigL = 0.0
                var sigR = 0.0

                for (i in chordFreqs.indices) {
                    val freq = chordFreqs[i]
                    val pan = 0.3 * sin(2.0 * Math.PI * 0.1 * t + i)
                    val note = sin(2.0 * Math.PI * freq * t) * (0.22 / (i + 1))
                    sigL += note * (0.5 - pan)
                    sigR += note * (0.5 + pan)
                }

                floatBuf[bufIdx++] = (sigL * envelope).toFloat().coerceIn(-1.0f, 1.0f)
                floatBuf[bufIdx++] = (sigR * envelope).toFloat().coerceIn(-1.0f, 1.0f)
            }

            sampleIndex += blockSize
            AudioUtilsJNI.AudioServer_queueAudioFloat(floatBuf, blockSize)

            // Pacing delay matched to current sample rate
            val delayMs = ((blockSize * 1000L) / sampleRate).coerceAtLeast(1L)
            delay(delayMs)
        }

        // Wait for remaining audio to play out
        var drainWaitMs = 0
        while (currentCoroutineContext().isActive && isDecoding && !isPaused && AudioUtilsJNI.AudioServer_getQueuedFrames() > 32 && drainWaitMs < 2000) {
            delay(30)
            drainWaitMs += 30
        }

        if (currentCoroutineContext().isActive && isDecoding && sampleIndex >= totalSamples && totalSamples > 0) {
            withContext(Dispatchers.Main) {
                onTrackComplete()
            }
        }
    }
}
