/* 桌面标题栏 inset shim。
   原版 PlatformWindow.kt 依赖 app.platform 的桌面窗口抽象（Android 用不到），
   这里只保留 VideoScaffold 引用的 desktopTitleBar inset，恒为 0。 */
@file:Suppress("unused")
package me.him188.ani.app.ui.foundation.layout

import androidx.compose.foundation.layout.WindowInsets

/** 桌面标题栏 inset：Android 恒为 0。 */
val WindowInsets.Companion.desktopTitleBar: WindowInsets
    get() = WindowInsets(0, 0, 0, 0)
