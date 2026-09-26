package com.orange.playerlibrary.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 选集去重单元测试。
 *
 * <p>关键语义：按 url **精确**比对。不能忽略查询串——不同集可能只差 query
 * （{@code .../v.m3u8?ep=1} 与 {@code .../v.m3u8?ep=2}），忽略 query 会把它们
 * 误判成同一集、丢集。
 */
public class EpisodeDedupTest {

    private static final String A = "https://v.cdnlz22.com/20240815/3725_2916c479/index.m3u8";
    private static final String B = "https://v.cdnlz22.com/20240815/3734_7cd1c271/index.m3u8";
    private static final String C = "https://v.lzcdn26.com/20250426/15616_67cb4081/index.m3u8";

    @Test
    public void 全部已存在_不新增() {
        int[] pick = EpisodeDedup.pickNewIndexes(
                Arrays.asList(A, B, C), Arrays.asList(A, B));
        assertArrayEquals(new int[0], pick);
    }

    @Test
    public void 部分已存在_只挑新的且保序() {
        int[] pick = EpisodeDedup.pickNewIndexes(
                Arrays.asList(A), Arrays.asList(A, B, C));
        assertArrayEquals(new int[]{1, 2}, pick);
    }

    @Test
    public void 空列表_全部新增() {
        int[] pick = EpisodeDedup.pickNewIndexes(
                Collections.<String>emptyList(), Arrays.asList(A, B));
        assertArrayEquals(new int[]{0, 1}, pick);
    }

    @Test
    public void 空候选_不新增() {
        assertArrayEquals(new int[0], EpisodeDedup.pickNewIndexes(Arrays.asList(A), Collections.<String>emptyList()));
        assertArrayEquals(new int[0], EpisodeDedup.pickNewIndexes(Arrays.asList(A), null));
    }

    @Test
    public void 候选内部重复_只取第一次() {
        int[] pick = EpisodeDedup.pickNewIndexes(
                Collections.<String>emptyList(), Arrays.asList(A, A, B));
        assertArrayEquals(new int[]{0, 2}, pick);
    }

    @Test
    public void 空地址被跳过() {
        int[] pick = EpisodeDedup.pickNewIndexes(
                Arrays.asList(A), Arrays.asList("", null, B));
        assertArrayEquals(new int[]{2}, pick);
    }

    /**
     * 回归：不能忽略查询串。不同集只差 query 时必须都保留。
     * （若误用 EpisodeUrlMatcher 那套「忽略 query」的匹配去重，这里会丢集。）
     */
    @Test
    public void 仅查询串不同_视为不同集都保留() {
        String ep1 = "https://h/v.m3u8?ep=1";
        String ep2 = "https://h/v.m3u8?ep=2";
        int[] pick = EpisodeDedup.pickNewIndexes(Arrays.asList(ep1), Arrays.asList(ep1, ep2));
        assertArrayEquals("ep2 与 ep1 仅 query 不同，必须保留", new int[]{1}, pick);
    }

    @Test
    public void contains_精确比对() {
        List<String> list = Arrays.asList(A, B);
        assertTrue(EpisodeDedup.contains(list, A));
        assertTrue(EpisodeDedup.contains(list, B));
        assertFalse(EpisodeDedup.contains(list, C));
        assertFalse(EpisodeDedup.contains(list, ""));
        assertFalse(EpisodeDedup.contains(list, null));
        assertFalse(EpisodeDedup.contains(null, A));
    }

    @Test
    public void contains_不做前缀包含匹配() {
        // .../1.mp4 与 .../10.mp4 互为前缀，不能算同一集
        List<String> list = Collections.singletonList("https://h/1.mp4");
        assertFalse(EpisodeDedup.contains(list, "https://h/10.mp4"));
        assertTrue(EpisodeDedup.contains(list, "https://h/1.mp4"));
    }

    @Test
    public void 大批量_新增顺序稳定() {
        List<String> existing = Arrays.asList(A);
        List<String> candidates = Arrays.asList(B, A, C, B);
        int[] pick = EpisodeDedup.pickNewIndexes(existing, candidates);
        // B(0) 新；A(1) 已存在；C(2) 新；B(3) 与已加入的 B 重复
        assertArrayEquals(new int[]{0, 2}, pick);
    }
}
