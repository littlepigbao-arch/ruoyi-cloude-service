package com.ruoyi.ai.mapper;

import com.ruoyi.ai.domain.AiWorkbook;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI 报表文档 Mapper
 *
 * @author ruoyi
 */
public interface AiWorkbookMapper
{
    /**
     * 新增文档
     */
    int insertWorkbook(AiWorkbook workbook);

    /**
     * 更新文档（名称 + 内容）
     */
    int updateWorkbook(AiWorkbook workbook);

    /**
     * 查询某用户的全部文档（不含 content，仅列表元数据）
     */
    List<AiWorkbook> selectListByUserId(@Param("userId") Long userId);

    /**
     * 按 ID + 用户查询单条文档（含 content）
     */
    AiWorkbook selectById(@Param("workbookId") Long workbookId, @Param("userId") Long userId);

    /**
     * 按 ID + 用户删除文档
     */
    int deleteById(@Param("workbookId") Long workbookId, @Param("userId") Long userId);

    /**
     * 统计同用户下同名文档数量（excludeId 用于更新时排除自身）
     */
    int countByNameAndUserId(@Param("name") String name, @Param("userId") Long userId, @Param("excludeId") Long excludeId);

    /**
     * 仅更新文档名称
     */
    int updateName(@Param("workbookId") Long workbookId, @Param("userId") Long userId, @Param("name") String name, @Param("updateBy") String updateBy);
}
