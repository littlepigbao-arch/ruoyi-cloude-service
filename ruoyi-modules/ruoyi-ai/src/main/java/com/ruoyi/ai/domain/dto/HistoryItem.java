package com.ruoyi.ai.domain.dto;

/**
 * 对话历史单条消息
 *
 * @author ruoyi
 */
public class HistoryItem
{
    /** 角色：user / assistant */
    private String role;

    /** 消息内容 */
    private String content;

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
