/*
 * Compose Multiplatform 资源系统 shim。
 *
 * 原版 animeko 用 `org.jetbrains.compose.resources.stringResource(StringResource)`，
 * 依赖其编译期生成的 Res 资源类。本模块不引入那套资源管线，
 * 这里提供同名重载，直接接受已解析好的字符串（见 LangShim），
 * 使复制的 UI 代码无需改动即可编译。
 */
@file:Suppress("unused")

package org.jetbrains.compose.resources

import androidx.compose.runtime.Composable

/** 直接返回传入的字符串（本模块用纯字符串代替资源系统）。 */
@Composable
fun stringResource(text: String): String = text
