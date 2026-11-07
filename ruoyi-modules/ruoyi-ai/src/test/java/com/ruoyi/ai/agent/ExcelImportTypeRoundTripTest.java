package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import junit.framework.TestCase;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C3 acceptance evidence (backend half of the plan's import-types/decimal-roundtrip gates): for each
 * cell kind a REAL xlsx byte stream is built with POI XSSF (test side only), imported through
 * {@link ExcelImportService#parse}, and the resulting cellData is asserted to match the source type,
 * value and precision. Formula cells assert f is preserved verbatim and that NO recomputation is
 * claimed or performed (SAX reading is not formula evaluation - a deliberately corrupted cached
 * value must survive the import unchanged). Evidence lines are printed to the test log
 * (D:/tmp-mysql/c3-*.log captures of the run).
 */
public class ExcelImportTypeRoundTripTest extends TestCase {
    private JsonNode importOnce(WorkbookWriter writer) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        writer.write(wb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out); wb.close();
        byte[] bytes = out.toByteArray();
        JsonNode parsed = ExcelImportService.parse(bytes, ExcelImportService.Budgets.defaults());
        System.out.println("[c3-roundtrip] bytes=" + bytes.length + " cells=" + parsed.path("stats").path("totalCells").asInt());
        return parsed.path("sheets").path("sheet-0").path("cellData");
    }
    private interface WorkbookWriter { void write(XSSFWorkbook wb); }
    private JsonNode cell(JsonNode cells, int row, int col) { return cells.path(String.valueOf(row)).path(String.valueOf(col)); }

    /** 数字（含高精度小数）：类型与逐位精度一致。 */
    public void testNumberRoundTripKeepsTypeAndEveryDigit() throws Exception {
        final String literal = "0.12345678901234567890123456789";
        JsonNode cells = importOnce(wb -> {
            XSSFRow row = wb.createSheet("T").createRow(0);
            row.createCell(0).setCellValue(123);
            row.createCell(1).setCellValue(-45.75);
            XSSFCell precise = (XSSFCell) row.createCell(2);
            precise.getCTCell().setV(literal);
        });
        assertEquals("整数保持 t=2", 2, cell(cells, 0, 0).path("t").asInt());
        assertEquals(new BigDecimal("123"), cell(cells, 0, 0).path("v").decimalValue());
        assertEquals(2, cell(cells, 0, 1).path("t").asInt());
        assertEquals(new BigDecimal("-45.75"), cell(cells, 0, 1).path("v").decimalValue());
        assertEquals("高精度小数逐位一致", new BigDecimal(literal), cell(cells, 0, 2).path("v").decimalValue());
        assertEquals(2, cell(cells, 0, 2).path("t").asInt());
        System.out.println("[c3-roundtrip] number " + cell(cells, 0, 2).path("v").decimalValue().toPlainString());
    }

    /** 布尔：类型与真值一致。 */
    public void testBooleanRoundTrip() throws Exception {
        JsonNode cells = importOnce(wb -> {
            Row row = wb.createSheet("T").createRow(0);
            row.createCell(0).setCellValue(true);
            row.createCell(1).setCellValue(false);
        });
        assertEquals(3, cell(cells, 0, 0).path("t").asInt());
        assertTrue(cell(cells, 0, 0).path("v").asBoolean());
        assertEquals(3, cell(cells, 0, 1).path("t").asInt());
        assertFalse(cell(cells, 0, 1).path("v").asBoolean());
    }

    /** 日期（1900 与 1904 系统、含时间部分）：序列值与同一时刻一一对应。 */
    public void testDateRoundTripAcrossBothDateSystems() throws Exception {
        final LocalDateTime at = LocalDateTime.of(2025, 6, 30, 23, 59, 59);
        BigDecimal canonical = BigDecimal.valueOf(DateUtil.getExcelDate(at, false));
        JsonNode from1900 = importOnce(wb -> {
            CellStyle style = wb.createCellStyle();
            style.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            Cell c = wb.createSheet("T").createRow(0).createCell(0);
            c.setCellValue(at); c.setCellStyle(style);
        });
        assertEquals(2, cell(from1900, 0, 0).path("t").asInt());
        assertEquals("1900 系统日期往返一致", 0, canonical.compareTo(cell(from1900, 0, 0).path("v").decimalValue()));
        JsonNode from1904 = importOnce(wb -> {
            wb.getCTWorkbook().addNewWorkbookPr().setDate1904(true); // POI 4.1.2 无 XLSX04 常量，直接置 CT 标志
            CellStyle style = wb.createCellStyle();
            style.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            Cell c = wb.createSheet("T").createRow(0).createCell(0);
            c.setCellValue(DateUtil.getExcelDate(at, true)); c.setCellStyle(style); // 按 1904 系统写入序列
        });
        assertEquals("1904 系统导入后与 1900 系统同一时刻同值", 0, canonical.compareTo(cell(from1904, 0, 0).path("v").decimalValue()));
        System.out.println("[c3-roundtrip] date serial " + canonical.toPlainString() + " (1900==1904 after normalization)");
    }

    /** 公式：f 逐字保留、缓存值原样（含被写错的缓存），无重算。 */
    public void testFormulaRoundTripPreservesFormulaWithoutRecomputation() throws Exception {
        JsonNode cells = importOnce(wb -> {
            Row row = wb.createSheet("T").createRow(0);
            row.createCell(0).setCellValue(6);
            row.createCell(1).setCellValue(7);
            XSSFCell formula = (XSSFCell) row.createCell(2);
            formula.setCellFormula("A1*B1");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            formula.getCTCell().setV("13"); // 故意写错缓存：导入必须原样保留 13，而不是重算 42
        });
        JsonNode c = cell(cells, 0, 2);
        assertEquals("=A1*B1", c.path("f").asText());
        assertEquals("缓存值原样保留（不重算）", 0, new BigDecimal("13").compareTo(c.path("v").decimalValue()));
        assertFalse("不得出现重算声明字段", c.has("recalculated") || c.has("computed"));
        System.out.println("[c3-roundtrip] formula f=" + c.path("f").asText() + " cachedV=" + c.path("v").decimalValue() + " (no recalculation)");
    }
}
