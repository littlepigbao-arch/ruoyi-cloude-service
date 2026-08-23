package com.ruoyi.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 大模型 HTTP 客户端（Kimi/Moonshot，OpenAI 兼容格式）
 *
 * @author ruoyi
 */
@Component
public class LlmClient
{
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    @Value("${ai.model.base-url:https://api.moonshot.cn/v1/chat/completions}")
    private String baseUrl;

    @Value("${ai.model.api-key:}")
    private String apiKey;

    @Value("${ai.model.model:moonshot-v1-8k}")
    private String model;

    @Value("${ai.model.temperature:0.3}")
    private double temperature;

    @Value("${ai.model.max-tokens:2048}")
    private int maxTokens;

    @Value("${ai.model.timeout-ms:55000}")
    private int timeoutMs;

    private OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    public void init()
    {
        // connect 5s，read 略小于前端 60s 超时
        int readMs = Math.min(timeoutMs, 55000);
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(readMs, TimeUnit.MILLISECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build();
        log.info("[LlmClient] 初始化完成 baseUrl={}, model={}, apiKey={}, timeout={}ms",
                baseUrl, model, (apiKey == null || apiKey.isEmpty() ? "<空>" : "<已配置>"), readMs);
    }

    /**
     * 调用大模型聊天接口
     *
     * @param messages OpenAI 格式消息数组 [{role,content},...]
     * @return 模型响应（content + tokens）
     * @throws LlmUnavailableException api-key 缺失 / HTTP 非 200 / 超时
     */
    public LlmResponse chat(List<Map<String, String>> messages) throws LlmUnavailableException
    {
        if (apiKey == null || apiKey.trim().isEmpty())
        {
            throw new LlmUnavailableException("AI_API_KEY 未配置，请设置环境变量 AI_API_KEY");
        }

        String body;
        try
        {
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("model", model);
            payload.put("messages", messages);
            payload.put("temperature", temperature);
            payload.put("max_tokens", maxTokens);
            // Kimi 支持 OpenAI 兼容的 JSON 模式，强制输出合法 JSON
            payload.put("response_format", java.util.Collections.singletonMap("type", "json_object"));
            body = mapper.writeValueAsString(payload);
        }
        catch (Exception e)
        {
            throw new LlmUnavailableException("构造请求体失败", e);
        }

        Request request = new Request.Builder()
                .url(baseUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body, JSON_TYPE))
                .build();

        try (Response resp = httpClient.newCall(request).execute())
        {
            String respText = resp.body() != null ? resp.body().string() : "";
            if (!resp.isSuccessful())
            {
                log.error("[LlmClient] 调用失败 HTTP={} body={}", resp.code(), truncate(respText, 500));
                throw new LlmUnavailableException("大模型 HTTP " + resp.code() + ": " + truncate(respText, 200));
            }

            return parseResponse(respText);
        }
        catch (LlmUnavailableException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            log.error("[LlmClient] 调用异常", e);
            throw new LlmUnavailableException("大模型调用异常: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 OpenAI 兼容响应：choices[0].message.content + usage
     */
    private LlmResponse parseResponse(String respText) throws LlmUnavailableException
    {
        try
        {
            JsonNode root = mapper.readTree(respText);
            LlmResponse out = new LlmResponse();

            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.size() == 0)
            {
                throw new LlmUnavailableException("响应缺少 choices: " + truncate(respText, 200));
            }
            JsonNode msg = choices.get(0).path("message").path("content");
            out.setContent(msg.asText(""));

            JsonNode usage = root.path("usage");
            out.setPromptTokens(usage.path("prompt_tokens").asInt(0));
            out.setCompletionTokens(usage.path("completion_tokens").asInt(0));
            out.setTotalTokens(usage.path("total_tokens").asInt(0));
            out.setModel(root.path("model").asText(model));

            if (out.getContent() == null || out.getContent().isEmpty())
            {
                throw new LlmUnavailableException("模型输出内容为空");
            }
            return out;
        }
        catch (LlmUnavailableException e)
        {
            throw e;
        }
        catch (Exception e)
        {
            throw new LlmUnavailableException("响应解析失败: " + e.getMessage(), e);
        }
    }

    private String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
