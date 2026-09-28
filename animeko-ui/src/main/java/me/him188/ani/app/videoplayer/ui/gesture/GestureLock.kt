/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.videoplayer.ui.gesture

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.foundation.theme.slightlyWeaken

// 说明：原文件还包含 LockedScreenGestureHost / LockableVideoGestureHost，
// 它们依赖 animeko 整套手势子系统（PlayerGestureHost / LevelController /
// SwipeSeekerState / FastSkipState ...）。橘子的手势由 GestureView 承担，
// 这里只保留锁定按钮本身，其余不复制。

const val TAG_GESTURE_LOCK = "GestureLock"

@Composable
fun GestureLock(
    isLocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier.testTag(TAG_GESTURE_LOCK),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.background.copy(0.05f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.slightlyWeaken()),
    ) {
        IconButton(onClick) {
            val color = if (isLocked) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White
            }
            CompositionLocalProvider(LocalContentColor provides color) {
                if (isLocked) {
                    Icon(Icons.Outlined.Lock, contentDescription = "UnLock screen")
                } else {
                    Icon(Icons.Outlined.LockOpen, contentDescription = "Lock screen")
                }
            }
        }
    }
}
