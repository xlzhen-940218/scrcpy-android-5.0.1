package org.las2mile.scrcpy

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class EglSurfaceRenderer(surface: Surface, private val mCanvasWidth: Int, private val mCanvasHeight: Int) {

    private var mEglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var mEglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var mEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var mProgram = 0
    private var maPositionHandle = -1
    private var maTexCoordHandle = -1
    private var muTextureHandle = -1
    private var mTextureId = 0

    private val mVertexBuffer: FloatBuffer
    private val mTexCoordBuffer: FloatBuffer

    init {
        mVertexBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        mTexCoordBuffer = ByteBuffer.allocateDirect(4 * 2 * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

        val texCoords = floatArrayOf(
            0.0f, 0.0f, // top left
            1.0f, 0.0f, // top right
            0.0f, 1.0f, // bottom left
            1.0f, 1.0f  // bottom right
        )
        mTexCoordBuffer.put(texCoords).position(0)

        initEGL(surface)
        initGL()
    }

    private fun initEGL(surface: Surface) {
        mEglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (mEglDisplay === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(mEglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(mEglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
        if (numConfigs[0] == 0) {
            attribList[10] = EGL14.EGL_NONE
            EGL14.eglChooseConfig(mEglDisplay, attribList, 0, configs, 0, configs.size, numConfigs, 0)
        }

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        mEglContext = EGL14.eglCreateContext(mEglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        if (mEglContext === EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        mEglSurface = EGL14.eglCreateWindowSurface(mEglDisplay, configs[0], surface, surfaceAttribs, 0)
        if (mEglSurface === EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed")
        }

        if (!EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    private fun initGL() {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)

        mProgram = GLES20.glCreateProgram()
        GLES20.glAttachShader(mProgram, vertexShader)
        GLES20.glAttachShader(mProgram, fragmentShader)
        GLES20.glLinkProgram(mProgram)

        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(mProgram, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(mProgram)
            GLES20.glDeleteProgram(mProgram)
            throw RuntimeException("Could not link program: $info")
        }

        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        maTexCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTexCoord")
        muTextureHandle = GLES20.glGetUniformLocation(mProgram, "uTexture")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        mTextureId = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("Shader compilation failed: $info")
        }
        return shader
    }

    fun drawFrame(bitmap: Bitmap?, ptsNs: Long) {
        if (bitmap == null || bitmap.isRecycled) return
        if (mEglDisplay === EGL14.EGL_NO_DISPLAY || mEglSurface === EGL14.EGL_NO_SURFACE) return

        EGL14.eglMakeCurrent(mEglDisplay, mEglSurface, mEglSurface, mEglContext)

        GLES20.glViewport(0, 0, mCanvasWidth, mCanvasHeight)
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val canvasAspect = mCanvasWidth.toFloat() / mCanvasHeight.toFloat()
        val bmpAspect = bitmap.width.toFloat() / bitmap.height.toFloat()

        var scaleX = 1.0f
        var scaleY = 1.0f

        if (bmpAspect > canvasAspect) {
            scaleY = canvasAspect / bmpAspect
        } else {
            scaleX = bmpAspect / canvasAspect
        }

        val vertices = floatArrayOf(
            -scaleX,  scaleY, // top left
             scaleX,  scaleY, // top right
            -scaleX, -scaleY, // bottom left
             scaleX, -scaleY  // bottom right
        )
        mVertexBuffer.position(0)
        mVertexBuffer.put(vertices).position(0)

        GLES20.glUseProgram(mProgram)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glUniform1i(muTextureHandle, 0)

        GLES20.glEnableVertexAttribArray(maPositionHandle)
        GLES20.glVertexAttribPointer(maPositionHandle, 2, GLES20.GL_FLOAT, false, 0, mVertexBuffer)

        GLES20.glEnableVertexAttribArray(maTexCoordHandle)
        GLES20.glVertexAttribPointer(maTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mTexCoordBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(maPositionHandle)
        GLES20.glDisableVertexAttribArray(maTexCoordHandle)

        EGLExt.eglPresentationTimeANDROID(mEglDisplay, mEglSurface, ptsNs)
        EGL14.eglSwapBuffers(mEglDisplay, mEglSurface)
    }

    fun release() {
        if (mEglDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(mEglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (mEglSurface !== EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(mEglDisplay, mEglSurface)
                mEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (mEglContext !== EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(mEglDisplay, mEglContext)
                mEglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(mEglDisplay)
            mEglDisplay = EGL14.EGL_NO_DISPLAY
        }

        if (mTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(mTextureId), 0)
            mTextureId = 0
        }
        if (mProgram != 0) {
            GLES20.glDeleteProgram(mProgram)
            mProgram = 0
        }
    }

    companion object {
        private const val TAG = "EglSurfaceRenderer"
        private const val EGL_RECORDABLE_ANDROID = 0x3142

        private const val VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n"

        private const val FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n"
    }
}

