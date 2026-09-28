/*
 * AniMotionScheme shim。
 *
 * 原版是 322 行的完整动效系统（含窗口尺寸自适应、spring 参数计算等）。
 * 播放器 UI 只用到 animatedVisibility 下的 6 个过渡（standard/row/column 的 enter/exit），
 * 这里保留同样的调用形态与默认值，动画参数取 Material 常用值。
 */
@file:Suppress("unused")

package me.him188.ani.app.ui.foundation.animation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment

/** 可见性动画参数。对应 animeko 的 AnimatedVisibilityMotionScheme。 */
@Stable
data class AnimatedVisibilityMotionScheme(
    val standardEnter: EnterTransition,
    val standardExit: ExitTransition,
    val rowEnter: EnterTransition,
    val rowExit: ExitTransition,
    val columnEnter: EnterTransition,
    val columnExit: ExitTransition,
)

/** 动效方案。这里只保留播放器 UI 用到的部分。 */
@Stable
data class AniMotionScheme(
    val animatedVisibility: AnimatedVisibilityMotionScheme,
)

/** 默认方案：fade + 展开/收缩。 */
fun defaultAniMotionScheme(): AniMotionScheme = AniMotionScheme(
    animatedVisibility = AnimatedVisibilityMotionScheme(
        standardEnter = fadeIn(),
        standardExit = fadeOut(),
        rowEnter = expandHorizontally(expandFrom = Alignment.Start),
        rowExit = shrinkHorizontally(shrinkTowards = Alignment.Start),
        columnEnter = expandVertically(expandFrom = Alignment.Top),
        columnExit = shrinkVertically(shrinkTowards = Alignment.Top),
    ),
)

@Stable
val LocalAniMotionScheme: ProvidableCompositionLocal<AniMotionScheme> =
    staticCompositionLocalOf { defaultAniMotionScheme() }

/** 提供默认动效方案。 */
@Composable
fun ProvideAniMotionCompositionLocals(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalAniMotionScheme provides defaultAniMotionScheme(),
        content = content,
    )
}
