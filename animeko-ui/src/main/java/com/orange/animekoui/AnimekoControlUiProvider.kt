package com.orange.animekoui

import android.content.Context
import android.view.View
import com.orange.playerlibrary.ui.IOrangeControlUiProvider

/**
 * 把 animeko 风格的 Compose 控制栏注册给橘子播放器。
 *
 * <p>用法（宿主 Application.onCreate）：</p>
 * <pre>
 * OrangeControlUiRegistry.setProvider(AnimekoControlUiProvider())
 * </pre>
 *
 * <p>注册后播放器会用 Compose 控制栏替换既有 XML 组件式控制栏；
 * 不注册则保持默认（旧 UI 行为零变化）。</p>
 */
class AnimekoControlUiProvider : IOrangeControlUiProvider {

    override fun createControlView(
        context: Context,
        host: IOrangeControlUiProvider.ControlUiHost,
    ): View {
        val view = AnimekoControlViewHost(context)
        view.bind(object : AnimekoControlViewHost.ControlHost {
            override fun isPlaying(): Boolean = host.isPlaying
            override fun positionMs(): Long = host.positionMs
            override fun durationMs(): Long = host.durationMs
            override fun danmakuEnabled(): Boolean = host.isDanmakuEnabled
            override fun speed(): Float = host.speed
            override fun onSeekTo(progress: Float) = host.onSeekTo(progress)
            override fun onSeekFinished() = host.onSeekFinished()
            override fun onTogglePlay() = host.onTogglePlay()
            override fun onNextEpisode() = host.onNextEpisode()
            override fun onToggleDanmaku() = host.onToggleDanmaku()
            override fun onSendDanmaku(text: String, color: Int, location: Int) =
                host.onSendDanmaku(text, color, location)
            override fun onSelectEpisode() = host.onSelectEpisode()
            override fun onSetSpeed(speed: Float) = host.onSetSpeed(speed)
            override fun onBack() = host.onBack()
            override fun isFullscreen(): Boolean = host.isFullscreen
            override fun onToggleFullscreen() = host.onToggleFullscreen()
            override fun title(): String? = host.title
            override fun subtitle(): String? = host.subtitle
            override fun episodeNumber(): String? = host.episodeNumber
            override fun isLocked(): Boolean = host.isLocked
            override fun onToggleLock() = host.onToggleLock()
            override fun isControllerVisible(): Boolean = host.isControllerVisible
            override fun onToggleControllerVisibility() = host.onToggleControllerVisibility()
            override fun playbackStage(): Int = host.playbackStage
            override fun errorMessage(): String? = host.errorMessage
            override fun bufferingProgressVisible(): Boolean = host.isBufferingProgressVisible
            override fun bufferingSpeedBytesPerSec(): Long = host.bufferingSpeedBytesPerSec
            override fun onRetry() = host.onRetry()
            override fun onStartPlay() = host.onStartPlay()
            override fun onReplay() = host.onReplay()
            override fun getPreviewFrame(positionMs: Long) = host.getPreviewFrame(positionMs)
            override fun onScreenshot() = host.onScreenshot()
            override fun topBarActions(): List<IOrangeControlUiProvider.TopBarAction> =
                host.topBarActions
            override fun onTopBarAction(actionId: String) = host.onTopBarAction(actionId)
            override fun isSniffingPanelShowing(): Boolean = host.isSniffingPanelShowing
        })
        return view
    }
}
