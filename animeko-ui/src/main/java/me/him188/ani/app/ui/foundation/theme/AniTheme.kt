/* 主题 shim（原版 ui-foundation theme/AppTheme.kt 的简化）。 */
@file:Suppress("unused")
package me.him188.ani.app.ui.foundation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import me.him188.ani.app.data.models.preference.DarkMode

/** animeko 深色主题的 primary（见真机截图：淡紫）。 */
private val AnimekoDark = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFD0BCFF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF381E72),
    surface = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
    background = androidx.compose.ui.graphics.Color(0xFF1C1B1F),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE6E1E5),
)

private val AnimekoLight = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
)

/**
 * 播放器 UI 主题。原版会按 darkModeOverride 与系统设置选择主题；
 * 这里简化：DARK 用深色方案，其余用浅色。
 */
@Composable
fun AniTheme(
    darkModeOverride: DarkMode? = null,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkModeOverride == DarkMode.DARK) AnimekoDark else AnimekoLight
    MaterialTheme(colorScheme = scheme, content = content)
}
