/* 平台信息 shim（原版 ui-foundation）。 */
@file:Suppress("unused")
package me.him188.ani.app.ui.foundation

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** 平台标识。原版含 Desktop/Android/iOS 的丰富能力，这里只保留 isDesktop 判断。 */
interface Platform {
    val isDesktop: Boolean
    val isAndroid: Boolean
}

private object AndroidPlatform : Platform {
    override val isDesktop = false
    override val isAndroid = true
}

val LocalPlatform: ProvidableCompositionLocal<Platform> =
    staticCompositionLocalOf { AndroidPlatform }
