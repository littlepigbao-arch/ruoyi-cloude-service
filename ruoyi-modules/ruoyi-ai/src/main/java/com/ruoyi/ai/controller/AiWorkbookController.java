package com.ruoyi.ai.controller;

import com.ruoyi.ai.domain.AiWorkbook;
import com.ruoyi.ai.service.AiWorkbookService;
import com.ruoyi.common.core.domain.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 报表文档接口
 *
 * @author ruoyi
 */
@RestController
@RequestMapping("/ai/workbook")
public class AiWorkbookController
{
    @Autowired
    private AiWorkbookService workbookService;

    /**
     * 查询当前用户的文档列表（不含内容）
     */
    @GetMapping("/list")
    public R<List<AiWorkbook>> list()
    {
        return workbookService.list();
    }

    /**
     * 保存/更新文档（workbookId 为空新增，否则更新）
     */
    @PostMapping("/save")
    public R<AiWorkbook> save(@RequestBody AiWorkbook workbook)
    {
        return workbookService.save(workbook);
    }

    /**
     * 查询单条文档（含完整快照内容）
     */
    @GetMapping("/{workbookId}")
    public R<AiWorkbook> get(@PathVariable Long workbookId)
    {
        return workbookService.get(workbookId);
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{workbookId}")
    public R<Void> remove(@PathVariable Long workbookId)
    {
        return workbookService.remove(workbookId);
    }
}
