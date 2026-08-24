package com.ruoyi.ai.domain;

import java.util.Date;

/**
 * AI 报表文档对象 ai_workbook
 *
 * @author ruoyi
 */
public class AiWorkbook
{
    /** 文档 ID（自增） */
    private Long workbookId;

    /** 文档名称 */
    private String name;

    /** 类型：created 新建 / imported 导入 */
    private String type;

    /** 表格快照 JSON 字符串 */
    private String content;

    /** 用户 ID */
    private Long userId;

    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;

    public Long getWorkbookId() { return workbookId; }
    public void setWorkbookId(Long workbookId) { this.workbookId = workbookId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }
}
