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

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

        // 6. 解析模型输出为 ChatResponse
        ChatResponse chatResp;
        try
        {
            String content = llmResp.getContent() == null ? "" : llmResp.getContent().trim();
            chatResp = mapper.readValue(content, ChatResponse.class);
            if (chatResp.getReply() == null)
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
}
