package com.ruoyi.ai.controller;

import com.ruoyi.ai.domain.dto.ChatRequest;
import com.ruoyi.ai.domain.dto.ChatResponse;
import com.ruoyi.ai.service.AiChatService;
import com.ruoyi.common.core.domain.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 表格助手接口
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai")
public class AiChatController
{
    @Autowired
    private AiChatService aiChatService;

    /**
     * AI 表格助手聊天接口
     * 前端传入中文指令 + 表格 context，后端用大模型解析为 actions JSON 返回
     *
     * 鉴权：通过网关 AuthFilter 校验 token，本接口不再加 @PreAuthorize
     * 降级：模型不可用/超时/JSON 解析失败时返回 code=503，前端自动回退本地正则解析
     */
    @PostMapping("/chat")
    public R<ChatResponse> chat(@RequestBody ChatRequest req)
    {
        return aiChatService.chat(req);
    }
}
