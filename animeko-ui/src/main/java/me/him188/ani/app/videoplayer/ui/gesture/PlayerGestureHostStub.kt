/* PlayerGestureHost 占位（原版在 video-player gesture，依赖其手势状态机）。
   复制的 VideoScaffold 仅在 slot 默认值里引用它，这里提供空实现占位。 */
@file:Suppress("unused")
package me.him188.ani.app.videoplayer.ui.gesture

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable

/** 手势宿主占位：不拦截事件。真实手势由橘子现有 GestureView 承担。 */
@Composable
fun PlayerGestureHost(
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    androidx.compose.foundation.layout.Box(modifier) {}
}
