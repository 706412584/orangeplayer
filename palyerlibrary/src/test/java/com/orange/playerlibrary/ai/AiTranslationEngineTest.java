package com.orange.playerlibrary.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class AiTranslationEngineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** 假 Provider：解析 user 行 "idx|原文" 并回复 "idx|译<idx>"，可注入故障与术语输出 */
    private static class FakeAiProvider implements AiProvider {
        int callCount = 0;
        final List<String> systemContents = new ArrayList<>();
        boolean failNextCall;          // 下一调用抛 retryable（模拟网络抖动）
        int permanentFails;            // 前 N 次调用抛永久错误（模拟 key 失效）
        String glossaryPerResponse = "";   // 每次响应追加的术语行（"" 表示不输出）
        int respondTimes = 1;              // 每个批次 mock 要应答几次（由 call 计数自然驱动）

        @Override
        public String chat(List<ChatMessage> messages, TranslatorSettings settings)
                throws AiException {
            callCount++;
            StringBuilder sys = new StringBuilder();
            for (ChatMessage m : messages) {
                if (ChatMessage.ROLE_SYSTEM.equals(m.getRole())) {
                    sys.append(m.getContent());
                }
            }
            systemContents.add(sys.toString());

            if (failNextCall) {
                failNextCall = false;
                throw new AiException("网络抖动", true);
            }
            if (permanentFails > 0) {
                permanentFails--;
                throw new AiException("key 失效或额度不足", false);
            }

            StringBuilder reply = new StringBuilder();
            for (ChatMessage m : messages) {
                if (ChatMessage.ROLE_USER.equals(m.getRole())) {
                    String[] userLines = m.getContent().split("\n");
                    for (String line : userLines) {
                        if (line.trim().isEmpty()) {
                            continue;
                        }
                        String[] parts = line.split("\\|", 2);
                        if (parts.length == 2) {
                            String idx = parts[0].trim();
                            reply.append(idx).append("|译").append(idx).append('\n');
                        }
                    }
                }
            }
            if (!glossaryPerResponse.isEmpty()) {
                reply.append(glossaryPerResponse).append('\n');
            }
            return reply.toString();
        }
    }

    private List<SubtitleLine> lines(int n) {
        List<SubtitleLine> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new SubtitleLine(i, "原文行 " + i));
        }
        return list;
    }

    private TranslatorSettings settings() {
        return TranslatorSettings.builder()
                .apiKey("fake-key")
                .batchCharBudget(40)    // "原文行 10" ~5 字符/行 → 每批 ~8 行
                .maxRetries(2)
                .build();
    }

    private File cacheDir() throws Exception {
        return tmp.newFolder("cache");
    }

    @Test
    public void translatesAllLinesInBatches() throws Exception {
        FakeAiProvider provider = new FakeAiProvider();
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        List<SubtitleLine> input = lines(20);
        int[] lastProgress = {0, 0};

        AiTranslationEngine.Result result = engine.translateAll(cacheDir(), "k", input,
                settings(), (done, total) -> {
                    lastProgress[0] = done;
                    lastProgress[1] = total;
                });

        assertEquals(20, result.translatedThisRun);
        assertEquals(0, result.untranslated);
        assertTrue(result.errors.isEmpty());
        assertTrue(provider.callCount >= 2);    // 预算 40 → 至少 2 批
        for (SubtitleLine l : input) {
            assertTrue("行 " + l.getIdx() + " 未译", l.hasTranslation());
            assertEquals("译" + l.getIdx(), l.getTranslated());
        }
        assertEquals(20, lastProgress[0]);
        assertEquals(20, lastProgress[1]);
    }

    @Test
    public void secondRunHitsCacheWithoutProviderCall() throws Exception {
        FakeAiProvider provider = new FakeAiProvider();
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        List<SubtitleLine> input = lines(10);
        File dir = cacheDir();

        engine.translateAll(dir, "key-v", input, settings(), null);
        int callsAfterFirst = provider.callCount;

        List<SubtitleLine> input2 = lines(10);
        AiTranslationEngine.Result r2 = engine.translateAll(dir, "key-v", input2, settings(), null);

        assertEquals(callsAfterFirst, provider.callCount);   // 无新调用
        assertEquals(0, r2.translatedThisRun);
        assertEquals(10, r2.cachedHits);
        assertEquals(0, r2.untranslated);
    }

    @Test
    public void retryableFailureRecoversOnRetry() throws Exception {
        FakeAiProvider provider = new FakeAiProvider();
        provider.failNextCall = true;   // 第一个批次第一次调用失败 → 重试成功
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        List<SubtitleLine> input = lines(5);

        AiTranslationEngine.Result result = engine.translateAll(cacheDir(), "k", input, settings(), null);

        assertEquals(5, result.translatedThisRun);
        assertTrue(result.errors.isEmpty());
        assertEquals(2, provider.callCount);   // 1 失败 + 1 成功
    }

    @Test
    public void permanentErrorSkipsBatchAndContinues() throws Exception {
        FakeAiProvider provider = new FakeAiProvider();
        provider.permanentFails = 1;    // 第一批（唯一调用？）永久失败
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        List<SubtitleLine> input = lines(3);   // 预算 40 只 1 批

        AiTranslationEngine.Result result = engine.translateAll(cacheDir(), "k", input, settings(), null);

        assertEquals(1, result.errors.size());
        assertEquals(0, result.translatedThisRun);
        assertEquals(3, result.untranslated);
        // 永久失败不重试
        assertEquals(1, provider.callCount);
    }

    @Test
    public void permanentErrorOnFirstBatchContinuesOthers() throws Exception {
        FakeAiProvider provider = new FakeAiProvider();
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        List<SubtitleLine> input = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            input.add(new SubtitleLine(i, "行" + i));
        }
        provider.permanentFails = 1;
        // 预算 40：每行 ~2 字符 → 每批约 20 行 → 3 批。第一批永久失败，后两批成功
        AiTranslationEngine.Result result = engine.translateAll(cacheDir(), "k", input, settings(), null);

        assertEquals(1, result.errors.size());
        assertTrue(result.translatedThisRun >= 40);
        assertTrue(result.untranslated > 0);
    }

    @Test
    public void glossaryAccumulatesIntoLaterSystemMessages() throws Exception {
        FakeAiProvider provider = new FakeAiProvider();
        provider.glossaryPerResponse = "## Naruto -> 鸣人";
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        List<SubtitleLine> input = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            input.add(new SubtitleLine(i, "行" + i));
        }
        // 多批 → 第二批起的 system 消息应携带术语
        engine.translateAll(cacheDir(), "k", input, settings(), null);

        assertTrue(provider.systemContents.size() >= 2);
        assertTrue("第二批 system 应含术语表",
                provider.systemContents.get(1).contains("Naruto -> 鸣人"));
        assertTrue(provider.systemContents.get(1).contains("已知术语对照"));
    }

    @Test
    public void laterBatchGetsContextTailOfPrevious() throws Exception {
        FakeAiProvider provider = new FakeAiProvider();
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        List<SubtitleLine> input = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            input.add(new SubtitleLine(i, "行" + i));
        }
        engine.translateAll(cacheDir(), "k", input, settings(), null);

        assertTrue(provider.systemContents.size() >= 2);
        String second = provider.systemContents.get(1);
        assertTrue("第二批应带上文衔接", second.contains("上一段已译内容"));
    }

    @Test
    public void blankLinesAreNotSentToProvider() throws Exception {
        FakeAiProvider provider = new FakeAiProvider();
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        List<SubtitleLine> input = new ArrayList<>();
        input.add(new SubtitleLine(0, "甲"));
        input.add(new SubtitleLine(1, ""));
        input.add(new SubtitleLine(2, "  "));
        input.add(new SubtitleLine(3, "乙"));

        AiTranslationEngine.Result result = engine.translateAll(cacheDir(), "k", input, settings(), null);

        assertEquals(2, result.translatedThisRun);
        assertTrue(input.get(1).getText().isEmpty());
        assertFalse(input.get(1).hasTranslation());
        assertTrue(input.get(0).hasTranslation());
        assertTrue(input.get(3).hasTranslation());
    }

    @Test
    public void worksWithoutCacheDir() throws Exception {
        FakeAiProvider provider = new FakeAiProvider();
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        AiTranslationEngine.Result result = engine.translateAll(null, "k", lines(5), settings(), null);
        assertEquals(5, result.translatedThisRun);
        assertEquals(0, result.cachedHits);
    }

    @Test
    public void unfinishedLinesReRunAfterPartialFailure() throws Exception {
        // 模拟崩溃/中断：第一轮永久错误跳过某批，第二轮只译剩余
        FakeAiProvider provider = new FakeAiProvider();
        AiTranslationEngine engine = new AiTranslationEngine(provider);
        List<SubtitleLine> input = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            input.add(new SubtitleLine(i, "行" + i));
        }
        File dir = cacheDir();

        provider.permanentFails = 2;   // 前 2 批永久失败
        AiTranslationEngine.Result r1 = engine.translateAll(dir, "k", input, settings(), null);
        assertTrue(r1.untranslated > 0);

        // 第二轮（无故障）：已译的走缓存，未译的重新请求
        int callsAfterR1 = provider.callCount;
        List<SubtitleLine> input2 = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            input2.add(new SubtitleLine(i, "行" + i));
        }
        AiTranslationEngine.Result r2 = engine.translateAll(dir, "k", input2, settings(), null);
        assertEquals(0, r2.untranslated);
        assertTrue(provider.callCount > callsAfterR1);
    }
}
