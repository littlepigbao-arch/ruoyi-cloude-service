package com.ruoyi.ai.domain;

import java.util.Date;

/**
 * AI 消息对象 ai_message
 *
 * @author ruoyi
 */
public class AiMessage
{
    /** 消息 ID（自增） */
    private Long messageId;

    /** 会话 ID */
    private String conversationId;

    /** 角色：user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    /** assistant 返回的 actions 数组（JSON 字符串） */
    private String actions;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private String model;

    private String createBy;
    private Date createTime;

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getActions() { return actions; }
    public void setActions(String actions) { this.actions = actions; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public Integer getTotalTokens() { return totalTokens; }
    public void setTotalTokens(Integer totalTokens) { this.totalTokens = totalTokens; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }
}
