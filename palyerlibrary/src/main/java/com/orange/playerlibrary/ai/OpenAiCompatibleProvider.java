package com.orange.playerlibrary.ai;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OpenAI 兼容协议的 HTTP Provider。
 * 使用 HttpURLConnection + Android 内置 org.json，零第三方依赖。
 * DeepSeek / 通义 Qwen(DashScope 兼容模式) / 智谱 GLM / OpenAI 均可用同一套请求。
 */
public class OpenAiCompatibleProvider implements AiProvider {

    private static final String TAG = "OpenAiProvider";

    @Override
    public String chat(List<ChatMessage> messages, TranslatorSettings settings) throws AiException {
        if (settings.getApiKey() == null || settings.getApiKey().trim().isEmpty()) {
            throw new AiException("未配置 API Key（设置里填写 " + settings.getBaseUrl() + " 申请的 key）", false);
        }

        HttpURLConnection conn = null;
        try {
            String body = buildRequestBody(messages, settings);
            URL url = new URL(settings.getChatCompletionsUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(settings.getTimeoutMs());
            conn.setReadTimeout(settings.getTimeoutMs());
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + settings.getApiKey());
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            try {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            } finally {
                os.close();
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readBody(conn.getErrorStream());
                // 429 限流 / 5xx 服务端错误 → 可重试；4xx（含 401/403 key 问题）→ 永久
                boolean retryable = code == 429 || code >= 500;
                throw new AiException("HTTP " + code + ": " + truncate(err, 200), retryable, code, null);
            }

            String responseBody = readBody(conn.getInputStream());
            return extractContent(responseBody);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            // 网络不可达/超时/解析失败均为瞬时问题，可重试
            throw new AiException("网络错误: " + e.getMessage(), true, 0, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String buildRequestBody(List<ChatMessage> messages, TranslatorSettings settings)
            throws JSONException {
        JSONObject root = new JSONObject();
        root.put("model", settings.getModel());
        root.put("stream", false);

        JSONArray msgArr = new JSONArray();
        for (ChatMessage m : messages) {
            JSONObject jm = new JSONObject();
            jm.put("role", m.getRole());
            jm.put("content", m.getContent());
            msgArr.put(jm);
        }
        root.put("messages", msgArr);
        return root.toString();
    }

    @Override
    public List<String> listModels(TranslatorSettings settings) throws AiException {
        if (settings.getApiKey() == null || settings.getApiKey().trim().isEmpty()) {
            throw new AiException("未配置 API Key", false);
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(settings.getModelsUrl());
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(settings.getTimeoutMs());
            conn.setReadTimeout(settings.getTimeoutMs());
            conn.setRequestProperty("Authorization", "Bearer " + settings.getApiKey());

            int code = conn.getResponseCode();
            if (code != 200) {
                String err = readBody(conn.getErrorStream());
                // 404 常见于端点未实现 /models：归为永久错误，提示手输模型名
                boolean retryable = code == 429 || code >= 500;
                throw new AiException("获取模型列表失败 HTTP " + code + ": " + truncate(err, 200),
                        retryable, code, null);
            }
            return extractModelIds(readBody(conn.getInputStream()));
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("网络错误: " + e.getMessage(), true, 0, e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 从 /models 响应中提取 data[].id，去重后按字母序（解析逻辑独立便于单测） */
    static List<String> extractModelIds(String responseBody) throws AiException {
        List<String> ids = new java.util.ArrayList<>();
        try {
            JSONObject root = new JSONObject(responseBody);
            JSONArray data = root.optJSONArray("data");
            if (data == null) {
                throw new AiException("响应无 data 字段（端点可能不兼容 /models）: "
                        + truncate(responseBody, 150), false);
            }
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "").trim();
                if (!id.isEmpty() && !ids.contains(id)) {
                    ids.add(id);
                }
            }
        } catch (JSONException e) {
            throw new AiException("响应 JSON 解析失败: " + truncate(responseBody, 150), false, 0, e);
        }
        if (ids.isEmpty()) {
            throw new AiException("该端点未返回任何模型", false);
        }
        java.util.Collections.sort(ids);
        return ids;
    }

    /** 从 /chat/completions 响应中提取 choices[0].message.content */
    private String extractContent(String responseBody) throws AiException {
        try {
            JSONObject root = new JSONObject(responseBody);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new AiException("响应无 choices（可能模型名错误或额度不足）: "
                        + truncate(responseBody, 150), false);
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            if (message == null) {
                throw new AiException("响应 message 缺失: " + truncate(responseBody, 150), false);
            }
            String content = message.optString("content", "").trim();
            if (content.isEmpty()) {
                throw new AiException("响应 content 为空", false);
            }
            return content;
        } catch (JSONException e) {
            throw new AiException("响应 JSON 解析失败: " + truncate(responseBody, 150), false, 0, e);
        }
    }

    private String readBody(InputStream in) throws java.io.IOException {
        if (in == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
