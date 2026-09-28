package com.orange.animekoui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.text.ProvideTextStyleContentColor
import me.him188.ani.app.videoplayer.ui.VideoLoadingIndicator


/**
 * 播放器浮层内容（居中叠层）。
 *
 * <p>对应参考项目 `VideoScaffold` 的 `floatingMessage` 槽，承载：
 * 加载/缓冲提示、错误提示（带重试）、起播按钮、播完重播。</p>
 *
 * <p>与参考项目的差异：参考项目的错误态只显示红字「播放失败, 请更换数据源」，
 * 因为它靠自动切换数据源恢复；橘子播放本地文件时没有数据源可切，
 * 所以错误浮层额外保留「重试」按钮，否则用户没有恢复手段。</p>
 */

/** 起播前：显示缩略图上的「开始播放」。 */
@Composable
fun AnimekoStartOverlay(
    title: String?,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onStart, modifier = Modifier.size(64.dp)) {
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = "开始播放",
                modifier = Modifier.size(56.dp),
                tint = Color.White,
            )
        }
        if (!title.isNullOrEmpty()) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** 加载 / 缓冲：转圈 + 文案（沿用参考项目的 [VideoLoadingIndicator]）。 */
@Composable
fun AnimekoLoadingOverlay(
    text: String,
    showProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    VideoLoadingIndicator(
        showProgress = showProgress,
        text = { Text(text) },
        modifier = modifier,
    )
}

/** 错误：红字提示 + 重试（+ 可选设置）。 */
@Composable
fun AnimekoErrorOverlay(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSettings: (() -> Unit)? = null,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 与参考项目一致：错误文案用 error 色，并加描边以保证在任意画面上可读
        ProvideTextStyleContentColor(
            MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.error,
        ) {
            Text(
                message,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Row(
            Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onRetry) { Text("重试") }
            if (onSettings != null) {
                TextButton(onClick = onSettings) { Text("设置") }
            }
        }
    }
}

/** 播完：重播。 */
@Composable
fun AnimekoCompleteOverlay(
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconButton(onClick = onReplay, modifier = Modifier.size(64.dp)) {
            Icon(
                Icons.Rounded.Replay,
                contentDescription = "重播",
                modifier = Modifier.size(48.dp),
                tint = Color.White,
            )
        }
    }
}

/** 缓冲时若已知下载速度，附在文案后。 */
@Composable
internal fun bufferingText(speedBytesPerSec: Long): String =
    if (speedBytesPerSec > 0) {
        "缓冲中…\n${formatSpeedBytes(speedBytesPerSec)}/s"
    } else {
        "缓冲中…"
    }

private fun formatSpeedBytes(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB", bytesPerSec / 1024f / 1024f)
    bytesPerSec >= 1024 -> String.format("%.1f KB", bytesPerSec / 1024f)
    else -> "$bytesPerSec B"
}
