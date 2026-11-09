package org.las2mile.scrcpy

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import org.las2mile.scrcpy.audio.AudioPlayer
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ScreenRecorder : AudioPlayer.AudioPcmListener {

    interface RecordListener {
        fun onStarted()
        fun onStopped(filePath: String)
        fun onError(reason: String)
        fun onTick(elapsedSeconds: Long)
    }

    private val mIsRecording = AtomicBoolean(false)

    private var mCaptureThread: HandlerThread? = null
    private var mCaptureHandler: Handler? = null

    private var mVideoEncoder: MediaCodec? = null
    private var mInputSurface: Surface? = null
    private var mEglRenderer: EglSurfaceRenderer? = null

    private var mAudioEncoder: MediaCodec? = null
    private var mAudioEnabled = false

    private val mMuxerLock = Any()
    private var mMuxer: MediaMuxer? = null
    private var mVideoTrackIndex = -1
    private var mAudioTrackIndex = -1
    private var mMuxerStarted = false

    private var mCanvasWidth = 0
    private var mCanvasHeight = 0
    private var mRecordStartMs = 0L

    private var mSurfaceView: SurfaceView? = null
    private var mScrcpy: Scrcpy? = null
    private var mContext: Context? = null
    private var mListener: RecordListener? = null

    private var mMediaUri: Uri? = null
    private var mMediaOutputStream: OutputStream? = null
    private var mOutputFile: File? = null

    private val mMainHandler = Handler(Looper.getMainLooper())
    private var mFrameCount = 0L

    val isRecording: Boolean
        get() = mIsRecording.get()

    fun start(
        surfaceView: SurfaceView, width: Int, height: Int,
        context: Context, listener: RecordListener
    ) {
        start(surfaceView, width, height, null, context, listener)
    }

    fun start(
        surfaceView: SurfaceView, width: Int, height: Int,
        scrcpy: Scrcpy?, context: Context, listener: RecordListener
    ) {
        if (mIsRecording.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            listener.onError(context.getString(R.string.err_record_min_version))
            return
        }

        mSurfaceView = surfaceView
        mScrcpy = scrcpy
        mContext = context
        mListener = listener

        val svW = if (width > 0) width else surfaceView.width
        val svH = if (height > 0) height else surfaceView.height
        if (svW <= 0 || svH <= 0) {
            listener.onError(context.getString(R.string.err_invalid_surface_size))
            return
        }

        val maxDim = Math.max(svW, svH)
        var targetW = svW
        var targetH = svH
        if (maxDim > 1920) {
            val scale = 1920f / maxDim
            targetW = Math.round(svW * scale)
            targetH = Math.round(svH * scale)
        }
        mCanvasWidth = (targetW / 2) * 2
        mCanvasHeight = (targetH / 2) * 2

        Thread({ initAndRecord() }, "RecorderInit").start()
    }

    private fun initAndRecord() {
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "scrcpy_$timeStamp.mp4"

            // 1. Configure Video Encoder (Surface input)
            val videoFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, mCanvasWidth, mCanvasHeight
            ).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
            }

            val vEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mInputSurface = vEncoder.createInputSurface()
            vEncoder.start()
            mVideoEncoder = vEncoder

            // 2. Configure Audio Encoder
            val sc = mScrcpy
            mAudioEnabled = sc != null && (sc.isAudioActive || sc.isAudioEnabled)
            if (mAudioEnabled) {
                try {
                    val audioFormat = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS
                    ).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                    }

                    val aEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    aEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    aEncoder.start()
                    mAudioEncoder = aEncoder

                    feedAudioSilenceInitial()
                    sc?.setAudioPcmListener(this)
                    Log.d(TAG, "Audio recording enabled via AAC encoder")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to initialize AAC audio encoder, continuing video-only", e)
                    mAudioEnabled = false
                    mAudioEncoder?.let {
                        try { it.release() } catch (ignored: Exception) {}
                        mAudioEncoder = null
                    }
                }
            }

            // 3. Setup MediaMuxer
            val ctx = mContext ?: throw Exception("Context is null")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/scrcpy")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("MediaStore insert returned null")
                mMediaUri = uri
                mMediaOutputStream = ctx.contentResolver.openOutputStream(uri)

                val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw")
                    ?: throw Exception("Cannot open ParcelFileDescriptor")
                mMuxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "scrcpy"
                )
                if (!dir.exists()) dir.mkdirs()
                val outFile = File(dir, fileName)
                mOutputFile = outFile
                mMuxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            mVideoTrackIndex = -1
            mAudioTrackIndex = -1
            mMuxerStarted = false
            mFrameCount = 0
            mRecordStartMs = System.currentTimeMillis()
            mIsRecording.set(true)

            if (mAudioEnabled) {
                drainAudioEncoder(false)
            }

            // 4. Start Capture & GL Render Thread
            val captureThread = HandlerThread("ScreenRecorderGL")
            captureThread.start()
            mCaptureThread = captureThread
            val captureHandler = Handler(captureThread.looper)
            mCaptureHandler = captureHandler

            captureHandler.post {
                try {
                    val inputSurf = mInputSurface ?: throw Exception("Input surface is null")
                    mEglRenderer = EglSurfaceRenderer(inputSurf, mCanvasWidth, mCanvasHeight)
                    scheduleNextFrame()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize EGL renderer", e)
                    cleanup()
                    val err = mContext?.getString(R.string.err_gl_init_failed, e.message)
                        ?: "OpenGL init failed: ${e.message}"
                    mMainHandler.post { mListener?.onError(err) }
                }
            }

            mMainHandler.post { mListener?.onStarted() }

        } catch (e: Exception) {
            Log.e(TAG, "Recorder init failed", e)
            cleanup()
            val msg = e.message
            val err = mContext?.getString(R.string.err_record_init_failed, msg)
                ?: "Record init failed: $msg"
            mMainHandler.post { mListener?.onError(err) }
        }
    }

    private fun feedAudioSilenceInitial() {
        val encoder = mAudioEncoder ?: return
        try {
            val inputIndex = encoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val buf = encoder.getInputBuffer(inputIndex)
                if (buf != null) {
                    buf.clear()
                    val silence = ByteArray(4096)
                    buf.put(silence)
                    encoder.queueInputBuffer(inputIndex, 0, silence.size, 0, 0)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "feedAudioSilenceInitial exception", e)
        }
    }

    private fun scheduleNextFrame() {
        if (!mIsRecording.get()) return
        mCaptureHandler?.postDelayed({ captureFrame() }, FRAME_INTERVAL_MS)
    }

    private fun captureFrame() {
        if (!mIsRecording.get() || mEglRenderer == null) return
        val sv = mSurfaceView ?: return

        val curW = sv.width
        val curH = sv.height
        if (curW <= 0 || curH <= 0) {
            scheduleNextFrame()
            return
        }

        val frameBmp = Bitmap.createBitmap(curW, curH, Bitmap.Config.ARGB_8888)
        val handler = mCaptureHandler ?: return

        PixelCopy.request(sv, frameBmp, { result ->
            if (result == PixelCopy.SUCCESS && mIsRecording.get() && mEglRenderer != null) {
                val ptsNs = (System.currentTimeMillis() - mRecordStartMs) * 1_000_000L
                mEglRenderer?.drawFrame(frameBmp, ptsNs)
                drainVideoEncoder(false)
                mFrameCount++

                val elapsed = (System.currentTimeMillis() - mRecordStartMs) / 1000
                mMainHandler.post { mListener?.onTick(elapsed) }
            }
            frameBmp.recycle()
            scheduleNextFrame()
        }, handler)
    }

    private fun drainVideoEncoder(endOfStream: Boolean) {
        val encoder = mVideoEncoder ?: return
        if (endOfStream) {
            try {
                encoder.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.w(TAG, "signalEndOfInputStream failed", e)
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        while (mVideoEncoder != null) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(mMuxerLock) {
                    mMuxer?.let {
                        mVideoTrackIndex = it.addTrack(encoder.outputFormat)
                        checkStartMuxerLocked()
                    }
                }
            } else if (outputIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outputIndex)
                if (encodedData != null) {
                    synchronized(mMuxerLock) {
                        if (mMuxerStarted && mVideoTrackIndex >= 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                mMuxer?.writeSampleData(mVideoTrackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    override fun onPcmData(pcm: ByteArray, offset: Int, length: Int) {
        val encoder = mAudioEncoder
        if (!mIsRecording.get() || encoder == null || !mAudioEnabled || length <= 0) {
            return
        }

        try {
            val inputIndex = encoder.dequeueInputBuffer(10_000)
            if (inputIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputIndex)
                if (inputBuffer != null) {
                    inputBuffer.clear()
                    inputBuffer.put(pcm, offset, length)
                    val ptsUs = (System.currentTimeMillis() - mRecordStartMs) * 1000L
                    encoder.queueInputBuffer(inputIndex, 0, length, ptsUs, 0)
                }
            }
            drainAudioEncoder(false)
        } catch (e: Exception) {
            Log.w(TAG, "Audio encode error: ${e.message}")
        }
    }

    private fun drainAudioEncoder(endOfStream: Boolean) {
        val encoder = mAudioEncoder ?: return

        val bufferInfo = MediaCodec.BufferInfo()
        while (mAudioEncoder != null) {
            val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                synchronized(mMuxerLock) {
                    mMuxer?.let {
                        mAudioTrackIndex = it.addTrack(encoder.outputFormat)
                        checkStartMuxerLocked()
                    }
                }
            } else if (outputIndex >= 0) {
                val encodedData = encoder.getOutputBuffer(outputIndex)
                if (encodedData != null) {
                    synchronized(mMuxerLock) {
                        if (mMuxerStarted && mAudioTrackIndex >= 0) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0) {
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                mMuxer?.writeSampleData(mAudioTrackIndex, encodedData, bufferInfo)
                            }
                        }
                    }
                }
                encoder.releaseOutputBuffer(outputIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
    }

    private fun checkStartMuxerLocked() {
        val muxer = mMuxer
        if (mMuxerStarted || muxer == null) return
        val videoReady = mVideoTrackIndex >= 0
        val audioReady = !mAudioEnabled || mAudioTrackIndex >= 0
        if (videoReady && audioReady) {
            try {
                muxer.start()
                mMuxerStarted = true
                Log.d(TAG, "MediaMuxer started successfully! (videoTrack=$mVideoTrackIndex, audioTrack=$mAudioTrackIndex)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MediaMuxer", e)
            }
        }
    }

    fun stop() {
        if (!mIsRecording.getAndSet(false)) return

        mScrcpy?.setAudioPcmListener(null)
        mCaptureHandler?.removeCallbacksAndMessages(null)

        Thread({
            try {
                drainVideoEncoder(true)
                mAudioEncoder?.let { encoder ->
                    try {
                        val inIdx = encoder.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    } catch (ignored: Exception) {
                    }
                    drainAudioEncoder(true)
                }
            } catch (e: Exception) {
                Log.w(TAG, "drainEncoders on stop: ${e.message}")
            }

            var savedPath: String? = null
            try {
                synchronized(mMuxerLock) {
                    if (mMuxer != null && mMuxerStarted) {
                        mMuxer?.stop()
                        mMuxer?.release()
                        mMuxer = null
                    }
                }
                val ctx = mContext
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }
                    val uri = mMediaUri
                    if (uri != null && ctx != null) {
                        ctx.contentResolver.update(uri, values, null, null)
                        savedPath = Environment.DIRECTORY_MOVIES + "/scrcpy"
                    }
                } else if (mOutputFile != null) {
                    savedPath = mOutputFile?.absolutePath
                }
            } catch (e: Exception) {
                Log.e(TAG, "Muxer stop error", e)
            }

            cleanup()

            val path = savedPath
            mMainHandler.post {
                mListener?.onStopped(path ?: "Movies/scrcpy")
            }
        }, "RecorderStop").start()
    }

    private fun cleanup() {
        mScrcpy?.setAudioPcmListener(null)

        if (mCaptureHandler != null && mEglRenderer != null) {
            mCaptureHandler?.post {
                mEglRenderer?.release()
                mEglRenderer = null
            }
        }

        mCaptureThread?.quitSafely()
        mCaptureThread = null
        mCaptureHandler = null

        mVideoEncoder?.let {
            try {
                it.stop()
                it.release()
            } catch (ignored: Exception) {}
            mVideoEncoder = null
        }
        mInputSurface?.let {
            try { it.release() } catch (ignored: Exception) {}
            mInputSurface = null
        }

        mAudioEncoder?.let {
            try {
                it.stop()
                it.release()
            } catch (ignored: Exception) {}
            mAudioEncoder = null
        }

        synchronized(mMuxerLock) {
            mMuxer?.let {
                try { it.release() } catch (ignored: Exception) {}
                mMuxer = null
            }
        }

        mMediaOutputStream?.let {
            try { it.close() } catch (ignored: Exception) {}
            mMediaOutputStream = null
        }

        mSurfaceView = null
        mScrcpy = null
        mMuxerStarted = false
        mVideoTrackIndex = -1
        mAudioTrackIndex = -1
    }

    companion object {
        private const val TAG = "ScreenRecorder"
        private const val FRAME_RATE = 30
        private const val IFRAME_INTERVAL = 2 // seconds
        private const val VIDEO_BIT_RATE = 6_000_000 // 6 Mbps
        private const val FRAME_INTERVAL_MS = 1000L / FRAME_RATE

        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNELS = 2
        private const val AUDIO_BIT_RATE = 128_000 // 128 kbps AAC
    }
}
