package com.ruoyi.ai.service;

import com.ruoyi.ai.domain.dto.ChatRequest;
import com.ruoyi.ai.domain.dto.ChatResponse;
import com.ruoyi.common.core.domain.R;

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
}
