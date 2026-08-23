package com.ruoyi.ai.service;

/**
 * 大模型不可用异常（api-key 缺失、HTTP 非 200、超时等场景）
 * 由上层捕获并降级返回 503，前端自动回退本地正则解析
 *
 * @author ruoyi
 */
public class LlmUnavailableException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public LlmUnavailableException(String message)
    {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
