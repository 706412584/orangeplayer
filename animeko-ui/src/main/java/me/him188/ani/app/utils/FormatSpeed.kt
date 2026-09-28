/* 倍速格式化 shim（原版 app/utils）。 */
@file:Suppress("unused")
package me.him188.ani.app.utils

/** 倍速 → 展示文本。原版为扩展函数，如 1.0 → "1"，1.5 → "1.5"。 */
fun Float.formatSpeedValue(): String {
    return if (this == toInt().toFloat()) "${toInt()}" else "$this"
}
