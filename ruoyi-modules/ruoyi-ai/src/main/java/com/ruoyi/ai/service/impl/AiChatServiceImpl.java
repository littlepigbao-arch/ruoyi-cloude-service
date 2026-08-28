package com.ruoyi.ai.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiConversation;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.dto.ChatRequest;
import com.ruoyi.ai.domain.dto.ChatResponse;
import com.ruoyi.ai.mapper.AiConversationMapper;
import com.ruoyi.ai.mapper.AiMessageMapper;
import com.ruoyi.ai.service.AiChatService;
import com.ruoyi.ai.service.LlmClient;
import com.ruoyi.ai.service.LlmResponse;
import com.ruoyi.ai.service.LlmUnavailableException;
import com.ruoyi.ai.service.PromptBuilder;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 表格助手聊天服务实现
 *
 * @author ruoyi
 */
@Service
public class AiChatServiceImpl implements AiChatService
{
    private static final Logger log = LoggerFactory.getLogger(AiChatServiceImpl.class);

    private static final int HISTORY_LIMIT = 10;

    /** 503 状态码：前端规范约定的"服务不可用，回退本地 mock" */
    private static final int SERVICE_UNAVAILABLE = 503;

    @Autowired
    private AiConversationMapper conversationMapper;

    @Autowired
    private AiMessageMapper messageMapper;

    @Autowired
    private LlmClient llmClient;

    @Autowired
    private PromptBuilder promptBuilder;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<ChatResponse> chat(ChatRequest req)
    {
        Long userId;
        String username;
        try
        {
            userId = SecurityUtils.getUserId();
            username = SecurityUtils.getUsername();
        }
        catch (Throwable e)
        {
            // 直接访问服务（未经网关 AuthFilter）时，SecurityContextHolder 无登录用户
            log.warn("[AiChat] 未获取到登录用户: {}", e.getMessage());
            return R.fail(SERVICE_UNAVAILABLE, "未登录或登录已过期");
        }

        // 1. 会话 ID 处理（前端传则用之，否则生成新的）
        String conversationId = req.getConversationId();
        if (conversationId == null || conversationId.trim().isEmpty())
        {
            conversationId = UUID.randomUUID().toString().replace("-", "");
        }

        // 2. 首次会话插入 conversation 记录
        if (conversationMapper.selectByConvId(conversationId) == null)
        {
            AiConversation conv = new AiConversation();
            conv.setConversationId(conversationId);
            conv.setUserId(userId);
            if (req.getContext() != null)
            {
                conv.setWorkbookName(req.getContext().getWorkbookName());
                conv.setSheetName(req.getContext().getSheetName());
            }
            conv.setStatus("0");
            conv.setCreateBy(username);
            conv.setCreateTime(new Date());
            conversationMapper.insertConversation(conv);
        }

        // 3. 持久化用户消息
        AiMessage userMsg = new AiMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(req.getMessage() == null ? "" : req.getMessage());
        userMsg.setCreateBy(username);
        userMsg.setCreateTime(new Date());
        messageMapper.insertMessage(userMsg);

        // 4. 拉取最近历史，组装 messages
        List<AiMessage> dbHistory = messageMapper.selectRecentByConvId(conversationId, HISTORY_LIMIT);

        List<Map<String, String>> messages;
        try
        {
            messages = promptBuilder.buildMessages(req, dbHistory);
        }
        catch (Exception e)
        {
            log.error("[AiChat] 构造 prompt 失败", e);
            return R.fail(SERVICE_UNAVAILABLE, "AI 服务暂时不可用");
        }

        // 5. 调用大模型
        LlmResponse llmResp;
        try
        {
            llmResp = llmClient.chat(messages);
        }
        catch (LlmUnavailableException e)
        {
            log.warn("[AiChat] 大模型不可用: {}", e.getMessage());
            return R.fail(SERVICE_UNAVAILABLE, "AI 服务暂时不可用");
        }
        catch (Exception e)
        {
            log.error("[AiChat] 大模型调用异常", e);
            return R.fail(SERVICE_UNAVAILABLE, "AI 服务暂时不可用");
        }

        // 6. 解析模型输出为 ChatResponse（含 JSON 修复兜底，避免大输出被截断后直接 503）
        ChatResponse chatResp;
        try
        {
            String content = llmResp.getContent() == null ? "" : llmResp.getContent().trim();
            chatResp = parseChatResponse(content);
            if (chatResp.getReply() == null || chatResp.getReply().trim().isEmpty())
            {
                chatResp.setReply(content.length() > 200 ? content.substring(0, 200) + "..." : content);
            }
        }
        catch (Exception e)
        {
            log.error("[AiChat] 解析模型输出失败: {}", e.getMessage());
            return R.fail(SERVICE_UNAVAILABLE, "AI 服务暂时不可用");
        }

        chatResp.setConversationId(conversationId);

        // 7. 持久化助手消息（含 actions JSON 字符串）
        AiMessage assistantMsg = new AiMessage();
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(chatResp.getReply());
        try
        {
            assistantMsg.setActions(mapper.writeValueAsString(chatResp.getActions()));
        }
        catch (Exception ignore)
        {
        }
        assistantMsg.setPromptTokens(llmResp.getPromptTokens());
        assistantMsg.setCompletionTokens(llmResp.getCompletionTokens());
        assistantMsg.setTotalTokens(llmResp.getTotalTokens());
        assistantMsg.setModel(llmResp.getModel());
        assistantMsg.setCreateBy(username);
        assistantMsg.setCreateTime(new Date());
        messageMapper.insertMessage(assistantMsg);

        // 8. 更新会话最后更新时间
        conversationMapper.updateTime(conversationId, username);

        return R.ok(chatResp);
    }

    @Override
    public R<List<AiMessage>> history(String conversationId)
    {
        if (conversationId == null || conversationId.trim().isEmpty())
        {
            return R.fail("会话 ID 不能为空");
        }

        Long userId;
        try
        {
            userId = SecurityUtils.getUserId();
        }
        catch (Throwable e)
        {
            return R.fail(SERVICE_UNAVAILABLE, "未登录或登录已过期");
        }

        // 校验会话归属，防止越权查询他人会话
        AiConversation conv = conversationMapper.selectByConvId(conversationId);
        if (conv == null || !userId.equals(conv.getUserId()))
        {
            return R.fail("会话不存在或无权访问");
        }

        List<AiMessage> messages = messageMapper.selectByConvId(conversationId);
        return R.ok(messages);
    }

    /**
     * 解析模型输出为 ChatResponse，带 JSON 修复兜底：
     * 1. 直接解析；2. 提取最外层 {}、去围栏、补齐未闭合的引号/括号后重试；
     * 3. 仍失败则提取 reply 文本、actions 置空，避免大输出被截断后落到 503。
     */
    private ChatResponse parseChatResponse(String content)
    {
        try
        {
            return mapper.readValue(content, ChatResponse.class);
        }
        catch (Exception first)
        {
            String repaired = repairJson(content);
            if (repaired != null)
            {
                try
                {
                    return mapper.readValue(repaired, ChatResponse.class);
                }
                catch (Exception second)
                {
                    log.warn("[AiChat] JSON 修复后仍解析失败: {}", second.getMessage());
                }
            }
            // 最终兜底：至少返回 reply，不让用户看到 503
            log.warn("[AiChat] 模型输出 JSON 非法，已降级为纯文本回复，原文前 200 字: {}", truncate(content, 200));
            ChatResponse fallback = new ChatResponse();
            fallback.setReply(extractReply(content));
            fallback.setActions(new ArrayList<>());
            fallback.setNeedFeedback(false);
            return fallback;
        }
    }

    /**
     * 修复被截断/包裹的 JSON：去 markdown 围栏 → 取最外层 {} → 补齐未闭合结构
     */
    private String repairJson(String raw)
    {
        if (raw == null) return null;
        String s = raw.trim();
        s = s.replaceAll("(?s)^```[a-zA-Z]*\\s*", "");
        s = s.replaceAll("(?s)\\s*```$", "");
        s = s.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end < start) return null;
        String json = s.substring(start, end + 1);
        return balanceJson(json);
    }

    /**
     * 补齐未闭合的字符串 / 数组 / 对象（截断在字符串中间时先闭合引号）
     */
    private String balanceJson(String json)
    {
        StringBuilder sb = new StringBuilder(json);
        boolean inStr = false;
        boolean esc = false;
        int brace = 0;
        int bracket = 0;
        for (int i = 0; i < sb.length(); i++)
        {
            char c = sb.charAt(i);
            if (inStr)
            {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{') brace++;
            else if (c == '}') brace--;
            else if (c == '[') bracket++;
            else if (c == ']') bracket--;
        }
        if (inStr) sb.append('"');
        while (bracket > 0) { sb.append(']'); bracket--; }
        while (brace > 0) { sb.append('}'); brace--; }
        return sb.toString();
    }

    /**
     * 从非法 JSON 中尽力提取 reply 文本
     */
    private String extractReply(String content)
    {
        if (content == null) return "";
        Matcher m = Pattern.compile("\"reply\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(content);
        if (m.find())
        {
            return m.group(1).replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return content.length() > 200 ? content.substring(0, 200) + "..." : content;
    }

    private String truncate(String s, int max)
    {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
