package com.orange.playerlibrary.ai;

/**
 * 在线 LLM 翻译接入配置。
 * 默认指向 DeepSeek 开放平台（OpenAI 兼容协议，key 可在 https://platform.deepseek.com 申请）；
 * baseUrl/model 均可改指向 Qwen( dashscope )、智谱 GLM 等任何 OpenAI 兼容端点。
 */
public class TranslatorSettings {

    /** OpenAI 兼容聊天补全路径后缀，附加在 baseUrl 后 */
    public static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    public static final String DEFAULT_MODEL = "deepseek-chat";
    public static final int DEFAULT_TIMEOUT_MS = 30_000;
    public static final int DEFAULT_MAX_RETRIES = 3;
    /** 每批字符预算：控制单次请求 token 量，兼容免费档单次限额 */
    public static final int DEFAULT_BATCH_CHAR_BUDGET = 1500;
    /** 每批最大行数（双保险，防超长单行撑爆预算） */
    public static final int DEFAULT_BATCH_MAX_LINES = 200;
    /** 传给下一批作为上文衔接的已译句数 */
    public static final int DEFAULT_CONTEXT_SENTENCES = 2;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final String targetLanguage;   // 目标语言描述，如 "简体中文" / "English"
    private final int timeoutMs;
    private final int maxRetries;
    private final int batchCharBudget;
    private final int batchMaxLines;
    private final int contextSentences;

    private TranslatorSettings(Builder b) {
        this.baseUrl = b.baseUrl;
        this.apiKey = b.apiKey;
        this.model = b.model;
        this.targetLanguage = b.targetLanguage;
        this.timeoutMs = b.timeoutMs;
        this.maxRetries = b.maxRetries;
        this.batchCharBudget = b.batchCharBudget;
        this.batchMaxLines = b.batchMaxLines;
        this.contextSentences = b.contextSentences;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getModel() {
        return model;
    }

    public String getTargetLanguage() {
        return targetLanguage;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public int getBatchCharBudget() {
        return batchCharBudget;
    }

    public int getBatchMaxLines() {
        return batchMaxLines;
    }

    public int getContextSentences() {
        return contextSentences;
    }

    /** 完整聊天补全端点 URL（baseUrl 末尾带 / 时自动去除） */
    public String getChatCompletionsUrl() {
        String base = baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + CHAT_COMPLETIONS_PATH;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String apiKey = "";
        private String model = DEFAULT_MODEL;
        private String targetLanguage = "简体中文";
        private int timeoutMs = DEFAULT_TIMEOUT_MS;
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private int batchCharBudget = DEFAULT_BATCH_CHAR_BUDGET;
        private int batchMaxLines = DEFAULT_BATCH_MAX_LINES;
        private int contextSentences = DEFAULT_CONTEXT_SENTENCES;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey == null ? "" : apiKey;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder targetLanguage(String targetLanguage) {
            this.targetLanguage = targetLanguage;
            return this;
        }

        public Builder timeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder batchCharBudget(int batchCharBudget) {
            this.batchCharBudget = batchCharBudget;
            return this;
        }

        public Builder batchMaxLines(int batchMaxLines) {
            this.batchMaxLines = batchMaxLines;
            return this;
        }

        public Builder contextSentences(int contextSentences) {
            this.contextSentences = contextSentences;
            return this;
        }

        public TranslatorSettings build() {
            return new TranslatorSettings(this);
        }
    }
}
