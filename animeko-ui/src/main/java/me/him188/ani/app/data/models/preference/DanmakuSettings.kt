/*
 * 弹幕设置 shim（原版 app-data models/preference/DanmakuSettings.kt 的发送相关子集）。
 *
 * 原版是 @Serializable 的完整设置模型；这里只保留发送弹幕样式（颜色/位置）所需字段，
 * 避免为一个 UI 组件引入 kotlinx.serialization。
 */
@file:Suppress("unused")

package me.him188.ani.app.data.models.preference

import me.him188.ani.danmaku.api.DanmakuLocation

data class DanmakuSettings(
    /** 发送弹幕时使用的颜色, RGB (不含 alpha), 例如白色为 `0xFFFFFF`. */
    val sendColor: Int = DEFAULT_SEND_COLOR,

    /** 发送弹幕时使用的位置. */
    val sendLocation: DanmakuLocation = DanmakuLocation.NORMAL,
) {
    companion object {
        /** 白色 */
        const val DEFAULT_SEND_COLOR: Int = 0xFFFFFF

        val Default = DanmakuSettings()
    }
}
