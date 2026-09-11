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

    /**
     * 拉取服务端可用模型 id 列表（OpenAI 兼容 GET /models）。
     * 供设置界面「获取模型」下拉选择，避免手输模型名出错。
     *
     * @throws AiException 调用失败（key 无效 / 端点不支持 / 网络问题）
     */
    List<String> listModels(TranslatorSettings settings) throws AiException;
}
