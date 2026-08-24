package com.ruoyi.ai.mapper;

import com.ruoyi.ai.domain.AiMessage;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI 消息 Mapper
 *
 * @author ruoyi
 */
public interface AiMessageMapper
{
    /**
     * 新增消息
     */
    int insertMessage(AiMessage message);

    /**
     * 查询某会话最近 N 条消息（按时间升序，便于喂给大模型）
     */
    List<AiMessage> selectRecentByConvId(@Param("conversationId") String conversationId, @Param("limit") int limit);

    /**
     * 查询某会话的全部消息（按 message_id 升序，用于历史记录展示）
     */
    List<AiMessage> selectByConvId(@Param("conversationId") String conversationId);
}
