package com.ruoyi.ai.domain.dto;

import java.util.List;

/**
 * AI 表格助手响应数据体
 *
 * @author ruoyi
 */
public class ChatResponse
{
    /** 给用户看的自然语言回复（显示在对话气泡里） */
    private String reply;

    /** 回传会话 ID */
    private String conversationId;

    /** 结构化指令数组，前端逐条执行；可为空数组（纯回复） */
    private List<Action> actions;

    /** 预留，默认 false */
    private Boolean needFeedback = false;

    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public List<Action> getActions() { return actions; }
    public void setActions(List<Action> actions) { this.actions = actions; }

    public Boolean getNeedFeedback() { return needFeedback; }
    public void setNeedFeedback(Boolean needFeedback) { this.needFeedback = needFeedback; }
}
