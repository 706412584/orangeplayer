package com.orange.playerlibrary.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 监听器列表的快照工具。
 *
 * <p>状态回调的分发方（{@code OrangevideoView.notifyPlayStateChanged} 等）必须遍历
 * **快照**而非原列表：监听器在自己的回调里解绑自己是常见写法（demo 的 PiP 恢复
 * 监听器就在 {@code onPlayStateChanged} 里调 {@code removeOnStateChangeListener}）。
 * 直接 for-each 遍历原列表时，{@code remove} 会改动 {@code modCount}，循环恢复后
 * {@code it.next()} 立即抛 {@link java.util.ConcurrentModificationException} 杀死进程。
 *
 * <p>真机复现（Android 16 / arm64）：曾进入画中画 → 重启 App → 点播放，
 * {@code onPrepared} 派发 STATE_PREPARED 时崩溃，进程存活约 1.6 秒。
 * 崩溃点即原实现的 for-each 那一行。
 *
 * <p>快照语义：本次通知遍历的是调用瞬间的元素集合，之后对原列表的增删不影响本次遍历，
 * 也不会重复通知新增的监听器。代价是一次浅拷贝，监听器数量通常是个位数。
 */
public final class ListenerSnapshot {

    private ListenerSnapshot() {
    }

    /**
     * 返回列表的浅拷贝；null 或空列表返回空列表（永不返回 null，调用方无需判空）。
     */
    public static <T> List<T> of(List<T> listeners) {
        if (listeners == null || listeners.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(listeners);
    }
}
