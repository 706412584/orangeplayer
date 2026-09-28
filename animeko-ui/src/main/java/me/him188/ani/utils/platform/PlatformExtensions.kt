/* 平台判定 shim（原版 utils/platform）。 */
@file:Suppress("unused")
package me.him188.ani.utils.platform

import me.him188.ani.app.ui.foundation.Platform

/** 是否桌面端。原版是 Platform 的扩展属性/函数，这里提供函数形态。 */
fun Platform.isDesktop(): Boolean = isDesktop
