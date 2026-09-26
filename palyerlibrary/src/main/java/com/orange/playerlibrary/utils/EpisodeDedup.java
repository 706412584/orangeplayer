package com.orange.playerlibrary.utils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 选集去重（纯函数）。
 *
 * <p>为什么需要：宿主分批加载、或多来源合并选集时会把同一集重复入列。重复项有两个
 * 实际危害：
 * <ul>
 *   <li>「当前集高亮」按地址比对，重复地址会同时命中多条 → 视觉上多条高亮；</li>
 *   <li>选集记忆存的是**下标**，重复项会让恢复指向错误的那一条。</li>
 * </ul>
 *
 * <p>比对规则：**url 精确相等**（不去 query、不做前缀/包含匹配）。
 * 不能复用 {@link EpisodeUrlMatcher#matches}——那是「条目地址 vs 正在播的地址」的
 * 三级回退匹配，语义更宽（会忽略查询串）；用它去重会把
 * {@code .../v.m3u8?ep=1} 与 {@code .../v.m3u8?ep=2} 误判为同一集。
 * 不同集的地址可能只差查询串，所以去重必须精确。
 */
public final class EpisodeDedup {

    private EpisodeDedup() {
    }

    /**
     * 从候选地址里挑出「尚未存在」的那些（保序）。
     *
     * @param existing 已在列表中的地址
     * @param candidates 待加入的地址（保序）
     * @return 需要新增的下标（相对 candidates），已存在或为空的跳过
     */
    public static int[] pickNewIndexes(List<String> existing, List<String> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new int[0];
        }
        Set<String> seen = new HashSet<>();
        if (existing != null) {
            for (String url : existing) {
                if (url != null && !url.isEmpty()) {
                    seen.add(url);
                }
            }
        }
        // 候选内部也要去重：同一批里出现两次的只取第一次
        java.util.ArrayList<Integer> picked = new java.util.ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            String url = candidates.get(i);
            if (url == null || url.isEmpty() || seen.contains(url)) {
                continue;
            }
            seen.add(url);
            picked.add(i);
        }
        int[] out = new int[picked.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = picked.get(i);
        }
        return out;
    }

    /**
     * 列表里是否已存在该地址。
     */
    public static boolean contains(List<String> existing, String url) {
        if (url == null || url.isEmpty() || existing == null) {
            return false;
        }
        for (String u : existing) {
            if (url.equals(u)) {
                return true;
            }
        }
        return false;
    }
}
