package com.ruoyi.ai.domain.dto;

import java.util.List;

/**
 * 工作簿级 AI 上下文。只携带各 Sheet 的受控摘要，避免把完整工作簿直接发送给模型。
 */
public class WorkbookContext
{
    private Long workbookId;
    private String workbookName;
    private String activeSheetId;
    private String activeSheetName;
    private Boolean truncated;
    private List<SheetContext> sheets;

    public Long getWorkbookId() { return workbookId; }
    public void setWorkbookId(Long workbookId) { this.workbookId = workbookId; }

    public String getWorkbookName() { return workbookName; }
    public void setWorkbookName(String workbookName) { this.workbookName = workbookName; }

    public String getActiveSheetId() { return activeSheetId; }
    public void setActiveSheetId(String activeSheetId) { this.activeSheetId = activeSheetId; }

    public String getActiveSheetName() { return activeSheetName; }
    public void setActiveSheetName(String activeSheetName) { this.activeSheetName = activeSheetName; }

    public Boolean getTruncated() { return truncated; }
    public void setTruncated(Boolean truncated) { this.truncated = truncated; }

    public List<SheetContext> getSheets() { return sheets; }
    public void setSheets(List<SheetContext> sheets) { this.sheets = sheets; }
}
