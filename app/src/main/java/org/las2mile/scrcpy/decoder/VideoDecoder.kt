package org.las2mile.scrcpy.decoder

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class VideoDecoder {

    private var mCodec: MediaCodec? = null
    private var mWorker: Worker? = null
    private val mIsConfigured = AtomicBoolean(false)
    private var mimeType = "video/avc"
    private var cachedCsd: ByteArray? = null

    fun setCodec(codecId: Int) {
        mimeType = when (codecId) {
            CODEC_H265 -> "video/hevc"
            CODEC_AV1 -> "video/av01"
            else -> "video/avc"
        }
    }

    fun configure(surface: Surface?, width: Int, height: Int) {
        mWorker?.configure(surface, width, height, mimeType)
    }

    fun setSurface(surface: Surface?, width: Int, height: Int) {
        mWorker?.setSurface(surface, width, height, mimeType)
    }

    fun decodeSample(data: ByteArray, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        mWorker?.decodeSample(data, offset, size, presentationTimeUs, flags)
    }

    fun start() {
        if (mWorker == null) {
            val worker = Worker()
            worker.setRunning(true)
            worker.start()
            mWorker = worker
        }
    }

    fun stop() {
        mWorker?.let {
            it.setRunning(false)
            mWorker = null
            mIsConfigured.set(false)
            mCodec?.let { codec ->
                try {
                    codec.stop()
                    codec.release()
                } catch (ignored: Exception) {
                }
                mCodec = null
            }
        }
    }

    private inner class Worker : Thread() {
        private val mIsRunning = AtomicBoolean(false)

        fun setRunning(isRunning: Boolean) {
            mIsRunning.set(isRunning)
        }

        @Synchronized
        fun setSurface(surface: Surface?, width: Int, height: Int, mime: String) {
            if (surface == null || !surface.isValid) {
                Log.w(TAG, "setSurface: surface is null or invalid")
                return
            }

            if (mCodec != null && mIsConfigured.get()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        mCodec?.setOutputSurface(surface)
                        Log.d(TAG, "mCodec.setOutputSurface succeeded dynamically!")
                        return
                    } catch (e: Exception) {
                        Log.w(TAG, "setOutputSurface failed, will re-configure", e)
                    }
                }
            }

            configure(surface, width, height, mime)
        }

        @Synchronized
        fun configure(surface: Surface?, width: Int, height: Int, mime: String) {
            if (surface == null || !surface.isValid) {
                Log.w(TAG, "Cannot configure VideoDecoder: surface is null or invalid")
                return
            }

            if (mIsConfigured.get() && mCodec != null) {
                mIsConfigured.set(false)
                try {
                    mCodec?.stop()
                    mCodec?.release()
                } catch (ignored: Exception) {
                }
                mCodec = null
            }

            try {
                val format = MediaFormat.createVideoFormat(mime, width, height)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                cachedCsd?.let {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(it))
                }
                val codec = MediaCodec.createDecoderByType(mime)
                codec.configure(format, surface, null, 0)
                codec.start()
                mCodec = codec
                mIsConfigured.set(true)
                Log.d(TAG, "VideoDecoder configured for $mime (${width}x$height)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create/configure video decoder for $mime", e)
                mCodec = null
                mIsConfigured.set(false)
            }
        }

        @Synchronized
        fun decodeSample(data: ByteArray, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            if ((flags and 2) != 0) { // BUFFER_FLAG_CODEC_CONFIG
                cachedCsd = data.copyOfRange(offset, offset + size)
            }

            val codec = mCodec
            if (!mIsConfigured.get() || !mIsRunning.get() || codec == null) {
                return
            }

            try {
                var index = codec.dequeueInputBuffer(10000)
                if (index < 0) {
                    index = codec.dequeueInputBuffer(20000)
                }
                if (index >= 0) {
                    @Suppress("DEPRECATION")
                    val buffer = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                        codec.inputBuffers[index]
                    } else {
                        codec.getInputBuffer(index)
                    }
                    if (buffer != null) {
                        buffer.clear()
                        buffer.put(data, offset, size)
                        codec.queueInputBuffer(index, 0, size, presentationTimeUs, flags)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error queuing input buffer", e)
            }
        }

        override fun run() {
            val info = MediaCodec.BufferInfo()
            while (mIsRunning.get()) {
                val codec = mCodec
                if (mIsConfigured.get() && codec != null) {
                    try {
                        val index = codec.dequeueOutputBuffer(info, 10000)
                        if (index >= 0) {
                            codec.releaseOutputBuffer(index, true)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    } catch (e: IllegalStateException) {
                        // Codec state transition
                    } catch (e: Exception) {
                        Log.e(TAG, "Error dequeuing output buffer", e)
                    }
                } else {
                    try {
                        sleep(10)
                    } catch (ignored: InterruptedException) {
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "VideoDecoder"

        const val CODEC_H264 = 0x68323634
        const val CODEC_H265 = 0x68323635
        const val CODEC_AV1 = 0x00617631
    }
}

