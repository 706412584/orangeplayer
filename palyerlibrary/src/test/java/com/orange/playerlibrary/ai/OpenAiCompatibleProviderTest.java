package com.orange.playerlibrary.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.List;

/**
 * /models 响应解析测试（「获取模型」按钮用）。
 * 解析逻辑与网络分离，这里只覆盖 JSON 形态与错误分支。
 */
public class OpenAiCompatibleProviderTest {

    @Test
    public void parsesModelIdsSortedAndDeduplicated() throws Exception {
        String body = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"gpt-4o\",\"object\":\"model\"},"
                + "{\"id\":\"agnes-2.5-flash\",\"object\":\"model\"},"
                + "{\"id\":\"gpt-4o\",\"object\":\"model\"}]}";
        List<String> ids = OpenAiCompatibleProvider.extractModelIds(body);
        // 去重后按字母序：gpt-4o 只出现一次
        assertEquals(2, ids.size());
        assertEquals("agnes-2.5-flash", ids.get(0));
        assertEquals("gpt-4o", ids.get(1));
    }

    @Test
    public void skipsEntriesWithoutId() throws Exception {
        String body = "{\"data\":[{\"object\":\"model\"},{\"id\":\"  \"},{\"id\":\"m1\"}]}";
        List<String> ids = OpenAiCompatibleProvider.extractModelIds(body);
        assertEquals(1, ids.size());
        assertEquals("m1", ids.get(0));
    }

    /** 端点不兼容 /models（无 data 字段）→ 永久错误，提示手输模型名 */
    @Test
    public void missingDataFieldIsPermanentError() {
        try {
            OpenAiCompatibleProvider.extractModelIds("{\"error\":\"not found\"}");
            fail("应抛 AiException");
        } catch (AiException e) {
            assertFalse("端点不兼容应归为永久错误", e.isRetryable());
        }
    }

    /** data 为空数组 → 永久错误，避免弹出空列表 */
    @Test
    public void emptyDataIsPermanentError() {
        try {
            OpenAiCompatibleProvider.extractModelIds("{\"data\":[]}");
            fail("应抛 AiException");
        } catch (AiException e) {
            assertFalse(e.isRetryable());
        }
    }

    @Test
    public void malformedJsonIsPermanentError() {
        try {
            OpenAiCompatibleProvider.extractModelIds("<html>502 Bad Gateway</html>");
            fail("应抛 AiException");
        } catch (AiException e) {
            assertFalse(e.isRetryable());
        }
    }

    /** 缺 apiKey 时不应发起请求（listModels 前置校验） */
    @Test
    public void listModelsRequiresApiKey() {
        TranslatorSettings settings = TranslatorSettings.builder()
                .baseUrl("https://example.com/v1")
                .apiKey("")
                .build();
        try {
            new OpenAiCompatibleProvider().listModels(settings);
            fail("应抛 AiException");
        } catch (AiException e) {
            assertTrue(e.getMessage().contains("API Key"));
            assertFalse(e.isRetryable());
        }
    }

    /** /models 端点由 baseUrl 派生（末尾斜杠需归一） */
    @Test
    public void modelsUrlNormalizesTrailingSlash() {
        assertEquals("https://x.com/v1/models",
                TranslatorSettings.builder().baseUrl("https://x.com/v1/").build().getModelsUrl());
        assertEquals("https://x.com/v1/models",
                TranslatorSettings.builder().baseUrl("https://x.com/v1").build().getModelsUrl());
    }
}
