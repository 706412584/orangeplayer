package com.orange.playerlibrary;

import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Collections;

/**
 * 悬浮环形进度（绿色圆环 + 百分比），挂在播放器上。
 *
 * 与模态 ProgressDialog 的区别：不弹窗、不调暗画面，适合自动触发的后台任务
 * （ASR 识别 / HLS 下载）展示进度。圆环 drawable 复用 progress_circle_bg / progress_circle_fg。
 *
 * 交互：
 * - 可拖动：按住拖动到任意位置（仅拦截落在控件范围内的触摸，控件之外的手势/控制栏不受影响）
 * - 贴边自动隐藏：松手时贴近容器左右边（≤{@link #SNAP_EDGE_DP}dp）→ 吸附该边，
 *   隐藏整个进度 UI，改显一个纯色把手（宽度 = 完整 UI 宽的 {@link #HANDLE_WIDTH_RATIO}）；
 *   点按或拖动把手即恢复完整 UI。
 * - 位置记忆：拖动过的位置在后续任务中保留（不回到默认右侧中部）。
 */
public class FloatingRingProgress {

    /** 贴边隐藏时纯色把手的宽度比例（相对完整 UI 宽度） */
    private static final float HANDLE_WIDTH_RATIO = 0.10f;

    /** 把手触摸区最小宽/高(dp)：10% 视觉宽在窄控件上仅几 dp，手指点不到 */
    private static final int MIN_TOUCH_WIDTH_DP = 32;
    private static final int MIN_TOUCH_HEIGHT_DP = 40;

    /** 把手高度比例（相对完整 UI 高度），比 UI 矮一些更像把手 */
    private static final float HANDLE_HEIGHT_RATIO = 0.35f;

    /** 松手时距容器左右边小于该值(dp)判定为「贴边」，触发隐藏 */
    private static final int SNAP_EDGE_DP = 32;

    private final Context mContext;
    private final View mRoot;
    private final View mContent;
    private final View mHandle;
    private final ProgressBar mFg;
    private final TextView mText;
    private final TextView mHint;
    private final int mTouchSlop;

    /** 绝对定位（TOP|START + margin），拖动/贴边都直接改 margin */
    private FrameLayout.LayoutParams mLp;
    private ViewGroup mAnchor;

    private boolean mHidden;
    /** 隐藏在哪一侧（true=右） */
    private boolean mHideRight;
    /** 完整 UI 的尺寸（隐藏时 root 收窄到把手，需记住原尺寸用于定把手大小与恢复） */
    private int mContentWidth;
    private int mContentHeight;
    /** 用户是否拖过位置（拖过则后续任务保留落点，不再回默认位置） */
    private boolean mHasCustomPosition;

    /** 无操作多久后自动贴边隐藏（进度更新不算操作） */
    private static final long AUTO_HIDE_DELAY_MS = 5000;

    /** 自动贴边：贴到距离更近的一侧（构造器内初始化，需引用 mRoot） */
    private final Runnable mAutoHideRunnable;

    /** 容器尺寸变化（旋转/全屏切换）后重新约束，避免控件彻底跑出可视区 */
    private final View.OnLayoutChangeListener mLayoutListener =
            (v, l, t, r, b, ol, ot, orr, ob) -> clampToAnchor();

    public FloatingRingProgress(Context context) {
        mContext = context;
        mRoot = LayoutInflater.from(context).inflate(R.layout.layout_asr_ring_progress, null);
        mContent = mRoot.findViewById(R.id.asr_ring_content);
        mHandle = mRoot.findViewById(R.id.asr_ring_handle);
        mFg = mRoot.findViewById(R.id.asr_ring_fg);
        mText = mRoot.findViewById(R.id.asr_ring_text);
        mHint = mRoot.findViewById(R.id.asr_ring_hint);
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // 可拖动需要接住落在控件内的触摸；控件范围很小，不影响播放器其他区域的手势
        mRoot.setClickable(true);
        mRoot.setFocusable(false);
        mRoot.setOnTouchListener(new DragTouchListener());
        mAutoHideRunnable = this::autoHideToNearestEdge;
    }

    /** 自动贴边：隐藏到距离更近的一侧 */
    private void autoHideToNearestEdge() {
        if (mHidden || mAnchor == null || mLp == null) {
            return;
        }
        int left = mLp.leftMargin;
        int right = mAnchor.getWidth() - (left + mRoot.getWidth());
        hideAtEdge(right <= left);
    }

    /**
     * 挂到播放器容器（默认右侧中部，避开顶部标题栏与底部字幕/控制栏）。
     * 重复调用会先摘除再挂载，避免重复添加。
     */
    public void attach(ViewGroup anchor) {
        if (anchor == null) {
            return;
        }
        try {
            if (mRoot.getParent() instanceof ViewGroup) {
                ((ViewGroup) mRoot.getParent()).removeView(mRoot);
            }
        } catch (Exception ignored) {
            // 旧容器可能已随 Activity 销毁
        }
        if (mAnchor != null) {
            mAnchor.removeOnLayoutChangeListener(mLayoutListener);
        }
        mAnchor = anchor;
        mLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.gravity = Gravity.TOP | Gravity.START;
        anchor.addView(mRoot, mLp);
        anchor.addOnLayoutChangeListener(mLayoutListener);

        // 新任务恢复完整 UI（保留用户拖过的落点）
        restoreContent();
        mRoot.post(() -> {
            if (mHasCustomPosition) {
                clampToAnchor();
            } else {
                applyDefaultPosition();
            }
            scheduleAutoHide();
        });
    }

    public void show() {
        mRoot.setVisibility(View.VISIBLE);
        scheduleAutoHide();
    }

    public void setProgress(int percent) {
        int p = Math.max(0, Math.min(100, percent));
        if (mFg != null) {
            mFg.setProgress(p);
        }
        if (mText != null) {
            mText.setText(p + "%");
        }
    }

    public void setHint(String hint) {
        if (mHint != null && hint != null) {
            mHint.setText(hint);
        }
    }

    public void dismiss() {
        cancelAutoHide();
        if (mAnchor != null) {
            mAnchor.removeOnLayoutChangeListener(mLayoutListener);
            mAnchor = null;
        }
        mHidden = false;
        if (mRoot.getParent() instanceof ViewGroup) {
            ((ViewGroup) mRoot.getParent()).removeView(mRoot);
        }
    }

    // ===== 位置 =====

    /** 默认落点：右侧中部，留 12dp 边距 */
    private void applyDefaultPosition() {
        if (mAnchor == null || mLp == null || mAnchor.getWidth() == 0 || mRoot.getWidth() == 0) {
            return;
        }
        int margin = dp(12);
        mHidden = false;
        applyLp(Math.max(0, mAnchor.getWidth() - mRoot.getWidth() - margin),
                Math.max(0, (mAnchor.getHeight() - mRoot.getHeight()) / 2),
                mLp.width, mLp.height);
    }

    /** 容器尺寸变化后把控件重新约束回可见范围；隐藏态按新尺寸重算把手位置 */
    private void clampToAnchor() {
        if (mAnchor == null || mLp == null) {
            return;
        }
        int anchorW = mAnchor.getWidth();
        int anchorH = mAnchor.getHeight();
        if (anchorW == 0 || mRoot.getWidth() == 0) {
            return;
        }
        if (mHidden) {
            // 隐藏态：root 宽=触摸区宽，重新贴到该侧并保证纵向可见
            applyLp(mHideRight ? Math.max(0, anchorW - mRoot.getWidth()) : 0,
                    clamp(mLp.topMargin, 0, Math.max(0, anchorH - mRoot.getHeight())),
                    mLp.width, mLp.height);
        } else {
            applyLp(clamp(mLp.leftMargin, 0, Math.max(0, anchorW - mRoot.getWidth())),
                    clamp(mLp.topMargin, 0, Math.max(0, anchorH - mRoot.getHeight())),
                    mLp.width, mLp.height);
        }
    }

    /**
     * 统一的 LayoutParams 应用入口：值未变化时不调 setLayoutParams。
     * 本方法会被 OnLayoutChangeListener 调用，无条件设置会 requestLayout →
     * 再次 layout → 死循环（真机实测刷屏 "requestLayout() improperly called
     * ... during layout"，UI 永不 idle，uiautomator dump 直接失败）。
     */
    private void applyLp(int left, int top, int width, int height) {
        if (mLp.leftMargin == left && mLp.topMargin == top
                && mLp.width == width && mLp.height == height) {
            return;
        }
        mLp.leftMargin = left;
        mLp.topMargin = top;
        mLp.width = width;
        mLp.height = height;
        mRoot.setLayoutParams(mLp);
    }

    /** 贴边隐藏：隐藏完整 UI，改显纯色把手 */
    private void hideAtEdge(boolean right) {
        if (mAnchor == null || mLp == null) {
            return;
        }
        // 记录完整 UI 尺寸（此时 content 仍可见）
        if (mContent.getWidth() > 0) {
            mContentWidth = mContent.getWidth();
            mContentHeight = mContent.getHeight();
        }
        mHideRight = right;

        int handleW = Math.max(1, (int) (mContentWidth * HANDLE_WIDTH_RATIO));
        int handleH = Math.max(1, (int) (mContentHeight * HANDLE_HEIGHT_RATIO));
        // 10% 视觉宽在窄控件上仅几 dp，手指点不到；触摸区兜底到 32dp，
        // 视觉上仍只显示 10% 宽的纯色把手（root 空白区透明）。
        int touchW = Math.max(handleW, dp(MIN_TOUCH_WIDTH_DP));
        int touchH = Math.max(handleH, dp(MIN_TOUCH_HEIGHT_DP));

        FrameLayout.LayoutParams handleLp = (FrameLayout.LayoutParams) mHandle.getLayoutParams();
        handleLp.width = handleW;
        handleLp.height = handleH;
        handleLp.gravity = Gravity.CENTER_VERTICAL | (right ? Gravity.END : Gravity.START);
        mHandle.setLayoutParams(handleLp);

        mContent.setVisibility(View.GONE);
        mHandle.setVisibility(View.VISIBLE);

        mHidden = true;
        applyLp(right ? Math.max(0, mAnchor.getWidth() - touchW) : 0,
                clamp(mLp.topMargin, 0, Math.max(0, mAnchor.getHeight() - touchH)),
                touchW, touchH);
        updateGestureExclusion();
    }

    /** 恢复完整 UI（点按把手 / 新任务开始） */
    private void restoreContent() {
        if (!mHidden) {
            return;
        }
        mHidden = false;
        mHandle.setVisibility(View.GONE);
        mContent.setVisibility(View.VISIBLE);
        // 用记录的完整宽度立即定位，不等重新布局（否则会读到把手宽度 → 位置错乱）
        int rootW = mContentWidth > 0 ? mContentWidth : mRoot.getWidth();
        int rootH = mContentHeight > 0 ? mContentHeight : mRoot.getHeight();
        int anchorW = mAnchor != null ? mAnchor.getWidth() : 0;
        int anchorH = mAnchor != null ? mAnchor.getHeight() : 0;
        applyLp(mHideRight ? Math.max(0, anchorW - rootW) : 0,
                clamp(mLp.topMargin, 0, Math.max(0, anchorH - rootH)),
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        updateGestureExclusion();
        scheduleAutoHide();
    }

    /**
     * 贴边态注册系统手势排除区（API 29+）：把手位于屏幕最边缘，
     * 否则触摸会被系统返回手势/边沿手势吞掉（真机实测：点把手无响应，
     * 事件穿透到播放器触发了全屏切换）。恢复完整 UI 时清空。
     */
    private void updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        mRoot.post(() -> {
            if (mRoot.getWidth() == 0) {
                return;
            }
            if (mHidden) {
                mRoot.setSystemGestureExclusionRects(Collections.singletonList(
                        new Rect(0, 0, mRoot.getWidth(), mRoot.getHeight())));
            } else {
                mRoot.setSystemGestureExclusionRects(Collections.emptyList());
            }
        });
    }

    /** 松手结算：贴边则隐藏，否则停在落点 */
    private void settleAfterDrag() {
        if (mAnchor == null || mLp == null) {
            return;
        }
        int anchorW = mAnchor.getWidth();
        int rootW = mRoot.getWidth();
        int edge = dp(SNAP_EDGE_DP);
        int left = mLp.leftMargin;
        int right = anchorW - (left + rootW);
        if (left <= edge) {
            hideAtEdge(false);
        } else if (right <= edge) {
            hideAtEdge(true);
        }
        mHasCustomPosition = true;
    }

    /** 显示完整 UI 后启动自动贴边计时（重复调用只保留最后一次） */
    private void scheduleAutoHide() {
        mRoot.removeCallbacks(mAutoHideRunnable);
        if (!mHidden) {
            mRoot.postDelayed(mAutoHideRunnable, AUTO_HIDE_DELAY_MS);
        }
    }

    private void cancelAutoHide() {
        mRoot.removeCallbacks(mAutoHideRunnable);
    }

    private int dp(int value) {
        return (int) (value * mContext.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 拖动 + 贴边手势 */
    private final class DragTouchListener implements View.OnTouchListener {

        private float mDownRawX;
        private float mDownRawY;
        private int mStartLeft;
        private int mStartTop;
        private boolean mDragging;

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (mAnchor == null || mLp == null) {
                return false;
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mDownRawX = event.getRawX();
                    mDownRawY = event.getRawY();
                    cancelAutoHide();
                    // 把手态：按下即恢复完整 UI（轻点恢复；拖动则从恢复位继续）
                    if (mHidden) {
                        restoreContent();
                    }
                    mStartLeft = mLp.leftMargin;
                    mStartTop = mLp.topMargin;
                    mDragging = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    int dx = (int) (event.getRawX() - mDownRawX);
                    int dy = (int) (event.getRawY() - mDownRawY);
                    if (!mDragging
                            && (Math.abs(dx) > mTouchSlop || Math.abs(dy) > mTouchSlop)) {
                        mDragging = true;
                    }
                    if (!mDragging) {
                        return true;
                    }
                    int anchorW = mAnchor.getWidth();
                    int anchorH = mAnchor.getHeight();
                    int rootW = mRoot.getWidth();
                    int rootH = mRoot.getHeight();
                    applyLp(clamp(mStartLeft + dx, 0, Math.max(0, anchorW - rootW)),
                            clamp(mStartTop + dy, 0, Math.max(0, anchorH - rootH)),
                            mLp.width, mLp.height);
                    return true;
                }

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mDragging) {
                        settleAfterDrag();
                    }
                    scheduleAutoHide();
                    return true;

                default:
                    return false;
            }
        }
    }
}
