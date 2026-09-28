package com.orange.animekoui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.flowOf
import me.him188.ani.app.data.models.preference.DarkMode
import me.him188.ani.app.domain.media.player.MediaCacheProgressInfo
import me.him188.ani.app.ui.foundation.theme.AniTheme
import me.him188.ani.app.videoplayer.ui.MutablePlayerFullscreenState
import me.him188.ani.app.videoplayer.ui.PlayerFullscreenState
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerBar
import me.him188.ani.app.videoplayer.ui.progress.PlayerControllerDefaults
import me.him188.ani.app.videoplayer.ui.progress.PlayerProgressSliderState
import me.him188.ani.app.videoplayer.ui.top.PlayerTopBar

/**
 * 控制栏预览（Compose Preview）。
 *
 * 在 Android Studio 中打开本文件即可看到渲染结果，无需装机；
 * 用于快速迭代布局，改完再上真机验证。
 *
 * 覆盖 animeko 的两种形态：全屏（expanded）与紧凑（非全屏）。
 */

private const val DEMO_POSITION = 82_000L      // 01:22
private const val DEMO_DURATION = 1_422_000L   // 23:42

@Composable
private fun PreviewScaffold(
    expanded: Boolean,
    title: String,
    content: @Composable () -> Unit,
) {
    AniTheme(darkModeOverride = DarkMode.DARK) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xFF2A2A2A)), // 占位视频底
        ) {
            Column(Modifier.fillMaxSize()) {
                PlayerTopBar(
                    title = if (expanded) {
                        { Text(title, fontSize = 18.sp) }
                    } else {
                        null
                    },
                    actions = {},
                )
                Spacer(Modifier.weight(1f))
                content()
            }
        }
    }
}

/** 全屏形态：顶栏 + 时间/进度 + 完整操作行。 */
@Preview(name = "animeko 全屏", widthDp = 780, heightDp = 360, showBackground = true)
@Composable
fun PreviewAnimekoFullscreen() {
    val fsState = remember { MutablePlayerFullscreenState(true) }
    val sliderState = remember {
        PlayerProgressSliderState(
            currentPositionMillis = { DEMO_POSITION },
            totalDurationMillis = { DEMO_DURATION },
            chapters = { emptyList() },
            onPreview = {},
            onPreviewFinished = {},
        )
    }
    PreviewScaffold(expanded = true, title = "测试视频") {
        PlayerControllerBar(
            startActions = {
                PlayerControllerDefaults.PlaybackIcon(isPlaying = { true }, onClick = {})
                PlayerControllerDefaults.NextEpisodeIcon(onClick = {})
                PlayerControllerDefaults.DanmakuIcon(danmakuEnabled = true, onClick = {})
            },
            progressIndicator = {
                Text(composeTimeText(DEMO_POSITION, DEMO_DURATION))
            },
            progressSlider = {
                PlayerControllerDefaults.MediaProgressSlider(
                    progressSliderState = sliderState,
                    cacheProgressInfoFlow = flowOf(MediaCacheProgressInfo.Empty),
                    showPreviewTimeTextOnThumb = true,
                    framePreview = null,
                    showFramePreviewInPopup = false,
                    touchSeekState = null,
                )
            },
            danmakuEditor = {
                Text(
                    "发条友善的弹幕吧～",
                    color = Color(0xFFE6E1E5),
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            },
            endActions = {
                PlayerControllerDefaults.SelectEpisodeIcon(onClick = {})
                TextButton(onClick = {}) { Text("1x") }
                PlayerControllerDefaults.FullscreenIcon(fullscreenState = fsState)
            },
            expanded = true,
        )
    }
}

/** 紧凑形态（非全屏）：无标题、无下一集/选集/倍速。 */
@Preview(name = "animeko 紧凑", widthDp = 400, heightDp = 240, showBackground = true)
@Composable
fun PreviewAnimekoCompact() {
    val fsState: PlayerFullscreenState = remember { MutablePlayerFullscreenState(false) }
    val sliderState = remember {
        PlayerProgressSliderState(
            currentPositionMillis = { DEMO_POSITION },
            totalDurationMillis = { DEMO_DURATION },
            chapters = { emptyList() },
            onPreview = {},
            onPreviewFinished = {},
        )
    }
    PreviewScaffold(expanded = false, title = "测试视频") {
        PlayerControllerBar(
            startActions = {
                PlayerControllerDefaults.PlaybackIcon(isPlaying = { false }, onClick = {})
                PlayerControllerDefaults.DanmakuIcon(danmakuEnabled = false, onClick = {})
            },
            progressIndicator = {
                Text(composeTimeText(DEMO_POSITION, DEMO_DURATION))
            },
            progressSlider = {
                PlayerControllerDefaults.MediaProgressSlider(
                    progressSliderState = sliderState,
                    cacheProgressInfoFlow = flowOf(MediaCacheProgressInfo.Empty),
                    showPreviewTimeTextOnThumb = false,
                    framePreview = null,
                    showFramePreviewInPopup = false,
                    touchSeekState = null,
                )
            },
            danmakuEditor = {},
            endActions = {
                PlayerControllerDefaults.FullscreenIcon(fullscreenState = fsState)
            },
            expanded = false,
        )
    }
}
