package com.example.screenstreamer.recording

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.example.screenstreamer.model.RecordingConfig
import com.example.screenstreamer.streaming.VideoPacketStreamer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MediaCodecScreenRecorder(
    private val context: Context,
    private val projection: MediaProjection,
    private val config: RecordingConfig,
    private val outputFile: File,
    private val streamer: VideoPacketStreamer?
) {
    private val running = AtomicBoolean(false)
    private val stopped = CountDownLatch(1)
    private val bufferInfo = MediaCodec.BufferInfo()
    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoderThread: Thread? = null
    private var displayThread: HandlerThread? = null
    private var videoTrackIndex = -1
    private var muxerStarted = false
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val format = MediaFormat.createVideoFormat(MIME_TYPE, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
            }
        }

        encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = encoder!!.createInputSurface()
        muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        displayThread = HandlerThread("screen-virtual-display").apply { start() }

        encoder!!.start()
        streamer?.start()
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenStreamRecorder",
            config.width,
            config.height,
            config.dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            Handler(displayThread!!.looper)
        )

        encoderThread = Thread(::drainEncoder, "screen-video-encoder").apply {
            start()
        }
    }

    fun stop(timeoutMs: Long = 5000) {
        if (!running.compareAndSet(true, false)) return
        try {
            encoder?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        stopped.await(timeoutMs, TimeUnit.MILLISECONDS)
        release()
    }

    fun requestSyncFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        try {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    private fun drainEncoder() {
        try {
            while (true) {
                val encoder = encoder ?: break
                val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!running.get()) {
                            break
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        handleFormatChanged(encoder.outputFormat)
                    }
                    outputIndex >= 0 -> {
                        handleEncodedBuffer(encoder, outputIndex)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            break
                        }
                    }
                }
            }
        } finally {
            stopped.countDown()
        }
    }

    private fun handleFormatChanged(format: MediaFormat) {
        csd0 = format.getOptionalByteArray("csd-0")
        csd1 = format.getOptionalByteArray("csd-1")
        muxer?.let {
            videoTrackIndex = it.addTrack(format)
            it.start()
            muxerStarted = true
        }
        streamer?.sendConfig(AvcUtils.csdToAnnexB(csd0, csd1))
    }

    private fun handleEncodedBuffer(encoder: MediaCodec, outputIndex: Int) {
        val encodedData = encoder.getOutputBuffer(outputIndex)
        if (encodedData == null) {
            encoder.releaseOutputBuffer(outputIndex, false)
            return
        }

        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            bufferInfo.size = 0
        }

        if (bufferInfo.size > 0 && muxerStarted) {
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)
            muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)

            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)
            val sample = AvcUtils.byteBufferToArray(encodedData)
            val keyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val annexB = AvcUtils.sampleToAnnexB(sample, csd0, csd1, keyFrame)
            streamer?.sendFrame(annexB, keyFrame, bufferInfo.presentationTimeUs)
        }

        encoder.releaseOutputBuffer(outputIndex, false)
    }

    private fun release() {
        try {
            virtualDisplay?.release()
        } catch (_: Exception) {
        }
        virtualDisplay = null

        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null

        try {
            muxer?.stop()
        } catch (_: Exception) {
        }
        try {
            muxer?.release()
        } catch (_: Exception) {
        }
        muxer = null

        try {
            inputSurface?.release()
        } catch (_: Exception) {
        }
        inputSurface = null

        displayThread?.quitSafely()
        displayThread = null
    }

    private fun MediaFormat.getOptionalByteArray(key: String): ByteArray? {
        return try {
            val buffer: ByteBuffer? = getByteBuffer(key)
            if (buffer == null) null else AvcUtils.byteBufferToArray(buffer)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val MIME_TYPE = "video/avc"
        private const val DEQUEUE_TIMEOUT_US = 10_000L
    }
}
