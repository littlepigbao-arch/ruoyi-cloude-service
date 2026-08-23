package com.ruoyi.ai.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * AI 表格助手 action DTO（覆盖 21 种 type 的所有可能字段）
 * 序列化时只输出非空字段，符合前端规范的"字段必须符合校验"约束
 *
 * @author ruoyi
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Action
{
    /** action 类型（白名单 21 种） */
    private String type;

    /** 通用：A1 区域字符串，如 "A1"、"B2:D5"、"Sheet1!A1:B2" */
    private String range;

    /** setCell 的值：number/string/boolean；以 = 开头视为公式 */
    private Object value;

    /** setValues 的二维数组 [行][列] */
    private List<List<Object>> values;

    /** clearRange 的清理内容：content/format/all */
    private String clearWhat;

    /** setFormula 的公式字符串（不带 = 也行） */
    private String formula;

    /** merge 模式：default/across/vertically */
    private String mode;

    /** merge 是否强制 */
    private Boolean force;

    /** insertRows/insertColumns 的插入位置（1-based） */
    private Integer rowIndex;
    private Integer columnIndex;

    /** deleteRows/deleteColumns 的起始位置（1-based） */
    private Integer rowPosition;
    private Integer columnPosition;

    /** insert/delete 数量，默认 1，上限 100 */
    private Integer count;

    /** insert 的位置方向：after/before/at */
    private String position;

    /** setRowHeight 的高度 */
    private Integer height;

    /** setColumnWidth 的宽度 */
    private Integer width;

    // ---- setStyle 样式字段 ----
    private String background;
    private String fontColor;
    private Integer fontSize;
    private String fontWeight;
    private String fontFamily;
    private String hAlign;
    private String vAlign;
    private Boolean wrap;
    private Integer textRotation;

    // ---- sumToCell 求和 ----
    private String sourceRange;
    private String targetRange;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getRange() { return range; }
    public void setRange(String range) { this.range = range; }

    public Object getValue() { return value; }
    public void setValue(Object value) { this.value = value; }

    public List<List<Object>> getValues() { return values; }
    public void setValues(List<List<Object>> values) { this.values = values; }

    public String getClearWhat() { return clearWhat; }
    public void setClearWhat(String clearWhat) { this.clearWhat = clearWhat; }

    public String getFormula() { return formula; }
    public void setFormula(String formula) { this.formula = formula; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Boolean getForce() { return force; }
    public void setForce(Boolean force) { this.force = force; }

    public Integer getRowIndex() { return rowIndex; }
    public void setRowIndex(Integer rowIndex) { this.rowIndex = rowIndex; }

    public Integer getColumnIndex() { return columnIndex; }
    public void setColumnIndex(Integer columnIndex) { this.columnIndex = columnIndex; }

    public Integer getRowPosition() { return rowPosition; }
    public void setRowPosition(Integer rowPosition) { this.rowPosition = rowPosition; }

    public Integer getColumnPosition() { return columnPosition; }
    public void setColumnPosition(Integer columnPosition) { this.columnPosition = columnPosition; }

    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }

    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }

    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }

    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }

    public String getBackground() { return background; }
    public void setBackground(String background) { this.background = background; }

    public String getFontColor() { return fontColor; }
    public void setFontColor(String fontColor) { this.fontColor = fontColor; }

    public Integer getFontSize() { return fontSize; }
    public void setFontSize(Integer fontSize) { this.fontSize = fontSize; }

    public String getFontWeight() { return fontWeight; }
    public void setFontWeight(String fontWeight) { this.fontWeight = fontWeight; }

    public String getFontFamily() { return fontFamily; }
    public void setFontFamily(String fontFamily) { this.fontFamily = fontFamily; }

    public String getHAlign() { return hAlign; }
    public void setHAlign(String hAlign) { this.hAlign = hAlign; }

    public String getVAlign() { return vAlign; }
    public void setVAlign(String vAlign) { this.vAlign = vAlign; }

    public Boolean getWrap() { return wrap; }
    public void setWrap(Boolean wrap) { this.wrap = wrap; }

    public Integer getTextRotation() { return textRotation; }
    public void setTextRotation(Integer textRotation) { this.textRotation = textRotation; }

    public String getSourceRange() { return sourceRange; }
    public void setSourceRange(String sourceRange) { this.sourceRange = sourceRange; }

    public String getTargetRange() { return targetRange; }
    public void setTargetRange(String targetRange) { this.targetRange = targetRange; }
}
