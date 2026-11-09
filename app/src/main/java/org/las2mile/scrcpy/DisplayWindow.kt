package org.las2mile.scrcpy

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

class DisplayWindow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface OnMoveCallback {
        fun onMove(x: Float, y: Float)
    }

    interface OnActionCallback {
        fun onAction(actionType: Int)
    }

    interface SurfaceReadyCallback {
        fun onSurfaceReady(surface: Surface)
        fun onSurfaceDestroyed()
    }

    private var closeListener: OnClickListener? = null
    private var moveCallback: OnMoveCallback? = null
    private var actionCallback: OnActionCallback? = null
    private var onDisplayTouchListener: OnTouchListener? = null
    private var surfaceReadyCallback: SurfaceReadyCallback? = null

    private var oldX = 0f
    private var oldY = 0f

    private lateinit var header: ViewGroup
    private lateinit var container: ViewGroup
    private lateinit var surfaceView: SurfaceView
    private lateinit var actionbar: ViewGroup
    private var resizeHandle: View? = null
    private var hintLayout: View? = null
    private var tvRecordTimer: TextView? = null

    private var remoteW = 1080
    private var remoteH = 1920
    private var surface: Surface? = null

    init {
        initView()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initView() {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.window_display, this, true)

        container = findViewById(R.id.container)
        surfaceView = findViewById(R.id.surface)
        actionbar = findViewById(R.id.actionbar)
        header = findViewById(R.id.header)
        hintLayout = findViewById(R.id.hint_layout)
        tvRecordTimer = findViewById(R.id.tv_record_timer)

        // Header drag-to-move
        header.setOnTouchListener { _, motionEvent ->
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    oldX = motionEvent.rawX
                    oldY = motionEvent.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val disX = motionEvent.rawX - oldX
                    val disY = motionEvent.rawY - oldY
                    moveCallback?.onMove(disX, disY)
                    oldX = motionEvent.rawX
                    oldY = motionEvent.rawY
                }
            }
            true
        }

        // Corner resize handle
        resizeHandle = findViewById(R.id.iv_resize)
        resizeHandle?.setOnTouchListener(object : OnTouchListener {
            private var startRawX = 0f
            private var startRawY = 0f
            private var startW = 0

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = event.rawX
                        startRawY = event.rawY
                        startW = container.width
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - startRawX
                        val dy = event.rawY - startRawY
                        val delta = if (Math.abs(dx) > Math.abs(dy)) dx else dy
                        val targetW = startW + delta.toInt()
                        applyConstrainedSize(targetW)
                        return true
                    }
                }
                return false
            }
        })

        // Screenshot button
        findViewById<View?>(R.id.iv_screenshot)?.setOnClickListener {
            actionCallback?.onAction(ACTION_SCREENSHOT)
        }

        // Record button / timer
        findViewById<View?>(R.id.iv_record)?.setOnClickListener {
            actionCallback?.onAction(ACTION_RECORD_TOGGLE)
        }
        tvRecordTimer?.setOnClickListener {
            actionCallback?.onAction(ACTION_RECORD_TOGGLE)
        }

        // Quick rotate
        findViewById<View?>(R.id.iv_rotate)?.setOnClickListener {
            actionCallback?.onAction(ACTION_ROTATE)
        }

        // Minimize toggle
        findViewById<View?>(R.id.iv_mini)?.setOnClickListener {
            if (container.visibility == View.VISIBLE) {
                container.visibility = View.GONE
                actionbar.visibility = View.GONE
            } else {
                container.visibility = View.VISIBLE
                actionbar.visibility = View.VISIBLE
            }
        }

        // Close button
        findViewById<View?>(R.id.iv_close)?.setOnClickListener { v ->
            closeListener?.onClick(v)
        }

        // Navigation buttons
        findViewById<View?>(R.id.action_power)?.setOnClickListener {
            actionCallback?.onAction(ACTION_POWER)
        }
        findViewById<View?>(R.id.action_back)?.setOnClickListener {
            actionCallback?.onAction(ACTION_BACK)
        }
        findViewById<View?>(R.id.action_home)?.setOnClickListener {
            actionCallback?.onAction(ACTION_HOME)
        }
        findViewById<View?>(R.id.action_menu)?.setOnClickListener {
            actionCallback?.onAction(ACTION_MENU)
        }
        findViewById<View?>(R.id.action_vol_down)?.setOnClickListener {
            actionCallback?.onAction(ACTION_VOLUME_DOWN)
        }
        findViewById<View?>(R.id.action_vol_up)?.setOnClickListener {
            actionCallback?.onAction(ACTION_VOLUME_UP)
        }

        // SurfaceView callbacks and touch forwarding
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                Log.d(TAG, "surfaceCreated: $surface")
                surfaceReadyCallback?.onSurfaceReady(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                surface = holder.surface
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                surfaceReadyCallback?.onSurfaceDestroyed()
            }
        })

        surfaceView.setOnTouchListener { view, motionEvent ->
            onDisplayTouchListener?.onTouch(view, motionEvent) ?: false
        }
    }

    fun setCloseListener(closeListener: OnClickListener?) {
        this.closeListener = closeListener
    }

    fun setMoveCallback(moveCallback: OnMoveCallback?) {
        this.moveCallback = moveCallback
    }

    fun setActionCallback(actionCallback: OnActionCallback?) {
        this.actionCallback = actionCallback
    }

    fun setOnDisplayTouchListener(onDisplayTouchListener: OnTouchListener?) {
        this.onDisplayTouchListener = onDisplayTouchListener
    }

    fun setSurfaceReadyCallback(callback: SurfaceReadyCallback?) {
        this.surfaceReadyCallback = callback
        val surf = surface
        if (surf != null && surf.isValid && callback != null) {
            callback.onSurfaceReady(surf)
        }
    }

    fun getSurfaceView(): SurfaceView {
        return surfaceView
    }

    fun setRemote(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        this.remoteW = w
        this.remoteH = h

        post {
            val screenW = getScreenWidth()
            val targetW = if (w < h) {
                (screenW * 0.48f).toInt()
            } else {
                (screenW * 0.65f).toInt()
            }
            applyConstrainedSize(targetW)
        }
    }

    fun applyConstrainedSize(targetWidth: Int) {
        val screenW = getScreenWidth()
        val screenH = getScreenHeight()
        val aspect = if (remoteW > 0 && remoteH > 0) (remoteW.toFloat() / remoteH.toFloat()) else (9f / 16f)

        val minW = dpToPx(160)
        val maxW = (screenW * 0.95f).toInt()
        var clampedW = Math.max(minW, Math.min(maxW, targetWidth))
        var clampedH = (clampedW / aspect).toInt()

        val maxH = (screenH * 0.85f).toInt()
        if (clampedH > maxH) {
            clampedH = maxH
            clampedW = (clampedH * aspect).toInt()
        }

        val lp = container.layoutParams
        lp.width = clampedW
        lp.height = clampedH
        container.layoutParams = lp

        val hlp = header.layoutParams
        hlp.width = clampedW
        header.layoutParams = hlp

        val alp = actionbar.layoutParams
        alp.width = clampedW
        actionbar.layoutParams = alp

        requestLayout()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    @Suppress("DEPRECATION")
    private fun getScreenWidth(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 1080
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels
    }

    @Suppress("DEPRECATION")
    private fun getScreenHeight(): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return 1920
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.heightPixels
    }

    fun hideHintTip() {
        hintLayout?.visibility = View.GONE
    }

    fun showHintTip() {
        hintLayout?.visibility = View.VISIBLE
    }

    fun updateRecordTimer(timerText: String?) {
        val recordBtn = findViewById<View?>(R.id.iv_record)
        val recording = !timerText.isNullOrEmpty()
        recordBtn?.visibility = if (recording) View.GONE else View.VISIBLE
        tvRecordTimer?.let {
            it.visibility = if (recording) View.VISIBLE else View.GONE
            if (recording) it.text = timerText
        }
    }

    fun getDisplaySurface(): Surface? {
        return surface ?: surfaceView.holder.surface
    }

    fun getSurfaceWidth(): Int {
        return if (surfaceView.width > 0) surfaceView.width else container.width
    }

    fun getSurfaceHeight(): Int {
        return if (surfaceView.height > 0) surfaceView.height else container.height
    }

    companion object {
        private const val TAG = "DisplayWindow"

        const val ACTION_BACK = 0
        const val ACTION_HOME = 1
        const val ACTION_MENU = 2
        const val ACTION_ROTATE = 3
        const val ACTION_POWER = 4
        const val ACTION_VOLUME_UP = 5
        const val ACTION_VOLUME_DOWN = 6
        const val ACTION_SCREENSHOT = 7
        const val ACTION_RECORD_TOGGLE = 8
    }
}

