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
            // SecurityUtils 静态初始化可能抛 ExceptionInInitializerError（Error 类型）
            log.warn("[AiChat] 未获取到登录用户: {}", e.getMessage());
            return R.fail(SERVICE_UNAVAILABLE, "未登录或登录已过期");
        }

        // 1. 会话 ID 处理（前端传则用之，否则生成新的）
        String conversationId = req.getConversationId();
        if (conversationId == null || conversationId.trim().isEmpty())
        {
            conversationId = UUID.randomUUID().toString().replace("-", "");
        }

        // 2. 会话存在性检查，不存在则创建
        AiConversation conv = conversationMapper.selectByConvId(conversationId);
        if (conv == null)
        {
            conv = new AiConversation();
            conv.setConversationId(conversationId);
            conv.setUserId(userId);
            conv.setStatus("0");
            if (req.getContext() != null)
            {
                conv.setWorkbookName(req.getContext().getWorkbookName());
                conv.setSheetName(req.getContext().getSheetName());
            }
            conv.setCreateBy(username);
            conversationMapper.insertConversation(conv);
            log.info("[AiChat] 新建会话 conversationId={} userId={}", conversationId, userId);
        }

        // 3. 查询数据库最近 N 条对话历史（前端未传 history 时作为上下文补全）
        List<AiMessage> dbHistory = messageMapper.selectRecentByConvId(conversationId, HISTORY_LIMIT);

        // 4. 组装大模型 messages
        List<Map<String, String>> messages = promptBuilder.buildMessages(req, dbHistory);

        // 5. 持久化 user 消息
        AiMessage userMsg = new AiMessage();
        userMsg.setConversationId(conversationId);
        userMsg.setRole("user");
        userMsg.setContent(req.getMessage());
        userMsg.setCreateBy(username);
        messageMapper.insertMessage(userMsg);

        // 6. 调用大模型
        LlmResponse llmResp;
        try
        {
            llmResp = llmClient.chat(messages);
        }
        catch (LlmUnavailableException e)
        {
            log.warn("[AiChat] 大模型不可用 conversationId={}: {}", conversationId, e.getMessage());
            return R.fail(SERVICE_UNAVAILABLE, "AI 服务暂时不可用");
        }
        catch (Exception e)
        {
            log.error("[AiChat] 大模型调用异常 conversationId={}", conversationId, e);
            return R.fail(SERVICE_UNAVAILABLE, "AI 服务暂时不可用");
        }

        // 7. 解析模型输出 JSON：{"reply":"...","actions":[...],"needFeedback":false}
        ChatResponse chatResp;
        String actionsJson = null;
        try
        {
            chatResp = mapper.readValue(llmResp.getContent(), ChatResponse.class);
            chatResp.setConversationId(conversationId);
            if (chatResp.getNeedFeedback() == null)
            {
                chatResp.setNeedFeedback(false);
            }
            // actions 序列化为字符串存储
            if (chatResp.getActions() != null)
            {
                actionsJson = mapper.writeValueAsString(chatResp.getActions());
            }
        }
        catch (Exception e)
        {
            log.error("[AiChat] 解析模型输出失败 conversationId={} content={}", conversationId, llmResp.getContent(), e);
            return R.fail(SERVICE_UNAVAILABLE, "AI 服务暂时不可用");
        }

        // 8. 持久化 assistant 消息
        AiMessage assistantMsg = new AiMessage();
        assistantMsg.setConversationId(conversationId);
        assistantMsg.setRole("assistant");
        assistantMsg.setContent(chatResp.getReply());
        assistantMsg.setActions(actionsJson);
        assistantMsg.setPromptTokens(llmResp.getPromptTokens());
        assistantMsg.setCompletionTokens(llmResp.getCompletionTokens());
        assistantMsg.setTotalTokens(llmResp.getTotalTokens());
        assistantMsg.setModel(llmResp.getModel());
        assistantMsg.setCreateBy(username);
        messageMapper.insertMessage(assistantMsg);

        // 9. 更新会话最后活动时间
        conversationMapper.updateTime(conversationId, username);

        log.info("[AiChat] 处理成功 conversationId={} actions={}", conversationId,
                chatResp.getActions() == null ? 0 : chatResp.getActions().size());

        return R.ok(chatResp);
    }
}
