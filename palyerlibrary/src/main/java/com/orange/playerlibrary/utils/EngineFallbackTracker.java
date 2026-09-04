package com.orange.playerlibrary.utils;

import com.orange.playerlibrary.PlayerConstants;
import com.orange.playerlibrary.utils.UrlProtocolClassifier.UrlInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 引擎自动回退状态机（每 URL 有界）。
 * <p>
 * 不变量：
 * - 每个 URL 自动换核上限 K=1（偏好引擎失败后最多自动尝试一个候选）
 * - 会话级失败集合防止重复尝试已失败引擎
 * - 自动路径永不写 PlayerSettingsManager（用户偏好只能被用户动作改变）
 * - USER_SELECT 清空全部自动态并成为新偏好
 */
public class EngineFallbackTracker {

    /** 自动换核预算（每 URL） */
    private static final int MAX_AUTO_SWITCHES = 1;

    public enum State { IDLE, ACTIVE, FALLBACK, TERMINAL_ERROR }

    public interface AvailabilityProbe {
        boolean isEngineAvailable(String engine);
    }

    private State state = State.IDLE;
    private String preferredEngine;
    private String currentEngine;
    private String currentUrl;
    private int autoSwitchesUsed;
    private final Set<String> failedEngines = new HashSet<>();

    public State getState() {
        return state;
    }

    public String getPreferredEngine() {
        return preferredEngine;
    }

    public String getCurrentEngine() {
        return currentEngine;
    }

    public boolean hasFailed(String engine) {
        return failedEngines.contains(engine);
    }

    /**
     * 新 URL 就绪：重置预算（失败集合跨 URL 保留至会话结束，
     * 因为"引擎对协议不可用"与具体 URL 无关）。
     *
     * @return 若偏好引擎不支持该协议需要回退，返回回退目标；否则返回 preferredEngine
     */
    public String onUrlSet(String url, String preferredEngine, UrlInfo info,
                           AvailabilityProbe probe) {
        this.currentUrl = url;
        this.preferredEngine = preferredEngine;
        this.autoSwitchesUsed = 0;

        if (preferredEngine != null
                && EngineCapabilityMatrix.supports(preferredEngine, info)
                && !failedEngines.contains(preferredEngine)
                && (probe == null || probe.isEngineAvailable(preferredEngine))) {
            this.currentEngine = preferredEngine;
            this.state = State.ACTIVE;
            return preferredEngine;
        }

        // 偏好引擎不支持该协议或已失败：走候选链（自动换核预算内）
        String next = nextCandidate(info, probe, preferredEngine);
        if (next != null) {
            this.currentEngine = next;
            this.autoSwitchesUsed = 1;
            this.state = State.FALLBACK;
            return next;
        }
        this.currentEngine = preferredEngine; // 保留偏好让上层显式报错
        this.state = State.ACTIVE;
        return preferredEngine;
    }

    /**
     * 播放错误：记入失败集合；预算未耗尽且有候选时返回下一个引擎，否则进入 TERMINAL
     *
     * @return 下一个自动引擎；null 表示不再自动重试
     */
    public String onEngineFailure(String failedEngine, UrlInfo info, AvailabilityProbe probe) {
        if (failedEngine != null) {
            failedEngines.add(failedEngine);
        }
        if (autoSwitchesUsed >= MAX_AUTO_SWITCHES) {
            this.state = State.TERMINAL_ERROR;
            return null;
        }
        String next = nextCandidate(info, probe, currentEngine);
        if (next == null) {
            this.state = State.TERMINAL_ERROR;
            return null;
        }
        this.currentEngine = next;
        this.autoSwitchesUsed++;
        this.state = State.FALLBACK;
        return next;
    }

    /**
     * PTS 跳变检测（IJK 播放 DISCONTINUITY 流的已知缺陷）：
     * 一次性临时切 EXO（计入预算）
     */
    public String onPtsJumpDetected(UrlInfo info, AvailabilityProbe probe) {
        if (PlayerConstants.ENGINE_EXO.equals(currentEngine)) {
            return null; // 已在 EXO
        }
        if (autoSwitchesUsed >= MAX_AUTO_SWITCHES
                || failedEngines.contains(PlayerConstants.ENGINE_EXO)) {
            return null;
        }
        if (!EngineCapabilityMatrix.supports(PlayerConstants.ENGINE_EXO, info)
                || (probe != null && !probe.isEngineAvailable(PlayerConstants.ENGINE_EXO))) {
            return null;
        }
        this.currentEngine = PlayerConstants.ENGINE_EXO;
        this.autoSwitchesUsed++;
        this.state = State.FALLBACK;
        return PlayerConstants.ENGINE_EXO;
    }

    /**
     * 用户手动选择引擎：清空自动态，成为新偏好（最高优先级）
     */
    public void onUserSelect(String engine) {
        this.preferredEngine = engine;
        this.currentEngine = engine;
        this.autoSwitchesUsed = 0;
        this.state = State.ACTIVE;
        this.failedEngines.clear();
    }

    /**
     * 新会话（如 OrangevideoView release）：全部复位
     */
    public void onNewSession() {
        this.state = State.IDLE;
        this.preferredEngine = null;
        this.currentEngine = null;
        this.currentUrl = null;
        this.autoSwitchesUsed = 0;
        this.failedEngines.clear();
    }

    /** 下一个可自动切换的候选（排除当前、已失败、不可用） */
    private String nextCandidate(UrlInfo info, AvailabilityProbe probe, String excludeEngine) {
        List<String> candidates = EngineCapabilityMatrix.candidates(info);
        List<String> pool = new ArrayList<>(candidates);
        // 候选链为空时（如偏好引擎是 DEFAULT 而 URL 是 mpd）退化为全引擎探测
        if (pool.isEmpty()) {
            pool.add(PlayerConstants.ENGINE_EXO);
            pool.add(PlayerConstants.ENGINE_IJK);
            pool.add(PlayerConstants.ENGINE_ALI);
            pool.add(PlayerConstants.ENGINE_DEFAULT);
        }
        for (String engine : pool) {
            if (engine.equals(excludeEngine) || failedEngines.contains(engine)) {
                continue;
            }
            if (!EngineCapabilityMatrix.supports(engine, info)) {
                continue;
            }
            if (probe != null && !probe.isEngineAvailable(engine)) {
                continue;
            }
            return engine;
        }
        return null;
    }
}
