package com.ruoyi.ai.domain.dto;

import java.util.List;
import java.util.Map;

/**
 * AI 表格助手前端请求体
 *
 * @author ruoyi
 */
public class ChatRequest
{
    /** 用户当前输入的自然语言指令（中文） */
    private String message;

    /** 会话 ID，首轮可由后端生成并回传 */
    private String conversationId;

    /** 近几轮对话历史（role: user/assistant） */
    private List<HistoryItem> history;

    /** 当前表格状态快照，见 SheetContext */
    private SheetContext context;

    /** 客户端元信息 */
    private Map<String, Object> clientMeta;

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public List<HistoryItem> getHistory() { return history; }
    public void setHistory(List<HistoryItem> history) { this.history = history; }

    public SheetContext getContext() { return context; }
    public void setContext(SheetContext context) { this.context = context; }

    public Map<String, Object> getClientMeta() { return clientMeta; }
    public void setClientMeta(Map<String, Object> clientMeta) { this.clientMeta = clientMeta; }
}
