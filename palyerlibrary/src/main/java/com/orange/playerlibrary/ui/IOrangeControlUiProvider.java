package com.orange.playerlibrary.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * 控制栏 UI 提供者。
 *
 * <p>用于把「控制栏视觉实现」从播放器内核解耦：{@code palyerlibrary} 只定义本接口，
 * 不依赖任何具体 UI 实现。宿主（如 {@code animeko-ui} 模块）实现本接口并注册，
 * 播放器即可用 Compose 控制栏替换既有的 XML 组件式控制栏，而 <b>旧 UI 代码不做改动</b>。</p>
 *
 * <p>注册方式：{@link OrangeControlUiRegistry#setProvider(IOrangeControlUiProvider)}。
 * 未注册时播放器走既有的 {@code VodControlView}/{@code TitleView}，行为零变化。</p>
 */
public interface IOrangeControlUiProvider {

    /**
     * 创建控制栏 View。
     *
     * @param context 上下文
     * @param host    播放器提供的能力集（读状态 + 回调），实现方不应持有超出 View 生命周期的引用
     * @return 控制栏 View；返回 {@code null} 表示创建失败，播放器将回退到旧 UI
     */
    @Nullable
    View createControlView(@NonNull Context context, @NonNull ControlUiHost host);

    /**
     * 播放器提供给控制栏 UI 的能力集。
     *
     * <p>只暴露 UI 需要的最小面：播放状态、进度、以及用户操作的回调。
     * 具体播放器内核（GSY/ijk/Exo/ali/mpv）由播放器侧适配，UI 层不感知。</p>
     */
    interface ControlUiHost {
        boolean isPlaying();
        long getPositionMs();
        long getDurationMs();

        boolean isDanmakuEnabled();
        float getSpeed();
        boolean isFullscreen();

        /** 主标题（如剧名/视频名）。 */
        String getTitle();

        /** 副标题（如「天地万物」）。无则为 {@code null}。 */
        String getSubtitle();

        /** 集数序号（如「01」）。无则为 {@code null}，由 UI 决定是否给默认值。 */
        String getEpisodeNumber();

        /** 手势是否已锁定（锁定后只显示解锁按钮）。 */
        boolean isLocked();

        /** 切换手势锁定。 */
        void onToggleLock();

        /** 控制器（顶栏 + 底栏）当前是否可见。 */
        boolean isControllerVisible();

        /** 切换控制器显隐（点击画面）。 */
        void onToggleControllerVisibility();

        /** 截取当前画面并保存到相册。 */
        void onScreenshot();

        /** 进度条拖动中（0..1）。 */
        void onSeekTo(float progress);

        /** 进度条拖动结束。 */
        void onSeekFinished();

        void onTogglePlay();
        void onNextEpisode();
        void onToggleDanmaku();
        /**
         * 发送弹幕。
         *
         * @param color    颜色（RGB，不含 alpha）
         * @param location 位置，取值见 {@code IDanmakuController.LOCATION_*}
         */
        void onSendDanmaku(@NonNull String text, int color, int location);
        void onSelectEpisode();

        /** 设置播放倍速（倍速控件拖动/提交时调用）。 */
        void onSetSpeed(float speed);

        void onBack();
        void onToggleFullscreen();

        /** 由播放器提供：控制栏 View 的父容器（用于挂载弹窗等）。 */
        @Nullable
        ViewGroup getOverlayContainer();

        // ===== 播放界面状态（加载/错误/起播/播完）=====

        /** 播放器 UI 当前阶段，取值见 {@code IOrangePlaybackUiState.STAGE_*}。 */
        int getPlaybackStage();

        /**
         * 错误提示文案；无错误时返回 {@code null}。
         *
         * <p>只返回文案，具体展示交给 UI 层，播放器不预设错误界面的样子。</p>
         */
        @Nullable
        String getErrorMessage();

        /** 缓冲/加载时是否显示进度圈。 */
        boolean isBufferingProgressVisible();

        /** 缓冲时的下载速度（字节/秒）；未知时为 0。 */
        long getBufferingSpeedBytesPerSec();

        /** 用户点「重试」：重新加载当前地址。 */
        void onRetry();

        /** 用户点「开始播放」（起播前）。 */
        void onStartPlay();

        /** 用户点「重播」（播放完成后）。 */
        void onReplay();

        // ===== 进度条拖动预览 =====

        /**
         * 取指定位置的视频帧，用于进度条拖动预览。
         *
         * <p>返回 {@code null} 表示该位置暂不可用（调用方应保留上一帧，避免闪烁）。
         * 实现方可能阻塞，调用方需放在后台线程。</p>
         *
         * @param positionMs 目标位置（毫秒）
         */
        @Nullable
        android.graphics.Bitmap getPreviewFrame(long positionMs);

        // ===== 顶栏（标题 + 功能按钮）=====
        //
        // 未注册自定义 UI 时，这些按钮由 XML 的 TitleView 提供。注册后 TitleView
        // 不再创建，若不给 UI 层这组入口，设置/投屏/小窗/嗅探就会整体消失。

        /**
         * 顶栏功能按钮。
         *
         * <p>返回的顺序即展示顺序：{@link TopBarAction#isPrimary()} 为 true 的直接在顶栏
         * 显示图标，其余收进溢出菜单。</p>
         */
        @NonNull
        List<TopBarAction> getTopBarActions();

        /** 执行某个顶栏动作。未知 id 应被忽略。 */
        void onTopBarAction(@NonNull String actionId);

        /** 嗅探面板是否处于展开状态（用于按钮高亮）。 */
        boolean isSniffingPanelShowing();
    }

    /**
     * 顶栏功能按钮。
     *
     * <p>实现 {@code equals}/{@code hashCode} 是必要的：UI 层每次刷新都会重新取一次
     * 动作列表，若按引用比较，等值列表也会被判为「变化」，导致顶栏每秒重组。</p>
     */
    final class TopBarAction {
        /** 设置（播放器设置对话框）。 */
        public static final String ID_SETTINGS = "settings";
        /** 投屏（DLNA）。 */
        public static final String ID_CAST = "cast";
        /** 小窗（画中画）。 */
        public static final String ID_PIP = "pip";
        /** 嗅探（视频嗅探面板）。 */
        public static final String ID_SNIFFING = "sniffing";
        /** 跳过片头片尾。 */
        public static final String ID_SKIP_OP_ED = "skip_op_ed";
        /** 弹幕设置。 */
        public static final String ID_DANMAKU_SETTINGS = "danmaku_settings";
        /** 字幕。 */
        public static final String ID_SUBTITLE = "subtitle";

        @NonNull
        private final String id;
        @NonNull
        private final String label;
        /** true = 顶栏直接显示图标；false = 收进溢出菜单。 */
        private final boolean primary;

        public TopBarAction(@NonNull String id, @NonNull String label, boolean primary) {
            this.id = id;
            this.label = label;
            this.primary = primary;
        }

        @NonNull
        public String getId() {
            return id;
        }

        @NonNull
        public String getLabel() {
            return label;
        }

        public boolean isPrimary() {
            return primary;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof TopBarAction)) {
                return false;
            }
            TopBarAction other = (TopBarAction) o;
            return primary == other.primary
                    && id.equals(other.id)
                    && label.equals(other.label);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * id.hashCode() + label.hashCode()) + (primary ? 1 : 0);
        }
    }
}
