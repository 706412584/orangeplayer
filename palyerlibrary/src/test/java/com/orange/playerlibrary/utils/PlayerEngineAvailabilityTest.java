package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.tool.NativeLibManager;

import org.junit.Test;

/**
 * 内核可用性判定的单测。
 *
 * 覆盖不依赖真机的部分：engine→bundle 映射、exo/default 恒可用、so 未下载时的判定。
 * 真机才能验证的是「so 就位后能否真正 dlopen」（见 NativeLibManager 的注入机制）。
 */
public class PlayerEngineAvailabilityTest {

    @Test
    public void bundleIdMappingCoversTheThreeOptionalEngines() {
        assertEquals(NativeLibManager.BUNDLE_IJK,
                PlayerEngineAvailability.bundleIdFor(PlayerConstants.ENGINE_IJK));
        assertEquals(NativeLibManager.BUNDLE_ALI,
                PlayerEngineAvailability.bundleIdFor(PlayerConstants.ENGINE_ALI));
        assertEquals(NativeLibManager.BUNDLE_MPV,
                PlayerEngineAvailability.bundleIdFor(PlayerConstants.ENGINE_MPV));
        // exo / default 无原生依赖，不该映射到任何按需组件
        assertNull(PlayerEngineAvailability.bundleIdFor(PlayerConstants.ENGINE_EXO));
        assertNull(PlayerEngineAvailability.bundleIdFor(PlayerConstants.ENGINE_DEFAULT));
    }

    @Test
    public void systemEngineIsAlwaysUsable() {
        // 系统内核是回退落点，任何情况下都必须可用，否则回退无处可去
        assertTrue(PlayerEngineAvailability.isSupported(PlayerConstants.ENGINE_DEFAULT));
        assertTrue(PlayerEngineAvailability.isUsable(PlayerConstants.ENGINE_DEFAULT));
        assertFalse(PlayerEngineAvailability.needsDownload(PlayerConstants.ENGINE_DEFAULT));
    }

    @Test
    public void optionalEnginesAreNotUsableBeforeInit() {
        // 未调用 install() 时 sFilesDir 为空 → isInstalled 必然 false，
        // 因此三个可选内核都不能判为可用（否则会跳过下载直接切换）
        for (String engine : new String[]{PlayerConstants.ENGINE_IJK,
                PlayerConstants.ENGINE_ALI, PlayerConstants.ENGINE_MPV}) {
            assertFalse(engine + " 未初始化时不应可用",
                    PlayerEngineAvailability.isUsable(engine));
        }
    }

    @Test
    public void needsDownloadOnlyForEnginesThatHaveABundle() {
        // 无 bundle 的内核（exo/default）没有可下载的东西，必须恒 false。
        // 曾经的实现写成 `isSupported && !isInstalled`，而 isInstalled 对无 bundle
        // 的内核恒 false，于是系统内核也会被判成「需要下载」。
        assertFalse(PlayerEngineAvailability.needsDownload(PlayerConstants.ENGINE_DEFAULT));
        assertFalse(PlayerEngineAvailability.needsDownload(PlayerConstants.ENGINE_EXO));
    }

    @Test
    public void needsDownloadImpliesSupportedAndNotInstalled() {
        // 不变式：needsDownload 为真时必须「宿主引入了」且「so 未就位」，
        // 否则 UI 会给一个用不了的内核显示下载入口
        for (String engine : new String[]{PlayerConstants.ENGINE_IJK,
                PlayerConstants.ENGINE_ALI, PlayerConstants.ENGINE_MPV,
                PlayerConstants.ENGINE_EXO, PlayerConstants.ENGINE_DEFAULT}) {
            if (PlayerEngineAvailability.needsDownload(engine)) {
                assertTrue(engine + " 需要下载时必须已引入",
                        PlayerEngineAvailability.isSupported(engine));
                assertFalse(engine + " 需要下载时 so 不应已就位",
                        PlayerEngineAvailability.isInstalled(engine));
            }
        }
    }

    @Test
    public void isInstalledIsFalseForEngineWithoutBundle() {
        // exo/default 没有对应 bundle，isInstalled 应为 false 而不是抛异常
        assertFalse(PlayerEngineAvailability.isInstalled(PlayerConstants.ENGINE_EXO));
        assertFalse(PlayerEngineAvailability.isInstalled(PlayerConstants.ENGINE_DEFAULT));
    }

    @Test
    public void unknownEngineIsNotUsable() {
        assertFalse(PlayerEngineAvailability.isUsable("nonexistent"));
        assertFalse(PlayerEngineAvailability.isSupported("nonexistent"));
    }
}
