package org.las2mile.scrcpy;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Floating tool button with an expandable Golden Spiral (黄金螺旋) radial menu.
 * Features:
 * 1. Long-press to drag and reposition; snaps to screen edge and displays half-hidden (展现一半).
 * 2. Expands in a Golden Spiral to avoid crowding; automatically shifts inward away from the edge
 *    when expanding so all menu items stay fully on-screen.
 * 3. Shows recording elapsed time when recording is active.
 *    Single tap during recording opens the Stop Recording confirmation dialog (with long-press hint).
 *    Long press during recording expands the menu.
 * 4. Slide-to-select: pressing down expands the menu, sliding over any item highlights it,
 *    and releasing executes that function.
 */
public class RadialMenuLayout extends FrameLayout {

    public static final int ACTION_SCREENSHOT = 1;
    public static final int ACTION_RECORD = 2;
    public static final int ACTION_POWER = 3;
    public static final int ACTION_VOL_UP = 4;
    public static final int ACTION_VOL_DOWN = 5;
    public static final int ACTION_ROTATE = 6;
    public static final int ACTION_APPS = 7;
    public static final int ACTION_HOME = 8;
    public static final int ACTION_BACK = 9;

    public interface OnMenuActionListener {
        void onActionSelected(int actionId);
    }

    public static class MenuItem {
        public final int actionId;
        public final int iconRes;
        public final int labelRes;
        public final String labelFallback;

        public MenuItem(int actionId, int iconRes, int labelRes, String labelFallback) {
            this.actionId = actionId;
            this.iconRes = iconRes;
            this.labelRes = labelRes;
            this.labelFallback = labelFallback;
        }
    }

    private final List<MenuItem> menuItems = new ArrayList<>();
    private final List<FrameLayout> itemViews = new ArrayList<>();
    private OnMenuActionListener actionListener;

    // Center Floating Button
    private FrameLayout centerFab;
    private ImageView ivCenterIcon;
    private LinearLayout layoutRecording;
    private View dotRecording;
    private TextView tvRecordTimer;

    // Tooltip chip
    private TextView tvTooltip;

    // Background scrim to dismiss when tapping outside
    private View scrimView;

    // Menu state
    private boolean isExpanded = false;
    private boolean isRecording = false;
    private int selectedIndex = -1;
    private boolean isSliding = false;
    private boolean wasExpandedOnDown = false;
    private float touchDownX = 0f;
    private float touchDownY = 0f;
    private float touchOffsetX = 0f;
    private float touchOffsetY = 0f;
    private int touchSlop;

    // Dragging & Edge Docking State
    private boolean isDragging = false;
    private boolean isLongPressed = false;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;

    // Dimensions (pixels)
    private int fabSizePx;
    private int itemSizePx;
    private float minRadiusPx;
    private float maxRadiusPx;

    // Positions:
    // docked: Resting position at screen edge (shows half)
    // active: Inward-shifted position during expansion
    // current: Animated position between docked and active
    private float dockedCenterX = -1;
    private float dockedCenterY = -1;
    private float activeCenterX = -1;
    private float activeCenterY = -1;
    private float currentFabCenterX = -1;
    private float currentFabCenterY = -1;

    // Golden Spiral item target positions
    private float[] itemTargetX;
    private float[] itemTargetY;

    // Expansion animator (0 = collapsed at edge, 1 = expanded inward)
    private ValueAnimator expandAnimator;
    private float expandFraction = 0f;

    private Vibrator vibrator;

    public RadialMenuLayout(@NonNull Context context) {
        super(context);
        init(context);
    }

    public RadialMenuLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public RadialMenuLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        fabSizePx = dpToPx(56);
        itemSizePx = dpToPx(44);
        minRadiusPx = dpToPx(65);
        maxRadiusPx = dpToPx(146);

        initMenuItems();
        setupViews(context);
    }

    private void initMenuItems() {
        menuItems.clear();
        // Golden spiral progression: inner items to outer items
        menuItems.add(new MenuItem(ACTION_SCREENSHOT, R.drawable.ic_screenshot, R.string.btn_screenshot, "截屏"));
        menuItems.add(new MenuItem(ACTION_RECORD,     R.drawable.ic_record,     R.string.btn_record,     "录屏"));
        menuItems.add(new MenuItem(ACTION_POWER,      R.drawable.ic_nav_power,  R.string.power_button,   "电源"));
        menuItems.add(new MenuItem(ACTION_VOL_UP,     R.drawable.ic_nav_vol_up, R.string.volup_button,   "音量+"));
        menuItems.add(new MenuItem(ACTION_VOL_DOWN,   R.drawable.ic_nav_vol_down, R.string.voldown_button, "音量-"));
        menuItems.add(new MenuItem(ACTION_ROTATE,     R.drawable.ic_nav_rotate, R.string.rotate_button,  "旋转"));
        menuItems.add(new MenuItem(ACTION_APPS,       R.drawable.ic_nav_tasks,  R.string.appswitch_button, "任务"));
        menuItems.add(new MenuItem(ACTION_HOME,       R.drawable.ic_nav_home,   R.string.home_button,    "主页"));
        menuItems.add(new MenuItem(ACTION_BACK,       R.drawable.ic_nav_back,   R.string.back_button,    "返回"));

        itemTargetX = new float[menuItems.size()];
        itemTargetY = new float[menuItems.size()];
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupViews(Context context) {
        setClipChildren(false);
        setClipToPadding(false);

        // 1. Scrim overlay
        scrimView = new View(context);
        scrimView.setBackgroundColor(Color.parseColor("#4D000000"));
        scrimView.setAlpha(0f);
        scrimView.setVisibility(GONE);
        scrimView.setOnClickListener(v -> collapse());
        addView(scrimView, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 2. Menu Items
        for (int i = 0; i < menuItems.size(); i++) {
            final MenuItem item = menuItems.get(i);

            FrameLayout itemView = new FrameLayout(context);
            itemView.setBackgroundResource(R.drawable.bg_radial_item_normal);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                itemView.setElevation(dpToPx(8));
            }

            ImageView iv = new ImageView(context);
            iv.setImageResource(item.iconRes);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int padding = dpToPx(10);
            iv.setPadding(padding, padding, padding, padding);
            itemView.addView(iv, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            itemView.setVisibility(GONE);
            itemView.setScaleX(0f);
            itemView.setScaleY(0f);
            itemView.setAlpha(0f);

            itemView.setOnClickListener(v -> {
                if (actionListener != null) {
                    actionListener.onActionSelected(item.actionId);
                }
                collapse();
            });

            addView(itemView, new LayoutParams(itemSizePx, itemSizePx));
            itemViews.add(itemView);
        }

        // 3. Center FAB
        centerFab = new FrameLayout(context);
        centerFab.setBackgroundResource(R.drawable.bg_floating_tool_button);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            centerFab.setElevation(dpToPx(14));
        }

        ivCenterIcon = new ImageView(context);
        ivCenterIcon.setImageResource(R.drawable.ic_floating_tools);
        ivCenterIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int iconPadding = dpToPx(14);
        ivCenterIcon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding);
        centerFab.addView(ivCenterIcon, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Recording indicator layout inside FAB
        layoutRecording = new LinearLayout(context);
        layoutRecording.setOrientation(LinearLayout.VERTICAL);
        layoutRecording.setGravity(Gravity.CENTER);
        layoutRecording.setVisibility(GONE);

        dotRecording = new View(context);
        dotRecording.setBackgroundResource(R.drawable.ic_record_dot);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dpToPx(7), dpToPx(7));
        dotLp.bottomMargin = dpToPx(2);
        layoutRecording.addView(dotRecording, dotLp);

        tvRecordTimer = new TextView(context);
        tvRecordTimer.setText("00:00");
        tvRecordTimer.setTextColor(Color.WHITE);
        tvRecordTimer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tvRecordTimer.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvRecordTimer.setGravity(Gravity.CENTER);
        layoutRecording.addView(tvRecordTimer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        centerFab.addView(layoutRecording, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        centerFab.setOnTouchListener((v, event) -> handleFabTouch(event));

        addView(centerFab, new LayoutParams(fabSizePx, fabSizePx));

        // 4. Tooltip chip
        tvTooltip = new TextView(context);
        tvTooltip.setBackgroundResource(R.drawable.bg_radial_tooltip);
        tvTooltip.setTextColor(Color.WHITE);
        tvTooltip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvTooltip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tvTooltip.setGravity(Gravity.CENTER);
        tvTooltip.setVisibility(GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tvTooltip.setElevation(dpToPx(16));
        }
        addView(tvTooltip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setOnMenuActionListener(OnMenuActionListener listener) {
        this.actionListener = listener;
    }

    public void setRecordingState(boolean recording, @Nullable String timeText) {
        this.isRecording = recording;
        post(() -> {
            if (recording) {
                ivCenterIcon.setVisibility(GONE);
                layoutRecording.setVisibility(VISIBLE);
                if (timeText != null) {
                    tvRecordTimer.setText(timeText);
                }
                centerFab.setBackgroundResource(R.drawable.bg_floating_tool_button_recording);
                centerFab.setAlpha(1.0f);
            } else {
                layoutRecording.setVisibility(GONE);
                ivCenterIcon.setVisibility(VISIBLE);
                centerFab.setBackgroundResource(R.drawable.bg_floating_tool_button);
                if (!isExpanded) {
                    boolean isAtEdge = (dockedCenterX <= fabSizePx / 2f || dockedCenterX >= getWidth() - fabSizePx / 2f);
                    centerFab.setAlpha(isAtEdge ? 0.75f : 1.0f);
                }
            }
        });
    }

    public boolean isExpanded() {
        return isExpanded;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isExpanded) {
            return false;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        int width = right - left;
        int height = bottom - top;
        if (width <= 0 || height <= 0) return;

        // Initial docked placement: Right edge, showing half (center at width)
        if (dockedCenterX < 0 || dockedCenterY < 0) {
            dockedCenterX = width; // Center at screen edge: half visible
            dockedCenterY = height * 0.65f;
            currentFabCenterX = dockedCenterX;
            currentFabCenterY = dockedCenterY;
            centerFab.setAlpha(0.75f);
        }

        // Clamp vertical position
        float minY = fabSizePx / 2f + dpToPx(16);
        float maxY = height - fabSizePx / 2f - dpToPx(16);
        dockedCenterY = Math.max(minY, Math.min(maxY, dockedCenterY));

        computeActiveCenter(width, height);
        computeGoldenSpiralGeometry(width, height);
        updateLayoutForFraction(expandFraction);
    }

    /**
     * Requirement 2:
     * If the tool button is docked near a screen edge, calculate an inward active position
     * shifted away from the edge so the Golden Spiral has plenty of room to fully display.
     */
    private void computeActiveCenter(int screenW, int screenH) {
        float shiftAmount = dpToPx(95);

        if (dockedCenterX >= screenW - fabSizePx / 2f) {
            // At right edge -> shift inward to the left
            activeCenterX = screenW - shiftAmount;
        } else if (dockedCenterX <= fabSizePx / 2f) {
            // At left edge -> shift inward to the right
            activeCenterX = shiftAmount;
        } else {
            // Floating in middle: ensure enough space for golden spiral radius
            float safeMargin = maxRadiusPx + fabSizePx / 2f + dpToPx(8);
            if (screenW > safeMargin * 2) {
                activeCenterX = Math.max(safeMargin, Math.min(screenW - safeMargin, dockedCenterX));
            } else {
                activeCenterX = screenW / 2f;
            }
        }

        // Clamp active Y within safe vertical margin
        float minY = dpToPx(120);
        float maxY = screenH - dpToPx(120);
        activeCenterY = Math.max(minY, Math.min(maxY, dockedCenterY));
    }

    /**
     * Requirement 2:
     * Golden Spiral (黄金螺旋) geometry calculation.
     * Arranges 9 items along a logarithmic spiral curve with increasing radius and angle.
     */
    private void computeGoldenSpiralGeometry(int screenW, int screenH) {
        int n = menuItems.size();
        if (n == 0) return;

        // Base angle points towards screen center
        float dirX = (screenW / 2f) - activeCenterX;
        float dirY = (screenH / 2f) - activeCenterY;
        double baseAngle = Math.atan2(dirY, dirX);

        // Sweep angle: 220 degrees for wide, elegant spiral distribution
        double spiralSweep = Math.toRadians(220);
        double startAngle = baseAngle - (spiralSweep / 2.0);
        double angleStep = spiralSweep / (n - 1);

        for (int i = 0; i < n; i++) {
            double angle = startAngle + i * angleStep;
            // Radius follows logarithmic/power curve from minRadius to maxRadius
            float progress = (float) i / (n - 1);
            float r = minRadiusPx + (maxRadiusPx - minRadiusPx) * (float) Math.pow(progress, 0.85);

            itemTargetX[i] = (float) (activeCenterX + r * Math.cos(angle));
            itemTargetY[i] = (float) (activeCenterY + r * Math.sin(angle));
        }
    }

    private void updateLayoutForFraction(float fraction) {
        // FAB position smoothly shifts between docked position and active position
        currentFabCenterX = dockedCenterX + (activeCenterX - dockedCenterX) * fraction;
        currentFabCenterY = dockedCenterY + (activeCenterY - dockedCenterY) * fraction;

        int fabL = (int) (currentFabCenterX - fabSizePx / 2f);
        int fabT = (int) (currentFabCenterY - fabSizePx / 2f);
        centerFab.layout(fabL, fabT, fabL + fabSizePx, fabT + fabSizePx);

        // Alpha: 1.0 when expanded/moving, 0.75 when docked at edge
        if (fraction > 0f) {
            centerFab.setAlpha(1.0f);
        } else if (!isRecording) {
            boolean isAtEdge = (dockedCenterX <= fabSizePx / 2f || dockedCenterX >= getWidth() - fabSizePx / 2f);
            centerFab.setAlpha(isAtEdge ? 0.75f : 1.0f);
        }

        centerFab.setRotation(fraction * 45f);

        // Layout items along Golden Spiral
        for (int i = 0; i < itemViews.size(); i++) {
            FrameLayout item = itemViews.get(i);
            if (fraction <= 0.01f) {
                item.setVisibility(GONE);
                continue;
            }
            item.setVisibility(VISIBLE);

            float curX = currentFabCenterX + (itemTargetX[i] - currentFabCenterX) * fraction;
            float curY = currentFabCenterY + (itemTargetY[i] - currentFabCenterY) * fraction;

            int left = (int) (curX - itemSizePx / 2f);
            int top = (int) (curY - itemSizePx / 2f);
            item.layout(left, top, left + itemSizePx, top + itemSizePx);

            float baseScale = (i == selectedIndex) ? 1.25f : 1.0f;
            item.setScaleX(fraction * baseScale);
            item.setScaleY(fraction * baseScale);
            item.setAlpha(Math.min(1.0f, fraction * 1.2f));
        }

        // Tooltip
        if (selectedIndex >= 0 && selectedIndex < itemViews.size() && fraction > 0.8f) {
            tvTooltip.setVisibility(VISIBLE);
            tvTooltip.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            );
            int tw = tvTooltip.getMeasuredWidth();
            int th = tvTooltip.getMeasuredHeight();

            int tLeft = (int) (itemTargetX[selectedIndex] - tw / 2f);
            int tTop = (int) (itemTargetY[selectedIndex] - itemSizePx / 2f - th - dpToPx(6));

            tLeft = Math.max(dpToPx(8), Math.min(getWidth() - tw - dpToPx(8), tLeft));
            tTop = Math.max(dpToPx(8), Math.min(getHeight() - th - dpToPx(8), tTop));

            tvTooltip.layout(tLeft, tTop, tLeft + tw, tTop + th);
        } else {
            tvTooltip.setVisibility(GONE);
        }
    }

    /**
     * Touch dispatcher for Center FAB.
     * Implements:
     * - Long press to drag and reposition (Requirement 1)
     * - Docking to screen edge and showing half (Requirement 1)
     * - Recording mode click -> Stop dialog; Long press -> Menu (Requirement 3)
     * - Slide to select (Requirement 4)
     */
    private boolean handleFabTouch(MotionEvent event) {
        float rawX = event.getRawX();
        float rawY = event.getRawY();

        int[] loc = new int[2];
        getLocationOnScreen(loc);
        float localX = rawX - loc[0];
        float localY = rawY - loc[1];

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchDownX = rawX;
                touchDownY = rawY;
                touchOffsetX = localX - dockedCenterX;
                touchOffsetY = localY - dockedCenterY;
                isSliding = false;
                isDragging = false;
                isLongPressed = false;
                wasExpandedOnDown = isExpanded;
                selectedIndex = -1;

                centerFab.setAlpha(1.0f);
                getParent().requestDisallowInterceptTouchEvent(true);

                // Schedule Long Press check (350ms)
                longPressRunnable = () -> {
                    isLongPressed = true;
                    performHapticTick();

                    if (isRecording) {
                        // Requirement 3: Long press during recording pops open menu!
                        if (!isExpanded) {
                            expand();
                        }
                    } else if (!isExpanded) {
                        // Requirement 1: Long press when not recording enables dragging
                        isDragging = true;
                        centerFab.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start();
                    }
                };
                uiHandler.postDelayed(longPressRunnable, 350);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = rawX - touchDownX;
                float dy = rawY - touchDownY;
                float dist = (float) Math.hypot(dx, dy);

                if (dist > touchSlop) {
                    if (isDragging) {
                        // Drag FAB across screen
                        dockedCenterX = localX - touchOffsetX;
                        dockedCenterY = localY - touchOffsetY;

                        // Vertical clamp
                        float minY = fabSizePx / 2f + dpToPx(16);
                        float maxY = getHeight() - fabSizePx / 2f - dpToPx(16);
                        dockedCenterY = Math.max(minY, Math.min(maxY, dockedCenterY));

                        currentFabCenterX = dockedCenterX;
                        currentFabCenterY = dockedCenterY;

                        int fabL = (int) (currentFabCenterX - fabSizePx / 2f);
                        int fabT = (int) (currentFabCenterY - fabSizePx / 2f);
                        centerFab.layout(fabL, fabT, fabL + fabSizePx, fabT + fabSizePx);
                        return true;
                    } else if (!isLongPressed) {
                        // Finger moved before long-press fired: cancel long press
                        cancelCustomLongPress();

                        if (!isRecording) {
                            // Slide-to-select mode: expand menu and track nearest item
                            isSliding = true;
                            if (!isExpanded) {
                                expand();
                            }
                        }
                    }
                }

                if (isSliding && isExpanded) {
                    int nearest = findNearestItem(localX, localY);
                    setSelectedItem(nearest);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelCustomLongPress();
                getParent().requestDisallowInterceptTouchEvent(false);

                if (isDragging) {
                    // Finished dragging: Snap to nearest edge showing half (Requirement 1)
                    finishDragAndSnapToEdge();
                    isDragging = false;
                    isLongPressed = false;
                    return true;
                }

                if (isRecording) {
                    // Requirement 3:
                    // If not long-pressed, this was a tap on the recording button -> show stop dialog!
                    if (!isLongPressed && !isExpanded) {
                        if (actionListener != null) {
                            actionListener.onActionSelected(ACTION_RECORD);
                        }
                    }
                } else {
                    if (isSliding && selectedIndex >= 0) {
                        // Slide-selected an item: execute!
                        int targetAction = menuItems.get(selectedIndex).actionId;
                        performHapticTick();
                        collapse();
                        if (actionListener != null) {
                            actionListener.onActionSelected(targetAction);
                        }
                    } else if (!isSliding) {
                        // Normal tap: toggle expand/collapse
                        if (wasExpandedOnDown) {
                            collapse();
                        } else {
                            expand();
                        }
                    } else {
                        // Slid to blank area: collapse
                        collapse();
                    }
                }

                isSliding = false;
                isLongPressed = false;
                setSelectedItem(-1);
                return true;
        }
        return false;
    }

    private void cancelCustomLongPress() {
        if (longPressRunnable != null) {
            uiHandler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    /**
     * Requirement 1:
     * Only snaps to screen edge and displays half when moved close to the edge.
     * Otherwise stays right where released.
     */
    private void finishDragAndSnapToEdge() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        centerFab.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();

        float edgeThreshold = dpToPx(48); // Near-edge snap threshold

        if (dockedCenterX < edgeThreshold) {
            // Moved to left edge: snap to left edge (center at 0) -> displays right half
            animateSnapToX(0f, true);
        } else if (dockedCenterX > width - edgeThreshold) {
            // Moved to right edge: snap to right edge (center at width) -> displays left half
            animateSnapToX((float) width, true);
        } else {
            // Released in other places: stay right where released!
            float minX = fabSizePx / 2f + dpToPx(8);
            float maxX = width - fabSizePx / 2f - dpToPx(8);
            float minY = fabSizePx / 2f + dpToPx(8);
            float maxY = height - fabSizePx / 2f - dpToPx(8);

            dockedCenterX = Math.max(minX, Math.min(maxX, dockedCenterX));
            dockedCenterY = Math.max(minY, Math.min(maxY, dockedCenterY));
            currentFabCenterX = dockedCenterX;
            currentFabCenterY = dockedCenterY;

            int fabL = (int) (currentFabCenterX - fabSizePx / 2f);
            int fabT = (int) (currentFabCenterY - fabSizePx / 2f);
            centerFab.layout(fabL, fabT, fabL + fabSizePx, fabT + fabSizePx);

            centerFab.animate().alpha(1.0f).setDuration(150).start();
        }
    }

    private void animateSnapToX(float targetX, boolean dockedAtEdge) {
        ValueAnimator snapAnim = ValueAnimator.ofFloat(dockedCenterX, targetX);
        snapAnim.setDuration(220);
        snapAnim.setInterpolator(new DecelerateInterpolator());
        snapAnim.addUpdateListener(animation -> {
            dockedCenterX = (float) animation.getAnimatedValue();
            currentFabCenterX = dockedCenterX;
            int fabL = (int) (currentFabCenterX - fabSizePx / 2f);
            int fabT = (int) (currentFabCenterY - fabSizePx / 2f);
            centerFab.layout(fabL, fabT, fabL + fabSizePx, fabT + fabSizePx);
        });
        snapAnim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!isRecording && !isExpanded) {
                    centerFab.animate().alpha(dockedAtEdge ? 0.75f : 1.0f).setDuration(200).start();
                }
            }
        });
        snapAnim.start();
    }

    private int findNearestItem(float touchX, float touchY) {
        float distFromFab = (float) Math.hypot(touchX - activeCenterX, touchY - activeCenterY);

        if (distFromFab < dpToPx(30)) {
            return -1; // Center deadzone
        }

        int closestIdx = -1;
        float minDist = Float.MAX_VALUE;
        float hitThreshold = dpToPx(52); // generous hit radius

        for (int i = 0; i < itemViews.size(); i++) {
            float ix = itemTargetX[i];
            float iy = itemTargetY[i];
            float d = (float) Math.hypot(touchX - ix, touchY - iy);
            if (d < minDist) {
                minDist = d;
                closestIdx = i;
            }
        }

        return (minDist <= hitThreshold) ? closestIdx : -1;
    }

    private void setSelectedItem(int index) {
        if (selectedIndex == index) return;

        int oldIndex = selectedIndex;
        selectedIndex = index;

        if (oldIndex >= 0 && oldIndex < itemViews.size()) {
            FrameLayout oldView = itemViews.get(oldIndex);
            oldView.setBackgroundResource(R.drawable.bg_radial_item_normal);
            oldView.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start();
        }

        if (index >= 0 && index < itemViews.size()) {
            FrameLayout newView = itemViews.get(index);
            newView.setBackgroundResource(R.drawable.bg_radial_item_selected);
            newView.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).start();

            MenuItem mi = menuItems.get(index);
            String label = getContext().getString(mi.labelRes);
            if (label == null || label.isEmpty() || label.length() > 6) {
                label = mi.labelFallback;
            }
            tvTooltip.setText(label);
            performHapticTick();
        }

        updateLayoutForFraction(expandFraction);
    }

    public void expand() {
        if (isExpanded) return;
        isExpanded = true;

        if (expandAnimator != null) expandAnimator.cancel();

        // Calculate active shifted position & golden spiral geometry
        computeActiveCenter(getWidth(), getHeight());
        computeGoldenSpiralGeometry(getWidth(), getHeight());

        scrimView.setVisibility(VISIBLE);
        scrimView.animate().alpha(1.0f).setDuration(220).start();

        for (FrameLayout item : itemViews) {
            item.setVisibility(VISIBLE);
        }

        expandAnimator = ValueAnimator.ofFloat(expandFraction, 1.0f);
        expandAnimator.setDuration(280);
        expandAnimator.setInterpolator(new OvershootInterpolator(1.25f));
        expandAnimator.addUpdateListener(animation -> {
            expandFraction = (float) animation.getAnimatedValue();
            updateLayoutForFraction(expandFraction);
        });
        expandAnimator.start();
    }

    public void collapse() {
        if (!isExpanded) return;
        isExpanded = false;
        setSelectedItem(-1);

        if (expandAnimator != null) expandAnimator.cancel();

        scrimView.animate().alpha(0f).setDuration(180).withEndAction(() -> scrimView.setVisibility(GONE)).start();

        expandAnimator = ValueAnimator.ofFloat(expandFraction, 0f);
        expandAnimator.setDuration(220);
        expandAnimator.setInterpolator(new DecelerateInterpolator());
        expandAnimator.addUpdateListener(animation -> {
            expandFraction = (float) animation.getAnimatedValue();
            updateLayoutForFraction(expandFraction);
        });
        expandAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                for (FrameLayout item : itemViews) {
                    item.setVisibility(GONE);
                }
                tvTooltip.setVisibility(GONE);
                if (!isRecording) {
                    boolean isAtEdge = (dockedCenterX <= fabSizePx / 2f || dockedCenterX >= getWidth() - fabSizePx / 2f);
                    centerFab.animate().alpha(isAtEdge ? 0.75f : 1.0f).setDuration(150).start();
                }
            }
        });
        expandAnimator.start();
    }

    private void performHapticTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        } catch (Exception ignored) {}
    }

    private int dpToPx(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp,
                getResources().getDisplayMetrics()
        );
    }
}
