/* Popup 属性 shim（原版 ui-foundation dialogs，签名对齐）。 */
@file:Suppress("unused")
package me.him188.ani.app.ui.foundation.dialogs

import androidx.compose.ui.window.PopupProperties

/** 与 animeko 同名同参：Android 侧把 clippingEnabled/usePlatformInsets 折进 PopupProperties。 */
fun PlatformPopupProperties(
    focusable: Boolean = false,
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    usePlatformDefaultWidth: Boolean = false,
    excludeFromSystemGesture: Boolean = true,
    clippingEnabled: Boolean = true,
    usePlatformInsets: Boolean = true,
): PopupProperties = PopupProperties(
    focusable = focusable,
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
    usePlatformDefaultWidth = usePlatformDefaultWidth,
    clippingEnabled = clippingEnabled,
)
