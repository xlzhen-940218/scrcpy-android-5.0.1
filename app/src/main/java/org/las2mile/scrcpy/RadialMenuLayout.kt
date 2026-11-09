package org.las2mile.scrcpy

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

class RadialMenuLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    fun interface OnMenuActionListener {
        fun onActionSelected(actionId: Int)
    }

    data class MenuItem(
        val actionId: Int,
        val iconRes: Int,
        val labelRes: Int,
        val labelFallback: String
    )

    private val menuItems = mutableListOf<MenuItem>()
    private val itemViews = mutableListOf<FrameLayout>()
    private var actionListener: OnMenuActionListener? = null

    // Center Floating Button
    private lateinit var centerFab: FrameLayout
    private lateinit var ivCenterIcon: ImageView
    private lateinit var layoutRecording: LinearLayout
    private lateinit var dotRecording: View
    private lateinit var tvRecordTimer: TextView

    // Tooltip chip
    private lateinit var tvTooltip: TextView

    // Background scrim to dismiss when tapping outside
    private lateinit var scrimView: View

    // Menu state
    var isExpanded = false
        private set
    var isRecording = false
        private set
    private var selectedIndex = -1
    private var isSliding = false
    private var wasExpandedOnDown = false
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchOffsetX = 0f
    private var touchOffsetY = 0f
    private var touchSlop = 0

    // Dragging & Edge Docking State
    private var isDragging = false
    private var isLongPressed = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    // Dimensions (pixels)
    private var fabSizePx = 0
    private var itemSizePx = 0
    private var minRadiusPx = 0f
    private var maxRadiusPx = 0f

    // Positions
    private var dockedCenterX = -1f
    private var dockedCenterY = -1f
    private var activeCenterX = -1f
    private var activeCenterY = -1f
    private var currentFabCenterX = -1f
    private var currentFabCenterY = -1f

    // Golden Spiral item target positions
    private lateinit var itemTargetX: FloatArray
    private lateinit var itemTargetY: FloatArray

    // Expansion animator
    private var expandAnimator: ValueAnimator? = null
    private var expandFraction = 0f

    private var vibrator: Vibrator? = null

    init {
        init(context)
    }

    private fun init(context: Context) {
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        @Suppress("DEPRECATION")
        vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        fabSizePx = dpToPx(56)
        itemSizePx = dpToPx(44)
        minRadiusPx = dpToPx(72).toFloat()
        maxRadiusPx = dpToPx(150).toFloat()

        initMenuItems()
        setupViews(context)
    }

    private fun initMenuItems() {
        menuItems.clear()
        menuItems.add(MenuItem(ACTION_SCREENSHOT, R.drawable.ic_screenshot, R.string.btn_screenshot, "截屏"))
        menuItems.add(MenuItem(ACTION_RECORD, R.drawable.ic_record, R.string.btn_record, "录屏"))
        menuItems.add(MenuItem(ACTION_FILE_TRANSFER, R.drawable.ic_menu_folder, R.string.menu_file_transfer, "传输"))
        menuItems.add(MenuItem(ACTION_KEYBOARD, R.drawable.ic_nav_keyboard, R.string.menu_keyboard, "键盘"))
        menuItems.add(MenuItem(ACTION_POWER, R.drawable.ic_nav_power, R.string.power_button, "电源"))
        menuItems.add(MenuItem(ACTION_VOL_UP, R.drawable.ic_nav_vol_up, R.string.volup_button, "音量+"))
        menuItems.add(MenuItem(ACTION_VOL_DOWN, R.drawable.ic_nav_vol_down, R.string.voldown_button, "音量-"))
        menuItems.add(MenuItem(ACTION_ROTATE, R.drawable.ic_nav_rotate, R.string.rotate_button, "旋转"))
        menuItems.add(MenuItem(ACTION_APPS, R.drawable.ic_nav_tasks, R.string.appswitch_button, "任务"))
        menuItems.add(MenuItem(ACTION_HOME, R.drawable.ic_nav_home, R.string.home_button, "主页"))
        menuItems.add(MenuItem(ACTION_BACK, R.drawable.ic_nav_back, R.string.back_button, "返回"))

        itemTargetX = FloatArray(menuItems.size)
        itemTargetY = FloatArray(menuItems.size)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViews(context: Context) {
        clipChildren = false
        clipToPadding = false

        // 1. Scrim overlay
        scrimView = View(context).apply {
            setBackgroundColor(Color.parseColor("#4D000000"))
            alpha = 0f
            visibility = View.GONE
            setOnClickListener { collapse() }
        }
        addView(scrimView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 2. Menu Items
        for (i in menuItems.indices) {
            val item = menuItems[i]

            val itemView = FrameLayout(context).apply {
                setBackgroundResource(R.drawable.bg_radial_item_normal)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = dpToPx(8).toFloat()
                }

                val iv = ImageView(context).apply {
                    setImageResource(item.iconRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    val padding = dpToPx(10)
                    setPadding(padding, padding, padding, padding)
                }
                addView(iv, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

                visibility = View.GONE
                scaleX = 0f
                scaleY = 0f
                alpha = 0f

                setOnClickListener {
                    actionListener?.onActionSelected(item.actionId)
                    collapse()
                }
            }

            addView(itemView, LayoutParams(itemSizePx, itemSizePx))
            itemViews.add(itemView)
        }

        // 3. Center FAB
        centerFab = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_floating_tool_button)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpToPx(14).toFloat()
            }

            ivCenterIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_floating_tools)
                scaleType = ImageView.ScaleType.FIT_CENTER
                val iconPadding = dpToPx(14)
                setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            }
            addView(ivCenterIcon, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            layoutRecording = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                visibility = View.GONE

                dotRecording = View(context).apply {
                    setBackgroundResource(R.drawable.ic_record_dot)
                }
                val dotLp = LinearLayout.LayoutParams(dpToPx(7), dpToPx(7)).apply {
                    bottomMargin = dpToPx(2)
                }
                addView(dotRecording, dotLp)

                tvRecordTimer = TextView(context).apply {
                    text = "00:00"
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                }
                addView(tvRecordTimer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            addView(layoutRecording, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

            setOnTouchListener { _, event -> handleFabTouch(event) }
        }
        addView(centerFab, LayoutParams(fabSizePx, fabSizePx))

        // 4. Tooltip chip
        tvTooltip = TextView(context).apply {
            setBackgroundResource(R.drawable.bg_radial_tooltip)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            visibility = View.GONE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = dpToPx(16).toFloat()
            }
            val padH = dpToPx(10)
            val padV = dpToPx(4)
            setPadding(padH, padV, padH, padV)
        }
        addView(tvTooltip, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun setOnMenuActionListener(listener: OnMenuActionListener?) {
        this.actionListener = listener
    }

    fun setRecording(recording: Boolean) {
        this.isRecording = recording
        if (recording) {
            ivCenterIcon.visibility = View.GONE
            layoutRecording.visibility = View.VISIBLE
            centerFab.alpha = 1.0f
        } else {
            layoutRecording.visibility = View.GONE
            ivCenterIcon.visibility = View.VISIBLE
            val isAtEdge = (dockedCenterX <= fabSizePx / 2f || dockedCenterX >= width - fabSizePx / 2f)
            centerFab.alpha = if (isAtEdge && !isExpanded) 0.75f else 1.0f
        }
    }

    fun updateRecordTimer(timerText: String?) {
        if (timerText.isNullOrEmpty()) {
            setRecording(false)
        } else {
            if (!isRecording) {
                setRecording(true)
            }
            tvRecordTimer.text = timerText
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            if (isTouchInsideFab(ev.x, ev.y)) {
                return false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private fun isTouchInsideFab(localX: Float, localY: Float): Boolean {
        val dx = localX - currentFabCenterX
        val dy = localY - currentFabCenterY
        return (dx * dx + dy * dy) <= (fabSizePx * fabSizePx)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isExpanded) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                if (!isTouchInsideFab(event.x, event.y)) {
                    collapse()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return

        if (dockedCenterX < 0 || dockedCenterY < 0) {
            dockedCenterX = width.toFloat()
            dockedCenterY = height * 0.65f
            currentFabCenterX = dockedCenterX
            currentFabCenterY = dockedCenterY
            centerFab.alpha = 0.75f
        }

        val minY = fabSizePx / 2f + dpToPx(16)
        val maxY = height - fabSizePx / 2f - dpToPx(16)
        dockedCenterY = Math.max(minY, Math.min(maxY, dockedCenterY))

        computeActiveCenter(width, height)
        computeGoldenSpiralGeometry(width, height)
        updateLayoutForFraction(expandFraction)
    }

    private fun computeActiveCenter(screenW: Int, screenH: Int) {
        val edgeThreshold = dpToPx(48).toFloat()
        val isNearLeft = (dockedCenterX <= edgeThreshold)
        val isNearRight = (dockedCenterX >= screenW - edgeThreshold)
        val cornerYThreshold = dpToPx(110).toFloat()
        val isNearTop = (dockedCenterY <= cornerYThreshold)
        val isNearBottom = (dockedCenterY >= screenH - cornerYThreshold)
        val isCorner = (isNearLeft || isNearRight) && (isNearTop || isNearBottom)

        val shiftX = (if (isCorner) dpToPx(115) else dpToPx(95)).toFloat()

        if (isNearRight) {
            activeCenterX = screenW - shiftX
        } else if (isNearLeft) {
            activeCenterX = shiftX
        } else {
            val safeMargin = maxRadiusPx + fabSizePx / 2f + dpToPx(8)
            activeCenterX = if (screenW > safeMargin * 2) {
                Math.max(safeMargin, Math.min(screenW - safeMargin, dockedCenterX))
            } else {
                screenW / 2f
            }
        }

        if (isCorner) {
            activeCenterY = if (isNearTop) {
                dpToPx(125).toFloat()
            } else {
                (screenH - dpToPx(125)).toFloat()
            }
        } else {
            val minY = dpToPx(100).toFloat()
            val maxY = (screenH - dpToPx(100)).toFloat()
            activeCenterY = Math.max(minY, Math.min(maxY, dockedCenterY))
        }
    }

    private fun computeGoldenSpiralGeometry(screenW: Int, screenH: Int) {
        val n = menuItems.size
        if (n == 0) return

        val edgeThreshold = dpToPx(48).toFloat()
        val isNearLeft = (dockedCenterX <= edgeThreshold)
        val isNearRight = (dockedCenterX >= screenW - edgeThreshold)
        val cornerYThreshold = dpToPx(110).toFloat()
        val isNearTop = (dockedCenterY <= cornerYThreshold)
        val isNearBottom = (dockedCenterY >= screenH - cornerYThreshold)
        val isCorner = (isNearLeft || isNearRight) && (isNearTop || isNearBottom)

        val rStart = (if (isCorner) dpToPx(70) else dpToPx(72)).toFloat()
        val rEnd = (if (isCorner) dpToPx(148) else if (isNearLeft || isNearRight) dpToPx(156) else dpToPx(160)).toFloat()
        val targetDistance = (if (isCorner) dpToPx(44) else dpToPx(46)).toFloat()

        val r = FloatArray(n)
        for (i in 0 until n) {
            val progress = i.toFloat() / (n - 1)
            r[i] = rStart + (rEnd - rStart) * Math.pow(progress.toDouble(), 0.85).toFloat()
        }

        val deltaThetas = DoubleArray(n - 1)
        var totalSweep = 0.0
        for (i in 0 until n - 1) {
            val rA = r[i]
            val rB = r[i + 1]
            var cosVal = (rA * rA + rB * rB - targetDistance * targetDistance) / (2f * rA * rB)
            cosVal = Math.max(-1f, Math.min(1f, cosVal))
            deltaThetas[i] = Math.acos(cosVal.toDouble())
            totalSweep += deltaThetas[i]
        }

        val startAngle: Double
        val direction: Double

        if (isCorner) {
            if (isNearRight && isNearTop) {
                startAngle = Math.toRadians(15.0)
                direction = 1.0
            } else if (isNearLeft && isNearTop) {
                startAngle = Math.toRadians(165.0)
                direction = -1.0
            } else if (isNearRight && isNearBottom) {
                startAngle = Math.toRadians(-15.0)
                direction = -1.0
            } else {
                startAngle = Math.toRadians(-165.0)
                direction = 1.0
            }
        } else if (isNearRight) {
            startAngle = Math.PI - (totalSweep / 2.0)
            direction = 1.0
        } else if (isNearLeft) {
            startAngle = -(totalSweep / 2.0)
            direction = 1.0
        } else {
            val dirX = (screenW / 2f) - activeCenterX
            val dirY = (screenH / 2f) - activeCenterY
            val baseAngle = Math.atan2(dirY.toDouble(), dirX.toDouble())
            startAngle = baseAngle - (totalSweep / 2.0)
            direction = 1.0
        }

        val itemMargin = itemSizePx / 2f + dpToPx(4)
        var currentAngle = startAngle

        for (i in 0 until n) {
            val tx = (activeCenterX + r[i] * Math.cos(currentAngle)).toFloat()
            val ty = (activeCenterY + r[i] * Math.sin(currentAngle)).toFloat()

            itemTargetX[i] = Math.max(itemMargin, Math.min(screenW - itemMargin, tx))
            itemTargetY[i] = Math.max(itemMargin, Math.min(screenH - itemMargin, ty))

            if (i < n - 1) {
                currentAngle += direction * deltaThetas[i]
            }
        }
    }

    private fun updateLayoutForFraction(fraction: Float) {
        currentFabCenterX = dockedCenterX + (activeCenterX - dockedCenterX) * fraction
        currentFabCenterY = dockedCenterY + (activeCenterY - dockedCenterY) * fraction

        val fabL = (currentFabCenterX - fabSizePx / 2f).toInt()
        val fabT = (currentFabCenterY - fabSizePx / 2f).toInt()
        centerFab.layout(fabL, fabT, fabL + fabSizePx, fabT + fabSizePx)

        if (fraction > 0f) {
            centerFab.alpha = 1.0f
        } else if (!isRecording) {
            val isAtEdge = (dockedCenterX <= fabSizePx / 2f || dockedCenterX >= width - fabSizePx / 2f)
            centerFab.alpha = if (isAtEdge) 0.75f else 1.0f
        }

        centerFab.rotation = fraction * 45f

        for (i in itemViews.indices) {
            val item = itemViews[i]
            if (fraction <= 0.01f) {
                item.visibility = View.GONE
                continue
            }
            item.visibility = View.VISIBLE

            val curX = currentFabCenterX + (itemTargetX[i] - currentFabCenterX) * fraction
            val curY = currentFabCenterY + (itemTargetY[i] - currentFabCenterY) * fraction

            val left = (curX - itemSizePx / 2f).toInt()
            val top = (curY - itemSizePx / 2f).toInt()
            item.layout(left, top, left + itemSizePx, top + itemSizePx)

            val baseScale = if (i == selectedIndex) 1.25f else 1.0f
            item.scaleX = fraction * baseScale
            item.scaleY = fraction * baseScale
            item.alpha = Math.min(1.0f, fraction * 1.2f)
        }

        if (selectedIndex >= 0 && selectedIndex < itemViews.size && fraction > 0.8f) {
            tvTooltip.visibility = View.VISIBLE
            tvTooltip.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val tw = tvTooltip.measuredWidth
            val th = tvTooltip.measuredHeight

            var tLeft = (itemTargetX[selectedIndex] - tw / 2f).toInt()
            var tTop = (itemTargetY[selectedIndex] - itemSizePx / 2f - th - dpToPx(6)).toInt()

            tLeft = Math.max(dpToPx(8), Math.min(width - tw - dpToPx(8), tLeft))
            tTop = Math.max(dpToPx(8), Math.min(height - th - dpToPx(8), tTop))

            tvTooltip.layout(tLeft, tTop, tLeft + tw, tTop + th)
        } else {
            tvTooltip.visibility = View.GONE
        }
    }

    private fun handleFabTouch(event: MotionEvent): Boolean {
        val rawX = event.rawX
        val rawY = event.rawY

        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val localX = rawX - loc[0]
        val localY = rawY - loc[1]

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = rawX
                touchDownY = rawY
                touchOffsetX = localX - dockedCenterX
                touchOffsetY = localY - dockedCenterY
                isSliding = false
                isDragging = false
                isLongPressed = false
                wasExpandedOnDown = isExpanded
                selectedIndex = -1

                centerFab.alpha = 1.0f
                parent?.requestDisallowInterceptTouchEvent(true)

                longPressRunnable = Runnable {
                    isLongPressed = true
                    performHapticTick()

                    if (isRecording) {
                        if (!isExpanded) {
                            expand()
                        }
                    } else if (!isExpanded) {
                        isDragging = true
                        centerFab.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start()
                    }
                }
                uiHandler.postDelayed(longPressRunnable!!, 350)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = rawX - touchDownX
                val dy = rawY - touchDownY
                val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()

                if (dist > touchSlop) {
                    if (isDragging) {
                        dockedCenterX = localX - touchOffsetX
                        dockedCenterY = localY - touchOffsetY

                        val minY = fabSizePx / 2f + dpToPx(16)
                        val maxY = height - fabSizePx / 2f - dpToPx(16)
                        dockedCenterY = Math.max(minY, Math.min(maxY, dockedCenterY))

                        currentFabCenterX = dockedCenterX
                        currentFabCenterY = dockedCenterY

                        val fabL = (currentFabCenterX - fabSizePx / 2f).toInt()
                        val fabT = (currentFabCenterY - fabSizePx / 2f).toInt()
                        centerFab.layout(fabL, fabT, fabL + fabSizePx, fabT + fabSizePx)
                        return true
                    } else if (!isLongPressed) {
                        cancelCustomLongPress()

                        if (!isRecording) {
                            isSliding = true
                            if (!isExpanded) {
                                expand()
                            }
                        }
                    }
                }

                if (isSliding && isExpanded) {
                    val nearest = findNearestItem(localX, localY)
                    setSelectedItem(nearest)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelCustomLongPress()
                parent?.requestDisallowInterceptTouchEvent(false)

                if (isDragging) {
                    finishDragAndSnapToEdge()
                    isDragging = false
                    isLongPressed = false
                    return true
                }

                if (isRecording) {
                    if (!isLongPressed && !isExpanded) {
                        actionListener?.onActionSelected(ACTION_RECORD)
                    }
                } else {
                    if (isSliding && selectedIndex >= 0) {
                        val targetAction = menuItems[selectedIndex].actionId
                        performHapticTick()
                        collapse()
                        actionListener?.onActionSelected(targetAction)
                    } else if (!isSliding) {
                        if (wasExpandedOnDown) {
                            collapse()
                        } else {
                            expand()
                        }
                    } else {
                        collapse()
                    }
                }

                isSliding = false
                isLongPressed = false
                setSelectedItem(-1)
                return true
            }
        }
        return false
    }

    private fun cancelCustomLongPress() {
        longPressRunnable?.let {
            uiHandler.removeCallbacks(it)
            longPressRunnable = null
        }
    }

    private fun finishDragAndSnapToEdge() {
        val width = width
        val height = height
        if (width <= 0 || height <= 0) return

        centerFab.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()

        val edgeThreshold = dpToPx(48).toFloat()

        if (dockedCenterX < edgeThreshold) {
            animateSnapToX(0f, true)
        } else if (dockedCenterX > width - edgeThreshold) {
            animateSnapToX(width.toFloat(), true)
        } else {
            val minY = fabSizePx / 2f + dpToPx(16)
            val maxY = height - fabSizePx / 2f - dpToPx(16)
            dockedCenterY = Math.max(minY, Math.min(maxY, dockedCenterY))
            centerFab.animate().alpha(1.0f).setDuration(150).start()
        }
    }

    private fun animateSnapToX(targetX: Float, dockedAtEdge: Boolean) {
        val snapAnim = ValueAnimator.ofFloat(dockedCenterX, targetX).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                dockedCenterX = animation.animatedValue as Float
                currentFabCenterX = dockedCenterX
                val fabL = (currentFabCenterX - fabSizePx / 2f).toInt()
                val fabT = (currentFabCenterY - fabSizePx / 2f).toInt()
                centerFab.layout(fabL, fabT, fabL + fabSizePx, fabT + fabSizePx)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isRecording && !isExpanded) {
                        centerFab.animate().alpha(if (dockedAtEdge) 0.75f else 1.0f).setDuration(200).start()
                    }
                }
            })
        }
        snapAnim.start()
    }

    private fun findNearestItem(touchX: Float, touchY: Float): Int {
        val distFromFab = Math.hypot((touchX - activeCenterX).toDouble(), (touchY - activeCenterY).toDouble()).toFloat()

        if (distFromFab < dpToPx(32)) {
            return -1
        }

        var closestIdx = -1
        var minDist = Float.MAX_VALUE
        val hitThreshold = dpToPx(38).toFloat()

        for (i in itemViews.indices) {
            val ix = itemTargetX[i]
            val iy = itemTargetY[i]
            val d = Math.hypot((touchX - ix).toDouble(), (touchY - iy).toDouble()).toFloat()
            if (d < minDist) {
                minDist = d
                closestIdx = i
            }
        }

        return if (minDist <= hitThreshold) closestIdx else -1
    }

    private fun setSelectedItem(index: Int) {
        if (selectedIndex == index) return

        val oldIndex = selectedIndex
        selectedIndex = index

        if (oldIndex in itemViews.indices) {
            val oldView = itemViews[oldIndex]
            oldView.setBackgroundResource(R.drawable.bg_radial_item_normal)
            oldView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
        }

        if (index in itemViews.indices) {
            val newView = itemViews[index]
            newView.setBackgroundResource(R.drawable.bg_radial_item_selected)
            newView.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).start()

            val mi = menuItems[index]
            var label: String? = context.getString(mi.labelRes)
            if (label.isNullOrEmpty() || label.length > 6) {
                label = mi.labelFallback
            }
            tvTooltip.text = label
            performHapticTick()
        }

        updateLayoutForFraction(expandFraction)
    }

    fun expand() {
        if (isExpanded) return
        isExpanded = true

        expandAnimator?.cancel()

        computeActiveCenter(width, height)
        computeGoldenSpiralGeometry(width, height)

        scrimView.visibility = View.VISIBLE
        scrimView.animate().alpha(1.0f).setDuration(220).start()

        for (item in itemViews) {
            item.visibility = View.VISIBLE
        }

        expandAnimator = ValueAnimator.ofFloat(expandFraction, 1.0f).apply {
            duration = 280
            interpolator = OvershootInterpolator(1.25f)
            addUpdateListener { animation ->
                expandFraction = animation.animatedValue as Float
                updateLayoutForFraction(expandFraction)
            }
            start()
        }
    }

    fun collapse() {
        if (!isExpanded) return
        isExpanded = false
        setSelectedItem(-1)

        expandAnimator?.cancel()

        scrimView.animate().alpha(0f).setDuration(180).withEndAction { scrimView.visibility = View.GONE }.start()

        expandAnimator = ValueAnimator.ofFloat(expandFraction, 0f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                expandFraction = animation.animatedValue as Float
                updateLayoutForFraction(expandFraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    for (item in itemViews) {
                        item.visibility = View.GONE
                    }
                    tvTooltip.visibility = View.GONE
                    if (!isRecording) {
                        val isAtEdge = (dockedCenterX <= fabSizePx / 2f || dockedCenterX >= width - fabSizePx / 2f)
                        centerFab.animate().alpha(if (isAtEdge) 0.75f else 1.0f).setDuration(150).start()
                    }
                }
            })
            start()
        }
    }

    fun setRecordingState(recording: Boolean, timeText: String?) {
        this.isRecording = recording
        post {
            if (recording) {
                ivCenterIcon.visibility = View.GONE
                layoutRecording.visibility = View.VISIBLE
                if (timeText != null) {
                    tvRecordTimer.text = timeText
                }
                centerFab.setBackgroundResource(R.drawable.bg_floating_tool_button_recording)
                centerFab.alpha = 1.0f
            } else {
                layoutRecording.visibility = View.GONE
                ivCenterIcon.visibility = View.VISIBLE
                centerFab.setBackgroundResource(R.drawable.bg_floating_tool_button)
                if (!isExpanded) {
                    val isAtEdge = (dockedCenterX <= fabSizePx / 2f || dockedCenterX >= width - fabSizePx / 2f)
                    centerFab.alpha = if (isAtEdge) 0.75f else 1.0f
                }
            }
        }
    }

    private fun performHapticTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator?.hasVibrator() == true) {
                vibrator?.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    companion object {
        const val ACTION_SCREENSHOT = 1
        const val ACTION_RECORD = 2
        const val ACTION_POWER = 3
        const val ACTION_VOL_UP = 4
        const val ACTION_VOL_DOWN = 5
        const val ACTION_ROTATE = 6
        const val ACTION_APPS = 7
        const val ACTION_HOME = 8
        const val ACTION_BACK = 9
        const val ACTION_FILE_TRANSFER = 10
        const val ACTION_KEYBOARD = 11
    }
}
