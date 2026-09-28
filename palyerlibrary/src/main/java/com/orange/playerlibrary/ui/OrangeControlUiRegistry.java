package com.orange.playerlibrary.ui;

import androidx.annotation.Nullable;

/**
 * 控制栏 UI 实现的注册表。
 *
 * <p>宿主在创建播放器之前注册一个 {@link IOrangeControlUiProvider}，
 * 播放器便会用它替换默认的 XML 组件式控制栏。不注册则保持默认（旧 UI）。</p>
 *
 * <p>设计为全局单例：UI 实现是「进程级选择」，与具体播放器实例无关，
 * 这样宿主的 {@code Application.onCreate} 里注册一次即可。</p>
 */
public final class OrangeControlUiRegistry {

    @Nullable
    private static volatile IOrangeControlUiProvider sProvider;

    private OrangeControlUiRegistry() {
    }

    /**
     * 注册控制栏 UI 实现。传 {@code null} 恢复默认（旧 XML UI）。
     */
    public static void setProvider(@Nullable IOrangeControlUiProvider provider) {
        sProvider = provider;
    }

    /**
     * 当前注册的实现，未注册时为 {@code null}。
     */
    @Nullable
    public static IOrangeControlUiProvider getProvider() {
        return sProvider;
    }

    /** 是否已注册自定义控制栏 UI。 */
    public static boolean hasProvider() {
        return sProvider != null;
    }
}
