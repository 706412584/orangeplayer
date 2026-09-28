/* 指针类型 shim（原版 ui-foundation input）。 */
@file:Suppress("unused")
package me.him188.ani.app.ui.foundation.input

import androidx.compose.ui.input.pointer.PointerType

/** 把平台指针类型归一到手势用的分类（触摸类，含手写笔/橡皮）。 */
fun PointerType.asGesturePointerType(): PointerType = PointerType.Touch
