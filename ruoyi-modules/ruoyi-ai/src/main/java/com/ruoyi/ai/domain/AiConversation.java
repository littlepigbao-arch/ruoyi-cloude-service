package com.ruoyi.ai.domain;

import java.util.Date;

/**
 * AI 会话对象 ai_conversation
 *
 * @author ruoyi
 */
public class AiConversation
{
    /** 会话 ID（前端传或后端生成） */
    private String conversationId;

    /** 用户 ID */
    private Long userId;

    /** 工作簿名 */
    private String workbookName;

    /** 工作表名 */
    private String sheetName;

    /** 状态（0正常 1停用） */
    private String status;

    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
    private String remark;

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getWorkbookName() { return workbookName; }
    public void setWorkbookName(String workbookName) { this.workbookName = workbookName; }

    public String getSheetName() { return sheetName; }
    public void setSheetName(String sheetName) { this.sheetName = sheetName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }

    public Date getCreateTime() { return createTime; }
    public void setCreateTime(Date createTime) { this.createTime = createTime; }

    public String getUpdateBy() { return updateBy; }
    public void setUpdateBy(String updateBy) { this.updateBy = updateBy; }

    public Date getUpdateTime() { return updateTime; }
    public void setUpdateTime(Date updateTime) { this.updateTime = updateTime; }

    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
