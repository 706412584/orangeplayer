/* 预览辅助 shim（原版 ui-foundation）。 */
@file:Suppress("unused")
package me.him188.ani.app.ui.foundation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

/** 预览用：套上默认主题。原版还提供平台相关的 CompositionLocal，这里简化为主题包装。 */
@Composable
fun ProvideCompositionLocalsForPreview(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}
