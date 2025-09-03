package com.ruoyi.job.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.ruoyi.common.core.web.domain.BaseEntity;
import com.ruoyi.job.util.ProgressFormatterUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.data.annotation.Id;

import java.math.BigDecimal;

/**
 * 直播预告报名信息导出实体
 */
@Data
@EqualsAndHashCode(callSuper = false)
public class ScClassUserInfo extends BaseEntity {

    @Id
    private Long id;

    //@ExcelIgnore // 不导出该字段
    private Long userId;

    //@ExcelIgnore // 不导出该字段
    private Long classId;

    //@ColumnWidth(25)
    // @ExcelProperty("名称(报名时)")
    private String name;

    //@ColumnWidth(25)
    //@ExcelProperty("单位")
    private String firm;

    //@ColumnWidth(25)
    //@ExcelProperty("专业")
    private String specialized;

    //@ColumnWidth(35)
    //@ExcelProperty("手机号")
    private String phonenumber;

    //@ColumnWidth(35)
    //@ExcelProperty("昵称")
    private String nickName;

    /**
     * 客户订单编号
     */
    //@ExcelProperty("客户订单编号")
    private String userSn;

    //@ExcelIgnore
    private Boolean todayAttendanceIs;

    @JsonIgnore
    // @ExcelProperty("是否打卡")
    private String attendanceIs;

    //@ExcelProperty("签到次数")
    private Integer attendanceCount;

    //@ExcelIgnore
    private BigDecimal progress;

    //@ExcelProperty("总学习进度")
    private String progressPercent;

    public String getAttendanceIs() {
        return attendanceCount.compareTo(0) > 0 ? "是" : "否";
    }

    public String getProgressPercent() {
        return ProgressFormatterUtil.format(this.progress.multiply(BigDecimal.valueOf(100)));
    }

}
