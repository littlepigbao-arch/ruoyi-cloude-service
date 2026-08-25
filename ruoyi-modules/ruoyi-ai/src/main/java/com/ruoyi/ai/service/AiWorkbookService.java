package com.ruoyi.ai.service;

import com.ruoyi.ai.domain.AiWorkbook;
import com.ruoyi.common.core.domain.R;

import java.util.List;

/**
 * AI 报表文档服务
 *
 * @author ruoyi
 */
public interface AiWorkbookService
{
    /**
     * 查询当前用户的文档列表（仅元数据，不含内容）
     *
     * @return 统一响应 R<List<AiWorkbook>>
     */
    R<List<AiWorkbook>> list();

    /**
     * 保存/更新文档（workbookId 为空时新增，否则更新）
     *
     * @param workbook 文档（name + type + content）
     * @return 统一响应 R<AiWorkbook>（含回填的 workbookId）
     */
    R<AiWorkbook> save(AiWorkbook workbook);

    /**
     * 查询单条文档（含完整快照内容）
     *
     * @param workbookId 文档 ID
     * @return 统一响应 R<AiWorkbook>
     */
    R<AiWorkbook> get(Long workbookId);

    /**
     * 删除文档
     *
     * @param workbookId 文档 ID
     * @return 统一响应 R<Void>
     */
    R<Void> remove(Long workbookId);

    /**
     * 重命名文档（校验同用户下名称唯一）
     *
     * @param workbook 文档（workbookId + name）
     * @return 统一响应 R<AiWorkbook>
     */
    R<AiWorkbook> rename(AiWorkbook workbook);
}
