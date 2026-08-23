package com.ruoyi.ai.mapper;

import com.ruoyi.ai.domain.AiConversation;
import org.apache.ibatis.annotations.Param;

/**
 * AI 会话 Mapper
 *
 * @author ruoyi
 */
public interface AiConversationMapper
{
    /**
     * 新增会话
     */
    int insertConversation(AiConversation conversation);

    /**
     * 根据会话 ID 查询会话
     */
    AiConversation selectByConvId(@Param("conversationId") String conversationId);

    /**
     * 更新会话最后更新时间
     */
    int updateTime(@Param("conversationId") String conversationId, @Param("updateBy") String updateBy);
}
