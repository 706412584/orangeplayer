/* 返回分发 shim（原版 ui-foundation navigation）。 */
@file:Suppress("unused")
package me.him188.ani.app.ui.foundation.navigation

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** 返回动作分发器。 */
fun interface BackDispatcher {
    fun onBackPressed()
}

val LocalBackDispatcher: ProvidableCompositionLocal<BackDispatcher> =
    staticCompositionLocalOf { BackDispatcher { } }
