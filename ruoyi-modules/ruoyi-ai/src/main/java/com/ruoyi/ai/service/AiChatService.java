package com.ruoyi.ai.service;

import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.dto.ChatRequest;
import com.ruoyi.ai.domain.dto.ChatResponse;
import com.ruoyi.common.core.domain.R;

import java.util.List;

/**
 * AI 表格助手聊天服务
 *
 * @author ruoyi
 */
public interface AiChatService
{
    /**
     * 处理用户聊天请求
     *
     * @param req 前端请求（message + context + history + conversationId）
     * @return 统一响应 R<ChatResponse>
     */
    R<ChatResponse> chat(ChatRequest req);

    /**
     * 查询某会话的历史消息
     *
     * @param conversationId 会话 ID
     * @return 统一响应 R<List<AiMessage>>（按时间升序）
     */
    R<List<AiMessage>> history(String conversationId);
}
