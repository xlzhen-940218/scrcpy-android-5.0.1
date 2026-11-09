package org.las2mile.scrcpy

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScreenCaptureUtil {
    private const val TAG = "ScreenCaptureUtil"

    interface CaptureCallback {
        fun onSuccess(filePath: String)
        fun onFailure(reason: String)
    }

    @JvmStatic
    fun captureFrame(surfaceView: SurfaceView?, context: Context?, callback: CaptureCallback?) {
        if (surfaceView == null || callback == null || context == null) return

        val mainHandler = Handler(Looper.getMainLooper())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val w = surfaceView.width
            val h = surfaceView.height
            if (w <= 0 || h <= 0) {
                callback.onFailure("SurfaceView size is 0")
                return
            }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

            val handlerThread = HandlerThread("PixelCopyThread")
            handlerThread.start()
            val copyHandler = Handler(handlerThread.looper)

            PixelCopy.request(surfaceView, bitmap, { copyResult ->
                handlerThread.quitSafely()
                if (copyResult == PixelCopy.SUCCESS) {
                    saveBitmap(bitmap, context, mainHandler, callback)
                } else {
                    Log.e(TAG, "PixelCopy failed: $copyResult")
                    mainHandler.post { callback.onFailure("PixelCopy error: $copyResult") }
                }
            }, copyHandler)
        } else {
            @Suppress("DEPRECATION")
            surfaceView.isDrawingCacheEnabled = true
            @Suppress("DEPRECATION")
            val cache = surfaceView.drawingCache
            val bitmap = if (cache != null) Bitmap.createBitmap(cache) else null
            @Suppress("DEPRECATION")
            surfaceView.isDrawingCacheEnabled = false
            if (bitmap == null) {
                mainHandler.post { callback.onFailure("DrawingCache returned null") }
                return
            }
            saveBitmap(bitmap, context, mainHandler, callback)
        }
    }

    private fun saveBitmap(
        bitmap: Bitmap,
        context: Context,
        mainHandler: Handler,
        callback: CaptureCallback
    ) {
        Thread({
            try {
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "scrcpy_$timeStamp.png"
                val savedPath: String

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/scrcpy")
                    }
                    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw Exception("MediaStore insert returned null URI")
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
                    } ?: throw Exception("Cannot open output stream")
                    savedPath = Environment.DIRECTORY_PICTURES + "/scrcpy/" + fileName
                } else {
                    val dir = File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                        "scrcpy"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val file = File(dir, fileName)
                    FileOutputStream(file).use { fos ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    savedPath = file.absolutePath
                }

                bitmap.recycle()
                mainHandler.post { callback.onSuccess(savedPath) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save screenshot", e)
                val msg = e.message ?: e.toString()
                mainHandler.post { callback.onFailure(msg) }
            }
        }, "SaveScreenshot").start()
    }
}
