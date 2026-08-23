package com.ruoyi.ai.service;

/**
 * 大模型响应封装
 *
 * @author ruoyi
 */
public class LlmResponse
{
    /** 模型输出的文本内容（应当是 JSON 字符串 {"reply":"...","actions":[...]}） */
    private String content;

    /** 输入 tokens 数 */
    private int promptTokens;

    /** 输出 tokens 数 */
    private int completionTokens;

    /** 总 tokens 数 */
    private int totalTokens;

    /** 实际使用的模型名 */
    private String model;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
