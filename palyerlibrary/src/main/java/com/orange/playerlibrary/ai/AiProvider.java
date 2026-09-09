package com.orange.playerlibrary.ai;

import java.util.List;

/**
 * AI 提供方抽象：一次对话补全（同步）。
 * 实现建议满足 OpenAI 兼容协议，保证 DeepSeek / Qwen / GLM 等可互相替换。
 */
public interface AiProvider {

    /**
     * 发送一轮对话，返回助手完整回复文本。
     * @throws AiException 调用失败（retryable 区分瞬时/永久）
     */
    String chat(List<ChatMessage> messages, TranslatorSettings settings) throws AiException;
}
