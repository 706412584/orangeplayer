package com.orange.animekoui

import android.content.Context
import android.widget.FrameLayout
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import com.orange.playerlibrary.ui.IOrangePlaybackUiState
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.ui.foundation.TextWithBorder
import me.him188.ani.app.ui.foundation.navigation.BackDispatcher
import me.him188.ani.app.ui.foundation.navigation.LocalBackDispatcher
import me.him188.ani.app.ui.foundation.theme.AniTheme
import me.him188.ani.app.ui.danmaku.DanmakuSendStyle
import me.him188.ani.app.ui.danmaku.DanmakuStylePicker
import me.him188.ani.app.ui.subject.episode.video.topbar.EpisodePlayerTitle
import me.him188.ani.app.videoplayer.ui.ControllerVisibility
import me.him188.ani.app.videoplayer.ui.PlayerControllerState
import me.him188.ani.app.videoplayer.ui.PlayerFullscreenState
import me.him188.ani.app.videoplayer.ui.VideoScaffold
import me.him188.ani.app.videoplayer.ui.gesture.GestureLock
import me.him188.ani.app.videoplayer.ui.gesture.ScreenshotButton
import me.him188.ani.app.videoplayer.ui.progress.MediaProgressFramePreviewState
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerBar
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults
import me.him188.ani.app.videoplayer.ui.progress.PlayerProgressSliderState
import me.him188.ani.app.videoplayer.ui.top.PlayerTopBar

/**
 * Compose 控制栏的宿主 View。
 *
 * <p>把 animeko 的**原版** [VideoScaffold] + [PlayerTopBar] + [PlayerControllerBar]
 * 包成一个普通 Android View，使既有的 {@code OrangevideoView}（Java）可以像挂 XML 组件一样
 * 把它 addView 进来，从而做到「不改旧 UI 代码，只换注册入口」。</p>
 *
 * <p>布局与视觉全部来自 animeko 原版：[VideoScaffold] 负责 16:9 框、上下渐变遮罩、
 * 以及给底栏提供白色内容色；本类只负责把橘子播放器的状态喂给它们，
 * 因此不需要手写任何布局。</p>
 *
 * <p>播放状态通过 [ControlHost] 接口回传，由宿主（橘子播放器）实现，
 * 避免 Compose 层直接依赖具体播放器内核。</p>
 */
class AnimekoControlViewHost(context: Context) : FrameLayout(context),
    com.orange.playerlibrary.ui.IOrangeControlUiView {

    /** 宿主（橘子播放器）需要提供的最小能力集。 */
    interface ControlHost {
        fun isPlaying(): Boolean
        fun positionMs(): Long
        fun durationMs(): Long
        fun danmakuEnabled(): Boolean
        fun speed(): Float
        fun onSeekTo(progress: Float)
        fun onSeekFinished()
        fun onTogglePlay()
        fun onNextEpisode()
        fun onToggleDanmaku()
        fun onSendDanmaku(text: String, color: Int, location: Int)
        fun onSelectEpisode()
        fun onSetSpeed(speed: Float)
        fun onBack()
        fun isFullscreen(): Boolean
        fun onToggleFullscreen()
        fun title(): String?
        fun subtitle(): String?
        fun episodeNumber(): String?
        fun isLocked(): Boolean
        fun onToggleLock()
        fun isControllerVisible(): Boolean
        fun onToggleControllerVisibility()

        // ===== 播放界面状态（加载/错误/起播/播完）=====
        /** 播放器 UI 当前阶段，取值见 IOrangePlaybackUiState.STAGE_*。 */
        fun playbackStage(): Int
        /** 错误提示文案；无错误时为 null。 */
        fun errorMessage(): String?
        /** 加载/缓冲时是否显示进度圈。 */
        fun bufferingProgressVisible(): Boolean
        /** 缓冲下载速度（字节/秒）；未知为 0。 */
        fun bufferingSpeedBytesPerSec(): Long
        fun onRetry()
        fun onStartPlay()
        fun onReplay()
        /** 取指定位置的预览帧（阻塞，调用方放后台线程）；不可用时返回 null。 */
        fun getPreviewFrame(positionMs: Long): android.graphics.Bitmap?
        fun onScreenshot()

        /** 顶栏功能按钮（设置/投屏/小窗/嗅探）。 */
        fun topBarActions(): List<com.orange.playerlibrary.ui.IOrangeControlUiProvider.TopBarAction>

        /** 执行顶栏动作。 */
        fun onTopBarAction(actionId: String)

        /** 嗅探面板是否展开（用于按钮高亮）。 */
        fun isSniffingPanelShowing(): Boolean
    }

    private var host: ControlHost? = null
    private val composeView = ComposeView(context)

    // 供 Compose 读取的可观察状态
    private val isPlayingState = mutableStateOf(false)
    private val positionState = mutableLongStateOf(0L)
    private val durationState = mutableLongStateOf(0L)
    private val danmakuState = mutableStateOf(true)
    private val speedState = mutableFloatStateOf(1f)
    private val fullscreenState = mutableStateOf(false)
    private val titleState = mutableStateOf<String?>(null)
    private val subtitleState = mutableStateOf<String?>(null)
    private val episodeNumberState = mutableStateOf<String?>(null)
    private val lockedState = mutableStateOf(false)
    private val controllerVisibleState = mutableStateOf(true)
    private val playbackStageState = mutableIntStateOf(0)
    private val errorMessageState = mutableStateOf<String?>(null)
    private val bufferingProgressState = mutableStateOf(false)
    private val bufferingSpeedState = mutableLongStateOf(0L)

    // 顶栏功能按钮由播放器提供；refresh 时重读一次。
    private val topBarActionsState =
        mutableStateOf(emptyList<com.orange.playerlibrary.ui.IOrangeControlUiProvider.TopBarAction>())

    init {
        addView(composeView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** 绑定宿主并开始渲染。 */
    fun bind(host: ControlHost) {
        this.host = host
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool,
        )
        composeView.setContent {
            AniTheme(darkModeOverride = DarkMode.DARK) {
                // 原版 PlayerTopBar 的返回按钮读 LocalBackDispatcher，
                // 这里接到橘子的返回回调上。
                CompositionLocalProvider(
                    LocalBackDispatcher provides BackDispatcher { host.onBack() },
                ) {
                    AnimekoControlContent(host)
                }
            }
        }
    }

    /**
     * 刷新状态（播放器在播放状态变化/进度更新时调用）。
     *
     * 实现 [com.orange.playerlibrary.ui.IOrangeControlUiView]，
     * 播放器据此在进度回调里把最新状态推进来。
     */
    override fun onRefresh() {
        val h = host ?: return
        isPlayingState.value = h.isPlaying()
        positionState.value = h.positionMs()
        durationState.value = h.durationMs()
        danmakuState.value = h.danmakuEnabled()
        speedState.value = h.speed()
        fullscreenState.value = h.isFullscreen()
        titleState.value = h.title()
        subtitleState.value = h.subtitle()
        episodeNumberState.value = h.episodeNumber()
        lockedState.value = h.isLocked()
        controllerVisibleState.value = h.isControllerVisible()
        playbackStageState.value = h.playbackStage()
        errorMessageState.value = h.errorMessage()
        bufferingProgressState.value = h.bufferingProgressVisible()
        bufferingSpeedState.value = h.bufferingSpeedBytesPerSec()
        topBarActionsState.value = h.topBarActions()
    }

    @Composable
    private fun AnimekoControlContent(h: ControlHost) {
        // 全屏状态：单一真值 + 幂等入口（animeko 原版 PlayerFullscreenState 的语义）。
        val fsState = remember {
            object : PlayerFullscreenState {
                override val isFullscreen: Boolean get() = fullscreenState.value
                override fun request(fullscreen: Boolean) = h.onToggleFullscreen()
            }
        }
        // 控制器常显：橘子播放器的控制栏由自身的显隐逻辑管理，
        // 这里不让 animeko 的 5 秒自动隐藏再插一手。
        val controllerState = remember {
            PlayerControllerState(ControllerVisibility.Visible)
        }
        // 显隐以播放器的真值为准：点击画面 / 自动隐藏都会改它，
        // 这里同步过来，避免两套显隐逻辑各说各话。
        LaunchedEffect(controllerVisibleState.value) {
            controllerState.setFullVisible(controllerVisibleState.value)
        }
        val expanded = fullscreenState.value

        VideoScaffold(
            expanded = expanded,
            controllerState = controllerState,
            // 锁定时隐藏顶栏/底栏/右侧按钮，只留解锁按钮（与原版一致）。
            gestureLocked = lockedState.value,
            // 用 animeko 原版默认的 safeContent insets：
            // 全屏时它给底栏补上横向安全区，否则最右侧的「退出全屏」按钮会被窗口边缘裁掉。
            modifier = Modifier.fillMaxSize(),
            topBar = {
                PlayerTopBar(
                    title = if (expanded) {
                        // 原版标题：有副标题时两行（主标题 titleMedium + 副标题 titleSmall），
                        // 无副标题时只显示主标题。文本带描边，保证压在任何画面上都可读。
                        {
                            val subtitle = subtitleState.value
                            if (!subtitle.isNullOrEmpty()) {
                                EpisodePlayerTitle(
                                    ep = episodeNumberState.value,
                                    episodeTitle = subtitle,
                                    subjectTitle = titleState.value.orEmpty(),
                                )
                            } else {
                                TextWithBorder(
                                    titleState.value.orEmpty(),
                                    softWrap = false,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    } else {
                        null
                    },
                    // 顶栏功能按钮：原版 TitleView 的设置/投屏/小窗/嗅探。
                    // 用原版 OptionsSwitcher 的溢出菜单承载，避免在顶栏铺一排图标。
                    actions = {
                        val actions = topBarActionsState.value
                        // 直接显示图标的一类（对应参考项目顶栏右侧那排）
                        actions.filter { it.isPrimary() }.forEach { act ->
                            IconButton(onClick = { h.onTopBarAction(act.id) }) {
                                Icon(
                                    iconForAction(act.id),
                                    contentDescription = act.label,
                                )
                            }
                        }
                        // 其余收进溢出菜单。这些是「命令」而非「选项」，
                        // 没有选中态：value 传 -1，永远不等于任何选项下标，因此不会高亮某一项。
                        val overflow = actions.filter { !it.isPrimary() }
                        if (overflow.isNotEmpty()) {
                            PlayerControllerDefaults.OptionsSwitcher<Int>(
                                value = -1,
                                onValueChange = { index -> h.onTopBarAction(overflow[index].id) },
                                optionsProvider = { overflow.indices.toList() },
                                renderValue = { index -> Text(overflow[index].label) },
                                renderValueExposed = { Icon(Icons.Rounded.MoreVert, "更多") },
                            )
                        }
                    },
                )
            },
            // 加载 / 缓冲 / 错误 / 起播 / 播完：统一走 VideoScaffold 的居中浮层。
            floatingMessage = {
                when (playbackStageState.value) {
                    IOrangePlaybackUiState.STAGE_ERROR -> AnimekoErrorOverlay(
                        message = errorMessageState.value.orEmpty(),
                        onRetry = { h.onRetry() },
                    )

                    IOrangePlaybackUiState.STAGE_COMPLETE -> AnimekoCompleteOverlay(
                        onReplay = { h.onReplay() },
                    )

                    IOrangePlaybackUiState.STAGE_PREPARE -> AnimekoStartOverlay(
                        title = titleState.value,
                        onStart = { h.onStartPlay() },
                    )

                    IOrangePlaybackUiState.STAGE_LOADING -> AnimekoLoadingOverlay(
                        text = bufferingText(bufferingSpeedState.value),
                        showProgress = true,
                    )

                    else -> Unit
                }
            },
            // 手势层不接管：橘子已有完整的触摸手势（亮度/音量/滑动 seek/长按加速/
            // 单击显隐控制器/双击暂停），全部由 GSY 的 surface_container 处理。
            // 这里若再挂一层 pointerInput，会抢在它之前消费事件，导致上述手势整体失效。
            // 单击显隐、双击语义继续沿用橘子既有的实现，不在这里重复。
            // 右侧中部：截图按钮（animeko 原版位于 rhsButtons 槽）。
            rhsButtons = {
                if (expanded) {
                    ScreenshotButton(onClick = { h.onScreenshot() })
                }
            },
            // 右侧中部偏下：手势锁定按钮（animeko 原版位于 gestureLock 槽）。
            gestureLock = {
                if (expanded) {
                    GestureLock(
                        isLocked = lockedState.value,
                        onClick = { h.onToggleLock() },
                    )
                }
            },
            bottomBar = {
                // IME 避让：弹幕输入框获得焦点后键盘会从底部弹出并盖住底栏，
                // 导致「发送」按钮点不到。这里按键盘高度把底栏整体顶起来。
                val imeBottom = WindowInsets.ime
                    .getBottom(LocalDensity.current)
                    .coerceAtLeast(0)
                    .dp
                PlayerControllerBar(
                    modifier = Modifier.padding(bottom = imeBottom),
                    startActions = {
                        PlayerControllerDefaults.PlaybackIcon(
                            isPlaying = { isPlayingState.value },
                            onClick = { h.onTogglePlay() },
                        )
                        // 与原版一致：下一集只在全屏出现（非全屏时原版也不显示）
                        if (expanded) {
                            PlayerControllerDefaults.NextEpisodeIcon(
                                onClick = { h.onNextEpisode() },
                            )
                        }
                        PlayerControllerDefaults.DanmakuIcon(
                            danmakuEnabled = danmakuState.value,
                            onClick = { h.onToggleDanmaku() },
                        )
                    },
                    progressIndicator = {
                        // 参考项目的时间文本是居中的
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(composeTimeText(positionState.value, durationState.value))
                        }
                    },
                    progressSlider = {
                        val sliderState = remember {
                            PlayerProgressSliderState(
                                currentPositionMillis = { positionState.value },
                                totalDurationMillis = { durationState.value },
                                chapters = { emptyList() },
                                onPreview = { /* 拖动中：仅预览，不 seek */ },
                                onPreviewFinished = { pos ->
                                    val dur = durationState.value
                                    if (dur > 0) h.onSeekTo(pos.toFloat() / dur)
                                    h.onSeekFinished()
                                },
                            )
                        }
                        PlayerControllerDefaults.MediaProgressSlider(
                            progressSliderState = sliderState,
                            cacheProgressInfoFlow = flowOf(MediaCacheProgressInfo.Empty),
                            showPreviewTimeTextOnThumb = expanded,
                            framePreview = null,
                            showFramePreviewInPopup = false,
                            touchSeekState = null,
                        )
                    },
                    danmakuEditor = {
                        var text by remember { mutableStateOf("") }
                        // 发送样式（颜色 + 位置），由输入框内左侧的样式按钮选择。
                        var sendStyle by remember { mutableStateOf(DanmakuSendStyle.Default) }
                        // 必须带 weight(1f)：原版调用点也是这么传的。
                        // 少了它，BasicTextField 会按占位文本的固有宽度撑开，
                        // 把后面的选集/倍速/全屏挤出屏幕（全屏下尤其明显）。
                        PlayerControllerDefaults.DanmakuTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.weight(1f),
                            // 样式按钮放在输入框内部最前面，表示「这条弹幕的样式」
                            leadingIcon = {
                                DanmakuStylePicker(
                                    style = sendStyle,
                                    onStyleChange = { sendStyle = it },
                                )
                            },
                            onSend = {
                                if (text.isNotBlank()) {
                                    h.onSendDanmaku(text, sendStyle.color, sendStyle.location.toOrangeLocation())
                                    text = ""
                                }
                            },
                        )
                    },
                    endActions = {
                        // 与原版一致：选集/倍速只在全屏出现
                        if (expanded) {
                            PlayerControllerDefaults.SelectEpisodeIcon(onClick = { h.onSelectEpisode() })
                            // 用 animeko 原版的倍速控件（按钮 + 滑杆弹层），
                            // 直接落到播放器的 setSpeed，不再绕道旧 XML 控制栏的按钮。
                            PlayerControllerDefaults.SpeedSwitcher(
                                currentSpeed = speedState.value,
                                speedRange = SPEED_RANGE,
                                onPreviewSpeed = { h.onSetSpeed(it) },
                                onCommitSpeed = { h.onSetSpeed(it) },
                            )
                        }
                        PlayerControllerDefaults.FullscreenIcon(fullscreenState = fsState)
                    },
                    expanded = expanded,
                )
            },
        )
    }

    /** 解绑，释放 Compose 内容。 */
    fun unbind() {
        host = null
        composeView.disposeComposition()
    }
}

/** 毫秒 → mm:ss（或 h:mm:ss）。 */
internal fun composeTimeText(posMs: Long, durMs: Long): String =
    "${fmt(posMs)} / ${fmt(durMs)}"

private fun fmt(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val s = total % 60
    val m = (total / 60) % 60
    val h = total / 3600
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

/**
 * 顶栏动作对应的图标。
 *
 * 对应参考项目顶栏：媒体源用 DisplaySettings、画中画用 PictureInPicture、
 * 投屏用 Cast。橘子把投屏/小窗提为直接图标，取代参考项目那个「数据源」位置。
 */
private fun iconForAction(id: String): androidx.compose.ui.graphics.vector.ImageVector =
    when (id) {
        com.orange.playerlibrary.ui.IOrangeControlUiProvider.TopBarAction.ID_CAST ->
            Icons.Rounded.Cast
        com.orange.playerlibrary.ui.IOrangeControlUiProvider.TopBarAction.ID_PIP ->
            Icons.Rounded.PictureInPictureAlt
        com.orange.playerlibrary.ui.IOrangeControlUiProvider.TopBarAction.ID_SNIFFING ->
            Icons.Rounded.TravelExplore
        com.orange.playerlibrary.ui.IOrangeControlUiProvider.TopBarAction.ID_SETTINGS ->
            Icons.Rounded.Settings
        else -> Icons.Rounded.MoreVert
    }

/** 弹幕位置 → 橘子的位置常量（IDanmakuController.LOCATION_*）。 */
private fun me.him188.ani.danmaku.api.DanmakuLocation.toOrangeLocation(): Int =
    when (this) {
        me.him188.ani.danmaku.api.DanmakuLocation.TOP -> 4
        me.him188.ani.danmaku.api.DanmakuLocation.BOTTOM -> 5
        me.him188.ani.danmaku.api.DanmakuLocation.NORMAL -> 1
    }

/** 倍速可选范围，与 animeko 原版 PlaybackSpeedControllerState.DEFAULT_SPEED_RANGE 一致。 */
private val SPEED_RANGE = 0.5f..2.5f
