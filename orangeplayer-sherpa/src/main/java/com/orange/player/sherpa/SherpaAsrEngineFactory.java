package com.orange.player.sherpa;

import com.orange.playerlibrary.speech.BatchAsrEngine;

/**
 * BatchAsrEngine 工厂——palyerlibrary 的 SherpaAvailabilityChecker 反射加载入口。
 */
public final class SherpaAsrEngineFactory {

    private SherpaAsrEngineFactory() {
    }

    public static BatchAsrEngine create() {
        return new SherpaBatchAsrEngine();
    }
}
