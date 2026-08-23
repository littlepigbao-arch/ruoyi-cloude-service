package com.ruoyi.ai.domain.dto;

import java.util.List;

/**
 * 当前活动 sheet 的数据摘要（前端已做 token 节流，最多 50 行 × 26 列）
 *
 * 关键：行列号全是 0-based（第 1 行 = row 0，A 列 = column 0）
 *
 * @author ruoyi
 */
public class SheetContext
{
    private String workbookName;
    private String sheetName;
    private String sheetId;
    private Integer rowCount;
    private Integer columnCount;

    /** 实际有数据的范围（0-based，含端点） */
    private Range usedRange;

    /** 二维数组 [行][列]，0-based 对齐 usedRange */
    private List<List<Object>> values;

    /** 仅当表格含公式时才出现此字段 */
    private List<List<Object>> formulas;

    /** 合并单元格列表 */
    private List<Merge> merges;

    /** 当前选区，可能为 null */
    private Range selection;

    /** 当前激活单元格 */
    private ActiveCell activeCell;

    /** true 表示表格被截断（超 50 行或 26 列），数据不全 */
    private Boolean truncated;

    public String getWorkbookName() { return workbookName; }
    public void setWorkbookName(String workbookName) { this.workbookName = workbookName; }

    public String getSheetName() { return sheetName; }
    public void setSheetName(String sheetName) { this.sheetName = sheetName; }

    public String getSheetId() { return sheetId; }
    public void setSheetId(String sheetId) { this.sheetId = sheetId; }

    public Integer getRowCount() { return rowCount; }
    public void setRowCount(Integer rowCount) { this.rowCount = rowCount; }

    public Integer getColumnCount() { return columnCount; }
    public void setColumnCount(Integer columnCount) { this.columnCount = columnCount; }

    public Range getUsedRange() { return usedRange; }
    public void setUsedRange(Range usedRange) { this.usedRange = usedRange; }

    public List<List<Object>> getValues() { return values; }
    public void setValues(List<List<Object>> values) { this.values = values; }

    public List<List<Object>> getFormulas() { return formulas; }
    public void setFormulas(List<List<Object>> formulas) { this.formulas = formulas; }

    public List<Merge> getMerges() { return merges; }
    public void setMerges(List<Merge> merges) { this.merges = merges; }

    public Range getSelection() { return selection; }
    public void setSelection(Range selection) { this.selection = selection; }

    public ActiveCell getActiveCell() { return activeCell; }
    public void setActiveCell(ActiveCell activeCell) { this.activeCell = activeCell; }

    public Boolean getTruncated() { return truncated; }
    public void setTruncated(Boolean truncated) { this.truncated = truncated; }

    /** 范围对象（usedRange / selection 通用） */
    public static class Range
    {
        private Integer startRow;
        private Integer endRow;
        private Integer startColumn;
        private Integer endColumn;
        /** A1 字符串，如 "B2:B5"（selection 时使用） */
        private String a1;

        public Integer getStartRow() { return startRow; }
        public void setStartRow(Integer startRow) { this.startRow = startRow; }

        public Integer getEndRow() { return endRow; }
        public void setEndRow(Integer endRow) { this.endRow = endRow; }

        public Integer getStartColumn() { return startColumn; }
        public void setStartColumn(Integer startColumn) { this.startColumn = startColumn; }

        public Integer getEndColumn() { return endColumn; }
        public void setEndColumn(Integer endColumn) { this.endColumn = endColumn; }

        public String getA1() { return a1; }
        public void setA1(String a1) { this.a1 = a1; }
    }

    /** 合并单元格 */
    public static class Merge
    {
        private Integer startRow;
        private Integer endRow;
        private Integer startColumn;
        private Integer endColumn;
        private String a1;

        public Integer getStartRow() { return startRow; }
        public void setStartRow(Integer startRow) { this.startRow = startRow; }

        public Integer getEndRow() { return endRow; }
        public void setEndRow(Integer endRow) { this.endRow = endRow; }

        public Integer getStartColumn() { return startColumn; }
        public void setStartColumn(Integer startColumn) { this.startColumn = startColumn; }

        public Integer getEndColumn() { return endColumn; }
        public void setEndColumn(Integer endColumn) { this.endColumn = endColumn; }

        public String getA1() { return a1; }
        public void setA1(String a1) { this.a1 = a1; }
    }

    /** 当前激活单元格 */
    public static class ActiveCell
    {
        private Integer row;
        private Integer column;
        private String a1;

        public Integer getRow() { return row; }
        public void setRow(Integer row) { this.row = row; }

        public Integer getColumn() { return column; }
        public void setColumn(Integer column) { this.column = column; }

        public String getA1() { return a1; }
        public void setA1(String a1) { this.a1 = a1; }
    }
}
