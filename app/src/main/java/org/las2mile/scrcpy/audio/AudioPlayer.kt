package org.las2mile.scrcpy.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayer(private val codecId: Int) {

    fun interface AudioPcmListener {
        fun onPcmData(pcm: ByteArray, offset: Int, length: Int)
    }

    private var audioTrack: AudioTrack? = null
    private var audioDecoder: MediaCodec? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var pcmListener: AudioPcmListener? = null

    fun setPcmListener(listener: AudioPcmListener?) {
        this.pcmListener = listener
    }

    fun start() {
        val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = Math.max(minBufferSize, 16384)

        audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
            )
        }

        audioTrack?.play()
        isRunning.set(true)
    }

    private fun initDecoderWithCsd(csd0Bytes: ByteArray?) {
        if (audioDecoder != null) {
            try {
                audioDecoder?.stop()
                audioDecoder?.release()
            } catch (ignored: Exception) {
            }
            audioDecoder = null
        }

        val mime = when (codecId) {
            CODEC_OPUS -> "audio/opus"
            CODEC_AAC -> "audio/mp4a-latm"
            else -> "audio/flac"
        }
        try {
            var channels = if (csd0Bytes != null && csd0Bytes.size > 9) (csd0Bytes[9].toInt() and 0xFF) else 2
            if (channels <= 0 || channels > 8) channels = 2

            val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE, channels)
            if (csd0Bytes != null) {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0Bytes))
            }

            if (codecId == CODEC_OPUS) {
                // csd-1: codec delay in nanoseconds (default 6500000L = 6.5ms)
                val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                csd1.putLong(6500000L)
                csd1.flip()
                format.setByteBuffer("csd-1", csd1)

                // csd-2: seek preroll in nanoseconds (default 80000000L = 80ms)
                val csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
                csd2.putLong(80000000L)
                csd2.flip()
                format.setByteBuffer("csd-2", csd2)
            }

            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            audioDecoder = decoder
            Log.d(TAG, "$mime decoder initialized with CSD successfully! (channels=$channels)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize $mime decoder with CSD", e)
            if (audioDecoder != null) {
                try {
                    audioDecoder?.release()
                } catch (ignored: Exception) {
                }
                audioDecoder = null
            }
        }
    }

    @Synchronized
    fun playSample(data: ByteArray, pts: Long, isConfig: Boolean) {
        val track = audioTrack
        if (!isRunning.get() || track == null) {
            return
        }

        if (codecId == CODEC_RAW) {
            try {
                track.write(data, 0, data.size)
                pcmListener?.onPcmData(data, 0, data.size)
            } catch (ignored: Exception) {
            }
            return
        }

        if (isConfig) {
            initDecoderWithCsd(data)
            return
        }

        val decoder = audioDecoder ?: return

        try {
            val inputIndex = decoder.dequeueInputBuffer(10000)
            if (inputIndex >= 0) {
                @Suppress("DEPRECATION")
                val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    decoder.getInputBuffer(inputIndex)
                } else {
                    decoder.inputBuffers[inputIndex]
                }
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(data)
                    decoder.queueInputBuffer(inputIndex, 0, data.size, pts, 0)
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            while (outputIndex >= 0) {
                @Suppress("DEPRECATION")
                val outputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    decoder.getOutputBuffer(outputIndex)
                } else {
                    decoder.outputBuffers[outputIndex]
                }
                if (outputBuffer != null && bufferInfo.size > 0) {
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val pcm = ByteArray(bufferInfo.size)
                    outputBuffer.get(pcm)
                    audioTrack?.write(pcm, 0, pcm.size)
                    pcmListener?.onPcmData(pcm, 0, pcm.size)
                }
                decoder.releaseOutputBuffer(outputIndex, false)
                outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "MediaCodec invalid state, disposing decoder: ${e.message}")
            if (audioDecoder != null) {
                try {
                    audioDecoder?.release()
                } catch (ignored: Exception) {
                }
                audioDecoder = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding audio sample", e)
        }
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        audioDecoder?.let {
            try {
                it.stop()
            } catch (ignored: Exception) {
            }
            try {
                it.release()
            } catch (ignored: Exception) {
            }
            audioDecoder = null
        }
        audioTrack?.let {
            try {
                it.stop()
            } catch (ignored: Exception) {
            }
            try {
                it.release()
            } catch (ignored: Exception) {
            }
            audioTrack = null
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"

        const val CODEC_RAW = 0x00726177
        const val CODEC_OPUS = 0x6F707573
        const val CODEC_AAC = 0x00616163
        const val CODEC_FLAC = 0x666C6163

        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}

