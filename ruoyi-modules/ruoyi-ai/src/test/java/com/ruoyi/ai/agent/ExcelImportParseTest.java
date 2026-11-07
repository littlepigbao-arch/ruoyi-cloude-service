package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C3 RED-first tests for the backend XLSX import parse core (POI 4.1.2 SAX event model - no DOM
 * usermodel on the import path). Covers the precise cell-type conversion that replaces the current
 * rough frontend mapping (agentReport/index.vue:1378 boolean/number/date vs everything-else, and the
 * even rougher manualSnapshot t=number?1:2 at index.vue:1571) and every budget refusal kind with a
 * negative that trips BEFORE anything is materialized (parse core takes bytes and returns JSON - the
 * MySQL lane test proves the job residue rules). Type semantics must align with the existing workbook
 * cellData model: t=1 string, t=2 number, t=3 boolean (WriteManifest.java:172, univer-action-executor.js:284);
 * formulas keep the file's cached value verbatim and are never recomputed.
 */
public class ExcelImportParseTest extends TestCase {
    private ExcelImportService.Budgets budgets() { return ExcelImportService.Budgets.defaults(); }

    // ---------- fixture helpers: real XLSX bytes built with POI XSSF (test-side only) ----------
    private byte[] workbookBytes(WorkbookWriter writer) throws Exception {
        XSSFWorkbook wb = new XSSFWorkbook();
        writer.write(wb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out); wb.close();
        return out.toByteArray();
    }
    private interface WorkbookWriter { void write(XSSFWorkbook wb); }

    /** Raw OPC package builder for cases POI's writer cannot express (inline strings, malformed refs, bombs). */
    private byte[] rawXlsx(String sheetRowsXml, String sharedStringsXml, String stylesXml, boolean date1904) throws IOException {
        String ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
        StringBuilder types = new StringBuilder();
        types.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            .append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">")
            .append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>")
            .append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>")
            .append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>")
            .append("<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        if (sharedStringsXml != null) types.append("<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>");
        if (stylesXml != null) types.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        types.append("</Types>");
        String rootRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";
        StringBuilder wbRels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            .append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            .append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>");
        if (sharedStringsXml != null) wbRels.append("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>");
        if (stylesXml != null) wbRels.append("<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        wbRels.append("</Relationships>");
        String workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><workbook " + ns + ">"
            + (date1904 ? "<workbookPr date1904=\"1\"/>" : "")
            + "<sheets><sheet name=\"S1\" sheetId=\"1\" r:id=\"rId1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/></sheets></workbook>";
        String sheet = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><worksheet " + ns + ">"
            + (stylesXml != null ? "<sheetData>" + sheetRowsXml + "</sheetData>" : "<sheetData>" + sheetRowsXml + "</sheetData>") + "</worksheet>";
        String sst = sharedStringsXml == null ? null : "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><sst " + ns + " count=\""
            + countTags(sharedStringsXml, "<si>") + "\" uniqueCount=\"" + countTags(sharedStringsXml, "<si>") + "\">" + sharedStringsXml + "</sst>";
        String styles = stylesXml == null ? null : "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><styleSheet " + ns + ">" + stylesXml + "</styleSheet>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(out);
        put(zip, "[Content_Types].xml", types.toString());
        put(zip, "_rels/.rels", rootRels);
        put(zip, "xl/workbook.xml", workbook);
        put(zip, "xl/_rels/workbook.xml.rels", wbRels.toString());
        put(zip, "xl/worksheets/sheet1.xml", sheet);
        if (sst != null) put(zip, "xl/sharedStrings.xml", sst);
        if (styles != null) put(zip, "xl/styles.xml", styles);
        zip.close();
        return out.toByteArray();
    }
    private static int countTags(String xml, String tag) { int n = 0, i = 0; while ((i = xml.indexOf(tag, i)) >= 0) { n++; i += tag.length(); } return n; }
    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(content.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private String cellText(JsonNode cell, String field) { return cell.path(field).asText(); }
    private JsonNode parseOne(byte[] bytes) { return ExcelImportService.parse(bytes, budgets()); }
    private JsonNode firstSheetCells(JsonNode parsed) { return parsed.path("sheets").path("sheet-0").path("cellData"); }

    // ---------- type conversion semantics ----------
    /** 数字：文件字面量精度原样保留（含 30 位高精度小数与二进制浮点尾差字面量），t=2。 */
    public void testNumberCellsKeepExactFileLiteralPrecision() throws Exception {
        byte[] bytes = workbookBytes(wb -> {
            Sheet sheet = wb.createSheet("N");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(1.5);
            row.createCell(1).setCellValue(-0.001);
            row.createCell(2).setCellValue(1E15);
            XSSFCell precise = (XSSFCell) row.createCell(3);
            precise.getCTCell().setT(org.openxmlformats.schemas.spreadsheetml.x2006.main.STCellType.N);
            precise.getCTCell().setV("1.234567890123456789012345678901");
            XSSFCell tail = (XSSFCell) row.createCell(4);
            tail.getCTCell().setV("0.30000000000000004");
        });
        JsonNode cells = firstSheetCells(parseOne(bytes));
        assertEquals(2, cells.path("0").path("0").path("t").asInt());
        assertEquals(0, new BigDecimal("1.5").compareTo(cells.path("0").path("0").path("v").decimalValue()));
        assertEquals(0, new BigDecimal("-0.001").compareTo(cells.path("0").path("1").path("v").decimalValue()));
        assertEquals(0, new BigDecimal("1.0E15").compareTo(cells.path("0").path("2").path("v").decimalValue()));
        // 30 位小数字面量不得经 double 重排（粗糙映射按 JS number 重排会丢位）
        assertEquals(new BigDecimal("1.234567890123456789012345678901"), cells.path("0").path("3").path("v").decimalValue());
        assertEquals(new BigDecimal("0.30000000000000004"), cells.path("0").path("4").path("v").decimalValue());
    }

    /** 布尔：t=3、v 为 JSON 布尔（现状粗糙映射把布尔归入其他/数字桶）。 */
    public void testBooleanCellsMapToBooleanType() throws Exception {
        byte[] bytes = workbookBytes(wb -> {
            Sheet sheet = wb.createSheet("B");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(true);
            row.createCell(1).setCellValue(false);
        });
        JsonNode cells = firstSheetCells(parseOne(bytes));
        assertEquals(3, cells.path("0").path("0").path("t").asInt());
        assertTrue("TRUE must import as JSON boolean", cells.path("0").path("0").path("v").isBoolean());
        assertTrue(cells.path("0").path("0").path("v").asBoolean());
        assertEquals(3, cells.path("0").path("1").path("t").asInt());
        assertFalse(cells.path("0").path("1").path("v").asBoolean());
    }

    /** 字符串：共享字符串（含复用）与 inlineStr 均 t=1，富文本 run 拼接。 */
    public void testStringCellsFromSharedInlineAndRichText() throws Exception {
        byte[] bytes = rawXlsx(
            "<row r=\"1\">"
            + "<c r=\"A1\" t=\"s\"><v>0</v></c>"
            + "<c r=\"B1\" t=\"s\"><v>0</v></c>"
            + "<c r=\"C1\" t=\"s\"><v>1</v></c>"
            + "<c r=\"D1\" t=\"inlineStr\"><is><t>plain-inline</t></is></c>"
            + "<c r=\"E1\" t=\"inlineStr\"><is><r><t>Ab</t></r><r><t>cd</t></r></is></c>"
            + "</row>",
            "<si><t>shared</t></si><si><r><t>Rich</t></r><r><t>Text</t></r></si>", null, false);
        JsonNode cells = firstSheetCells(parseOne(bytes));
        assertEquals(1, cells.path("0").path("0").path("t").asInt());
        assertEquals("shared", cellText(cells.path("0").path("0"), "v"));
        assertEquals("复用同一共享串必须同值", "shared", cellText(cells.path("0").path("1"), "v"));
        assertEquals("富文本 run 按序拼接", "RichText", cellText(cells.path("0").path("2"), "v"));
        assertEquals("plain-inline", cellText(cells.path("0").path("3"), "v"));
        assertEquals("Abcd", cellText(cells.path("0").path("4"), "v"));
    }

    /** 日期（1900 系统、含时间部分）：数值序列原样保留（t=2、精度不丢），时刻可无损还原。 */
    public void testDateCellsWithTimePartKeepExactSerial() throws Exception {
        final LocalDateTime at = LocalDateTime.of(2026, 10, 14, 13, 45, 30);
        byte[] bytes = workbookBytes(wb -> {
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));
            Sheet sheet = wb.createSheet("D");
            Row row = sheet.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellValue(at);
            cell.setCellStyle(dateStyle);
        });
        JsonNode cell = firstSheetCells(parseOne(bytes)).path("0").path("0");
        assertEquals(2, cell.path("t").asInt());
        double expectedSerial = DateUtil.getExcelDate(at, false);
        assertEquals("日期序列必须等于 1900 系统精确序列", 0, BigDecimal.valueOf(expectedSerial).compareTo(cell.path("v").decimalValue()));
        Date back = DateUtil.getJavaDate(cell.path("v").doubleValue(), false);
        Calendar cal = Calendar.getInstance(); cal.setTime(back);
        assertEquals(2026, cal.get(Calendar.YEAR)); assertEquals(13, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(45, cal.get(Calendar.MINUTE)); assertEquals(30, cal.get(Calendar.SECOND));
    }

    /** 日期（1904 系统）：同一时刻必须换算为 1900 系统序列（+1462 天、时间分数保留）；同表普通数字不换算。 */
    public void testDateCellsFrom1904WorkbookConvertTo1900Serials() throws Exception {
        final LocalDateTime at = LocalDateTime.of(2024, 2, 29, 6, 30, 15);
        byte[] bytes = workbookBytes(wb -> {
            // POI 4.1.2 无 XLSX04 常量：直接置 workbookPr date1904，并按 1904 系统写入序列（真实 1904 文件的存储形态）
            wb.getCTWorkbook().addNewWorkbookPr().setDate1904(true);
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd hh:mm"));
            CellStyle plain = wb.createCellStyle();
            plain.setDataFormat(wb.createDataFormat().getFormat("0.000"));
            Sheet sheet = wb.createSheet("D4");
            Row row = sheet.createRow(0);
            Cell date = row.createCell(0);
            date.setCellValue(DateUtil.getExcelDate(at, true)); date.setCellStyle(dateStyle);
            Cell number = row.createCell(1);
            number.setCellValue(1462.5); number.setCellStyle(plain);
        });
        JsonNode cells = firstSheetCells(parseOne(bytes));
        JsonNode dateCell = cells.path("0").path("0");
        assertEquals(2, dateCell.path("t").asInt());
        double canonical = DateUtil.getExcelDate(at, false);
        assertEquals("1904 序列必须换算为 1900 系统序列（同一时刻）", 0, BigDecimal.valueOf(canonical).compareTo(dateCell.path("v").decimalValue()));
        assertEquals("普通数字单元格不受 1904 换算影响", 0, new BigDecimal("1462.5").compareTo(cells.path("0").path("1").path("v").decimalValue()));
    }

    /** 公式：f 原样保留（= 前缀），v 只取文件缓存值——缓存被人为写错也不重算（SAX 读取 ≠ 重算）。 */
    public void testFormulaCellsPreserveTextAndCachedValueWithoutRecomputation() throws Exception {
        byte[] bytes = workbookBytes(wb -> {
            Sheet sheet = wb.createSheet("F");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(40);
            row.createCell(1).setCellValue(2);
            XSSFCell honest = (XSSFCell) row.createCell(2);
            honest.setCellFormula("A1+B1");
            XSSFCell stale = (XSSFCell) row.createCell(3);
            stale.setCellFormula("A1*B1");
            wb.getCreationHelper().createFormulaEvaluator().evaluateAll();
            // 评估后把缓存值人为改成 999：导入不得重算回 80
            stale.getCTCell().setV("999");
        });
        JsonNode cells = firstSheetCells(parseOne(bytes));
        JsonNode sum = cells.path("0").path("2");
        assertEquals("=A1+B1", cellText(sum, "f"));
        assertEquals("缓存值必须原样读出", 0, new BigDecimal("42").compareTo(sum.path("v").decimalValue()));
        JsonNode bomb = cells.path("0").path("3");
        assertEquals("=A1*B1", cellText(bomb, "f"));
        assertEquals("被写错的缓存必须保持 999（不重算声明）", 0, new BigDecimal("999").compareTo(bomb.path("v").decimalValue()));
        assertFalse("导入结果不得携带任何重算标记", bomb.has("recalculated"));
    }

    /** 无缓存公式：只有 f、没有 v——不得伪造计算结果。 */
    public void testFormulaWithoutCachedValueImportsFormulaOnly() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"1\"><c r=\"A1\"><f>1+1</f></c></row>", null, null, false);
        JsonNode cell = firstSheetCells(parseOne(bytes)).path("0").path("0");
        assertEquals("=1+1", cellText(cell, "f"));
        assertFalse("文件无缓存值时不得出现 v", cell.has("v"));
    }

    /** 共享公式：master 保留 f 文本，slave 只保留缓存值（文件本就不含 slave 文本，不发明公式）。 */
    public void testSharedFormulaMasterKeepsTextSlaveDropsIt() throws Exception {
        byte[] bytes = rawXlsx(
            "<row r=\"1\">"
            + "<c r=\"A1\"><v>10</v></c>"
            + "<c r=\"B1\"><f t=\"shared\" ref=\"B1:B2\" si=\"0\">A1*2</f><v>20</v></c>"
            + "</row><row r=\"2\">"
            + "<c r=\"A2\"><v>11</v></c>"
            + "<c r=\"B2\"><f t=\"shared\" si=\"0\"/><v>22</v></c>"
            + "</row>", null, null, false);
        JsonNode cells = firstSheetCells(parseOne(bytes));
        assertEquals("=A1*2", cellText(cells.path("0").path("1"), "f"));
        assertEquals(0, new BigDecimal("20").compareTo(cells.path("0").path("1").path("v").decimalValue()));
        assertFalse("slave 单元格不得被发明公式", cells.path("1").path("1").has("f"));
        assertEquals(0, new BigDecimal("22").compareTo(cells.path("1").path("1").path("v").decimalValue()));
    }

    /** 错误格：按文档化策略落为错误字面量字符串（t=1）。 */
    public void testErrorCellsImportAsDocumentedErrorStrings() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"1\"><c r=\"A1\" t=\"e\"><v>#DIV/0!</v></c></row>", null, null, false);
        JsonNode cell = firstSheetCells(parseOne(bytes)).path("0").path("0");
        assertEquals(1, cell.path("t").asInt());
        assertEquals("#DIV/0!", cellText(cell, "v"));
    }

    /** 稀疏与仅样式空格：不产生 cellData。 */
    public void testSparseAndStyleOnlyCellsCreateNoCellData() throws Exception {
        byte[] bytes = rawXlsx(
            "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>only</t></is></c></row>"
            + "<row r=\"3\"><c r=\"B3\" s=\"1\"/></row>", null,
            "<fonts count=\"1\"><font/></fonts><fills count=\"1\"><fill/></fills><borders count=\"1\"><border/></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\"/></cellStyleXfs><cellXfs count=\"2\"><xf numFmtId=\"0\"/><xf numFmtId=\"0\"/></cellXfs>", false);
        JsonNode cells = firstSheetCells(parseOne(bytes));
        assertEquals("只有真实有值单元格", 1, cells.path("0").size());
        assertTrue(cells.has("0") && !cells.path("0").has("1"));
        assertFalse("仅样式空格不得生成第 2 行", cells.has("2"));
    }

    /** 表序与表名保持；行列表容量等于实际使用范围（空表为 0）。 */
    public void testSheetOrderAndNamesPreserved() throws Exception {
        byte[] bytes = workbookBytes(wb -> {
            wb.createSheet("First").createRow(4).createCell(1).setCellValue("x");
            wb.createSheet("Zwei");
        });
        JsonNode parsed = parseOne(bytes);
        assertEquals(arr().add("sheet-0").add("sheet-1").toString(), parsed.path("sheetOrder").toString());
        assertEquals("First", parsed.path("sheets").path("sheet-0").path("name").asText());
        assertEquals("Zwei", parsed.path("sheets").path("sheet-1").path("name").asText());
        assertEquals("行表容量来自实际使用范围", 5, parsed.path("sheets").path("sheet-0").path("rowCount").asInt());
        assertEquals("列容量来自实际使用范围", 2, parsed.path("sheets").path("sheet-0").path("columnCount").asInt());
        assertEquals("空表容量为 0", 0, parsed.path("sheets").path("sheet-1").path("rowCount").asInt());
    }

    /** 范围外内容（样式/合并/图表/宏/外链/批注）必须如实登记为丢弃，不得宣称保真。 */
    public void testParseDocumentsDroppedFeatures() throws Exception {
        byte[] bytes = workbookBytes(wb -> { wb.createSheet("S").createRow(0).createCell(0).setCellValue("x"); });
        JsonNode dropped = parseOne(bytes).path("stats").path("dropped");
        for (String feature : Arrays.asList("styles", "merges", "charts", "macros", "externalLinks", "comments", "drawings", "sharedFormulaSlaveFormulas")) {
            boolean listed = false;
            for (JsonNode d : dropped) if (feature.equals(d.asText())) { listed = true; break; }
            assertTrue("超范围特性必须登记丢弃: " + feature, listed);
        }
    }

    // ---------- budget refusals (all negative evidence, before any materialization) ----------
    private void assertBudgetKind(byte[] bytes, ExcelImportService.Budgets budgets, String kind) {
        try {
            ExcelImportService.parse(bytes, budgets);
            fail("必须以 IMPORT_BUDGET_EXCEEDED 拒绝: " + kind);
        } catch (IllegalArgumentException expected) {
            assertTrue("unexpected: " + expected.getMessage(), expected.getMessage().startsWith("IMPORT_BUDGET_EXCEEDED"));
            assertTrue("kind 必须在消息中可追溯 (" + kind + "): " + expected.getMessage(), expected.getMessage().contains(kind));
        }
    }
    public void testSourceByteBudgetRefused() {
        byte[] bytes = new byte[32];
        ExcelImportService.Budgets b = budgets(); b.maxSourceBytes = 16;
        try { ExcelImportService.parse(bytes, b); fail("必须拒绝超限源字节"); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage().startsWith("IMPORT_BUDGET_EXCEEDED")); assertTrue(e.getMessage().contains("SOURCE_BYTES")); }
    }
    public void testExpandedByteBudgetRefused() throws Exception {
        StringBuilder big = new StringBuilder();
        while (big.length() < 20000) big.append("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>0123456789012345678901234567890123456789</t></is></c></row>");
        byte[] bytes = rawXlsx(big.toString(), null, null, false);
        ExcelImportService.Budgets b = budgets(); b.maxExpandedBytes = 4096; b.maxCompressionRatio = 1000000;
        assertBudgetKind(bytes, b, "EXPANDED_BYTES");
    }
    public void testCompressionRatioRefused() throws Exception {
        StringBuilder big = new StringBuilder();
        while (big.length() < 200000) big.append("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa</t></is></c></row>");
        byte[] bytes = rawXlsx(big.toString(), null, null, false);
        ExcelImportService.Budgets b = budgets(); b.maxCompressionRatio = 2; b.maxExpandedBytes = Long.MAX_VALUE;
        assertBudgetKind(bytes, b, "COMPRESSION_RATIO");
    }
    public void testRowBudgetRefusedAtConfiguredLimit() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"6\"><c r=\"A6\" t=\"inlineStr\"><is><t>x</t></is></c></row>", null, null, false);
        ExcelImportService.Budgets b = budgets(); b.maxRows = 5;
        assertBudgetKind(bytes, b, "ROWS");
    }
    public void testNativeExcelRowBoundRefusedAtDefaults() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"1048577\"><c r=\"A1048577\" t=\"inlineStr\"><is><t>x</t></is></c></row>", null, null, false);
        assertBudgetKind(bytes, budgets(), "ROWS");
    }
    public void testColumnBudgetRefused() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"1\"><c r=\"XFD1\" t=\"inlineStr\"><is><t>a</t></is></c><c r=\"XFE1\" t=\"inlineStr\"><is><t>b</t></is></c></row>", null, null, false);
        ExcelImportService.Budgets b = budgets();
        try { ExcelImportService.parse(bytes, b); fail("超 Excel 列界必须拒绝"); }
        catch (IllegalArgumentException e) { assertTrue(e.getMessage().startsWith("IMPORT_BUDGET_EXCEEDED")); assertTrue(e.getMessage().contains("COLUMNS")); }
    }
    public void testColumnBudgetRefusedAtConfiguredLimit() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"1\"><c r=\"D1\" t=\"inlineStr\"><is><t>x</t></is></c></row>", null, null, false);
        ExcelImportService.Budgets b = budgets(); b.maxColumns = 3;
        assertBudgetKind(bytes, b, "COLUMNS");
    }
    public void testSharedStringEntryBudgetRefused() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\" t=\"s\"><v>1</v></c></row>",
            "<si><t>a</t></si><si><t>b</t></si>", null, false);
        ExcelImportService.Budgets b = budgets(); b.maxSharedStrings = 1;
        assertBudgetKind(bytes, b, "SHARED_STRINGS");
    }
    public void testSharedStringCharBudgetRefused() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>", "<si><t>abcdef</t></si>", null, false);
        ExcelImportService.Budgets b = budgets(); b.maxSharedStringChars = 5;
        assertBudgetKind(bytes, b, "SHARED_STRING_CHARS");
    }
    public void testCellTextBudgetRefused() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>abcd</t></is></c></row>", null, null, false);
        ExcelImportService.Budgets b = budgets(); b.maxCellTextChars = 3;
        assertBudgetKind(bytes, b, "CELL_TEXT");
    }
    public void testTotalCellBudgetRefused() throws Exception {
        byte[] bytes = rawXlsx("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>a</t></is></c><c r=\"B1\" t=\"inlineStr\"><is><t>b</t></is></c><c r=\"C1\" t=\"inlineStr\"><is><t>c</t></is></c></row>", null, null, false);
        ExcelImportService.Budgets b = budgets(); b.maxTotalCells = 2;
        assertBudgetKind(bytes, b, "TOTAL_CELLS");
    }
    public void testNonZipSourceRefused() {
        byte[] notZip = "not a zip at all".getBytes(StandardCharsets.UTF_8);
        try { ExcelImportService.parse(notZip, budgets()); fail("非 XLSX 必须拒绝"); }
        catch (IllegalArgumentException e) { assertTrue("unexpected: " + e.getMessage(), e.getMessage().startsWith("IMPORT_INVALID")); }
    }
    public void testEmptySourceRefused() {
        try { ExcelImportService.parse(new byte[0], budgets()); fail("空源必须拒绝"); }
        catch (IllegalArgumentException e) { assertTrue("unexpected: " + e.getMessage(), e.getMessage().startsWith("IMPORT_")); }
    }

    // ---------- C3-review F1/F3/F4 regressions (audit pass, workbook-wide budget, style 0) ----------
    /** F1：包元数据（[Content_Types].xml 内 2MB 注释填充）也必须计入展开预算——审核探针原场景：
     *  compressed≈3.6KB、元数据展开 2MB、预算展开上限 2KB → 结构化 EXPANDED_BYTES 拒绝；
     *  且解析不得修改 JVM 全局 ZipSecureFile 压缩比保护（禁止按请求改全局安全设置）。 */
    public void testZipMetadataExpansionIsAuditedAndGlobalGuardUntouched() throws Exception {
        double ratioBefore = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
        byte[] bomb = metadataBomb(rawXlsx("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>", null, null, false));
        ExcelImportService.Budgets b = budgets(); b.maxExpandedBytes = 2048; b.maxCompressionRatio = 2;
        try { ExcelImportService.parse(bomb, b); fail("元数据展开绕过预算必须拒绝"); }
        catch (IllegalArgumentException e) { assertBudgetKind(bomb, b, "EXPANDED_BYTES"); }
        ExcelImportService.Budgets defaults = ExcelImportService.Budgets.defaults();
        try { ExcelImportService.parse(bomb, defaults); fail("默认压缩比下元数据炸弹必须拒绝"); }
        catch (IllegalArgumentException e) { assertBudgetKind(bomb, defaults, "COMPRESSION_RATIO"); }
        assertEquals("解析不得修改全局压缩比保护", ratioBefore, org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio(), 0.0);
    }
    private byte[] metadataBomb(byte[] input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream dst = new ZipOutputStream(out);
        try (ZipInputStream src = new ZipInputStream(new ByteArrayInputStream(input))) {
            ZipEntry e; byte[] buf = new byte[4096];
            while ((e = src.getNextEntry()) != null) {
                ByteArrayOutputStream item = new ByteArrayOutputStream(); int n;
                while ((n = src.read(buf)) != -1) item.write(buf, 0, n);
                dst.putNextEntry(new ZipEntry(e.getName())); dst.write(item.toByteArray());
                if (e.getName().equals("[Content_Types].xml")) {
                    dst.write("<!--".getBytes(StandardCharsets.UTF_8));
                    byte[] padding = new byte[2000000]; Arrays.fill(padding, (byte) 'x');
                    dst.write(padding); dst.write("-->".getBytes(StandardCharsets.UTF_8));
                }
                dst.closeEntry();
            }
        }
        dst.close(); return out.toByteArray();
    }
    /** F3：TOTAL_CELLS 是工作簿级预算——两张表各 1 格、上限 1 → 结构化拒绝（审核探针原场景：旧实现 total=2 放行）。 */
    public void testTotalCellsBudgetIsWorkbookWideNotPerSheet() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("A").createRow(0).createCell(0).setCellValue(1);
            wb.createSheet("B").createRow(0).createCell(0).setCellValue(2);
            wb.write(bytes);
        }
        ExcelImportService.Budgets b = budgets(); b.maxTotalCells = 1;
        try { ExcelImportService.parse(bytes.toByteArray(), b); fail("跨表总量超限必须拒绝"); }
        catch (IllegalArgumentException e) { assertBudgetKind(bytes.toByteArray(), b, "TOTAL_CELLS"); }
    }
    /** F4：样式索引 0 的 xf 是真实条目——1904 系统、numFmtId=14（日期）挂在索引 0 时序列 1 必须换算为 1463
     *  （审核探针原场景：旧实现排除索引 0，actual=1）。 */
    public void testStyleZeroDateIn1904WorkbookConvertsTo1900Serial() throws Exception {
        String styles = "<cellXfs count=\"1\"><xf numFmtId=\"14\"/></cellXfs>";
        JsonNode date = ExcelImportService.parse(rawXlsx("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>", null, styles, true), budgets());
        assertEquals("索引 0 的日期格式必须换算", 0, new BigDecimal("1463").compareTo(date.path("sheets").path("sheet-0").path("cellData").path("0").path("0").path("v").decimalValue()));
    }

    // ---------- C3 re-review F1-a/F1-b regressions (audit view parity, per-entry boundary) ----------
    /** F1-a：前缀字节（MZ 头 + 中央目录偏移平移）的 ZIP 是 POI 可解析的，审计必须同视图覆盖——
     *  审核探针原场景：200KB 元数据经前缀字节绕过 2KB 展开预算被接受 → 现在结构化 EXPANDED_BYTES 拒绝。 */
    public void testPrefixBytesZipIsAuditedWithSameEntryViewAsPoi() throws Exception {
        byte[] bomb = metadataBomb(rawXlsx("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>", null, null, false));
        byte[] prefixed = prefixed(bomb);
        ExcelImportService.Budgets b = budgets(); b.maxExpandedBytes = 2048;
        try { ExcelImportService.parse(prefixed, b); fail("前缀字节 ZIP 的元数据展开必须计入预算"); }
        catch (IllegalArgumentException e) { assertBudgetKind(prefixed, b, "EXPANDED_BYTES"); }
    }
    /** 复审探针同款前缀字节构造：MZ 头 +32 字节偏移、中央目录各条目局部头偏移同步平移。 */
    private byte[] prefixed(byte[] zip) {
        byte[] z = zip.clone(); int end = z.length - 22, cd = u32(z, end + 16), n = u16(z, end + 10), p = cd, extra = 32;
        for (int i = 0; i < n; i++) {
            if (u32(z, p) != 0x02014b50) throw new AssertionError("central dir");
            put32(z, p + 42, u32(z, p + 42) + extra); p += 46 + u16(z, p + 28) + u16(z, p + 30) + u16(z, p + 32);
        }
        put32(z, end + 16, cd + extra);
        byte[] out = new byte[z.length + extra]; out[0] = 'M'; out[1] = 'Z'; System.arraycopy(z, 0, out, extra, z.length);
        return out;
    }
    private static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p + 1] & 255) << 8); }
    private static int u32(byte[] b, int p) { return u16(b, p) | (u16(b, p + 2) << 16); }
    private static void put32(byte[] b, int p, int n) { for (int i = 0; i < 4; i++) b[p + i] = (byte) (n >>> (8 * i)); }
    /** F1-b：整包压缩比不等价于单条目阈值——高压缩工作表 + 低压缩填充条目（整包审计通过、旧实现放行到
     *  POI 才报无结构化 Zip bomb）→ 审计按 POI 单条目边界先拒，结构化 COMPRESSION_RATIO。 */
    public void testPerEntryCompressionBoundaryRefusesWithStructuredFacts() throws Exception {
        byte[] bomb = paddedEntryBomb(rawXlsx("<row r=\"1\"><c r=\"A1\"><v>1</v></c></row>", null, null, false));
        ExcelImportService.Budgets b = ExcelImportService.Budgets.defaults();
        try { ExcelImportService.parse(bomb, b); fail("单条目超压缩边界必须拒绝"); }
        catch (IllegalArgumentException e) { assertBudgetKind(bomb, b, "COMPRESSION_RATIO"); }
    }

    // ---------- C3-review-04 (P2): anchored zip-bomb recognition + same-caliber observed/observedBytes ----------
    /** 缺口1（锚定识别）：包元数据探针原场景——外层 InvalidFormatException("Can't read content types part!")
     *  包装内层 InvalidFormatException("Zip bomb detected! ...带实时尺寸")，InvalidFormatException 非
     *  IOException/InvalidOperationException，旧类型并集漏识别 → IMPORT_INVALID、kind 空。锚定识别只要求
     *  cause 链任一层消息含 "zip bomb detected"，必须结构化 COMPRESSION_RATIO 且 observed/observedBytes
     *  同口径双值（下取整比例 + 展开字节证据）。 */
    public void testAnchoredRecognitionCoversInvalidFormatWrapperChain() {
        String poi = "Zip bomb detected! The file would exceed the max. ratio of compressed file size to the size of the expanded data.\n"
            + "Uncompressed size: 106435, Raw/compressed size: 1024, ratio: 0.009621\n"
            + "Limits: MIN_INFLATE_RATIO: 0.010000, Entry: [Content_Types].xml";
        org.apache.poi.openxml4j.exceptions.InvalidFormatException inner =
            new org.apache.poi.openxml4j.exceptions.InvalidFormatException(poi);
        org.apache.poi.openxml4j.exceptions.InvalidFormatException outer =
            new org.apache.poi.openxml4j.exceptions.InvalidFormatException("Can't read content types part!", inner);
        ExcelImportService.Counters counters = new ExcelImportService.Counters(budgets(), 2048);
        ExcelImportService.BudgetRefusal refusal = ExcelImportService.poiZipBombRefusal(outer, counters);
        assertNotNull("InvalidFormatException 包装链必须被锚定识别为结构化拒绝", refusal);
        assertEquals("COMPRESSION_RATIO", refusal.kind);
        assertEquals("100", refusal.limit);
        assertEquals("106435/1024 下取整", "103", refusal.observed);
        assertEquals("字节证据=消息内展开尺寸", Long.valueOf(106435L), refusal.observedBytes);
    }

    /** 缺口2（同口径）：消息可解析尺寸时 observed=整数除法下取整、observedBytes=uncompressed——
     *  比例与字节证据分家，不再用字节数冒充比例。 */
    public void testParseableSizesGiveFloorRatioAndUncompressedBytes() {
        String sizes = "Zip bomb detected! Uncompressed size: 1000, Raw/compressed size: 3, ratio: 0.003000";
        ExcelImportService.Counters counters = new ExcelImportService.Counters(budgets(), 4096);
        ExcelImportService.BudgetRefusal refusal = ExcelImportService.poiZipBombRefusal(new Exception(sizes), counters);
        assertNotNull(refusal);
        assertEquals("1000/3 下取整", "333", refusal.observed);
        assertEquals(Long.valueOf(1000L), refusal.observedBytes);
        assertTrue("有尺寸时消息仍写数值比例: " + refusal.getMessage(), refusal.getMessage().contains("观察值 333"));
        ExcelImportService.ZipBombObserved direct = ExcelImportService.poiZipBombObserved(sizes, counters);
        assertEquals(333L, direct.observed);
        assertEquals(Long.valueOf(1000L), direct.observedBytes);
    }

    /** 缺口2（fallback 口径）：无尺寸但措辞为比例拒绝（含 "ratio" 字样，C3-REVIEW-05 五审场景）→
     *  kind 必须仍定 COMPRESSION_RATIO/limit=100（先依据阈值措辞定种类，再提取观测值）、
     *  observed=UNKNOWN_RATIO 哨兵 -1（显式"观测值未知"）、observedBytes=counters.expanded 字节证据、
     *  消息写 "观察值 未知（展开 N 字节）"——红：旧判别 ", ratio:" 缺失误入 ENTRY_SIZE/4294967295。 */
    public void testRatioFallbackUsesUnknownSentinelAndByteEvidence() {
        String noSizes = "Zip bomb detected! The file would exceed the max ratio";
        ExcelImportService.Counters counters = new ExcelImportService.Counters(budgets(), 4096);
        counters.expanded = 123456;
        ExcelImportService.BudgetRefusal refusal = ExcelImportService.poiZipBombRefusal(new java.io.IOException(noSizes), counters);
        assertNotNull(refusal);
        assertEquals("五审场景：比例措辞（含 ratio 字样）无尺寸仍必须分类为 COMPRESSION_RATIO", "COMPRESSION_RATIO", refusal.kind);
        assertEquals("比例拒绝 limit 必须为 POI 单条目比例边界 100", "100", refusal.limit);
        assertEquals("观测值未知必须显式哨兵 -1", -1L, ExcelImportService.UNKNOWN_RATIO);
        assertEquals("-1", refusal.observed);
        assertEquals("字节证据必须取自 counters.expanded", Long.valueOf(123456L), refusal.observedBytes);
        assertTrue("消息必须写未知+展开字节证据: " + refusal.getMessage(), refusal.getMessage().contains("观察值 未知（展开 123456 字节"));
        ExcelImportService.ZipBombObserved direct = ExcelImportService.poiZipBombObserved(noSizes, counters);
        assertEquals(-1L, direct.observed);
        assertEquals(Long.valueOf(123456L), direct.observedBytes);
    }

    /** 锚定识别：大小写不敏感（"zip bomb detected" 为 POI/commons-compress 特有短语，四审已确认特异性）。 */
    public void testAnchorIsCaseInsensitive() {
        String upper = "ZIP BOMB DETECTED! Uncompressed size: 5000, Raw/compressed size: 10, ratio: 0.002000";
        ExcelImportService.Counters counters = new ExcelImportService.Counters(budgets(), 4096);
        ExcelImportService.BudgetRefusal refusal = ExcelImportService.poiZipBombRefusal(new RuntimeException(upper), counters);
        assertNotNull(refusal);
        assertEquals("比例变体须按大小写不敏感锚定识别", "COMPRESSION_RATIO", refusal.kind);
        assertEquals("500", refusal.observed);
        assertEquals(Long.valueOf(5000L), refusal.observedBytes);
    }

    /** 锚定识别特异性：无 zip-bomb 证据的异常链必须返回 null（不得把普通解析失败误伤为预算拒绝）。 */
    public void testAnchorIgnoresUnrelatedFailures() {
        ExcelImportService.Counters counters = new ExcelImportService.Counters(budgets(), 4096);
        assertNull(ExcelImportService.poiZipBombRefusal(new java.io.IOException("disk error"), counters));
        assertNull(ExcelImportService.poiZipBombRefusal(new IllegalArgumentException("IMPORT_INVALID: 非法行号"), counters));
        assertNull(ExcelImportService.poiZipBombRefusal(new Exception(new RuntimeException("nested but unrelated")), counters));
        assertNull(ExcelImportService.poiZipBombRefusal(null, counters));
    }

    /** 约束1（按触发阈值分类）：POI 4.1.2 两种消息变体共用 "Zip bomb detected" 前缀——比例变体含
     *  ", ratio:"（MIN_INFLATE_RATIO 阈值，字节码字符串常量实证），单条目大小变体不含（MAX_ENTRY_SIZE
     *  阈值）。分类必须互不错记：比例变体 → kind=COMPRESSION_RATIO、limit=100(1/0.01)；大小变体 →
     *  kind=ENTRY_SIZE、limit=ZipSecureFile.getMaxEntrySize()（javap 实证公开可读）、observed=消息内
     *  展开消费字节、observedBytes=null（同口径，无需字节证据副本）。 */
    public void testThresholdClassificationDistinguishesRatioAndEntrySize() {
        String ratio = "Zip bomb detected! The file would exceed the max. ratio of compressed file size to the size of the expanded data.\n"
            + "Uncompressed size: 106435, Raw/compressed size: 1024, ratio: 0.009621\n"
            + "Limits: MIN_INFLATE_RATIO: 0.010000, Entry: [Content_Types].xml";
        String entrySize = "Zip bomb detected! The file would exceed the max size of the expanded data in the zip-file.\n"
            + "This may indicates that the file is used to inflate memory usage and thus could pose a security risk.\n"
            + "Uncompressed size: 65597, Raw/compressed size: 41472\n"
            + "Limits: MAX_ENTRY_SIZE: 65536, Entry: [Content_Types].xml";
        ExcelImportService.Counters counters = new ExcelImportService.Counters(budgets(), 8192);
        ExcelImportService.BudgetRefusal ratioRefusal = ExcelImportService.poiZipBombRefusal(new java.io.IOException(ratio), counters);
        assertNotNull(ratioRefusal);
        assertEquals("比例变体必须分类为 COMPRESSION_RATIO", "COMPRESSION_RATIO", ratioRefusal.kind);
        assertEquals("100", ratioRefusal.limit);
        assertEquals("103", ratioRefusal.observed);
        assertEquals(Long.valueOf(106435L), ratioRefusal.observedBytes);
        ExcelImportService.BudgetRefusal sizeRefusal = ExcelImportService.poiZipBombRefusal(new java.io.IOException(entrySize), counters);
        assertNotNull(sizeRefusal);
        assertEquals("大小变体必须分类为 ENTRY_SIZE", "ENTRY_SIZE", sizeRefusal.kind);
        assertEquals("limit 取 POI 公开可读 getMaxEntrySize()",
            String.valueOf(org.apache.poi.openxml4j.util.ZipSecureFile.getMaxEntrySize()), sizeRefusal.limit);
        assertEquals("observed 与 limit 同口径（展开字节）", "65597", sizeRefusal.observed);
        assertNull("同口径时无需第四字节证据", sizeRefusal.observedBytes);
        // C3-REVIEW-05：大小措辞（"max size ... expanded data"，无 "ratio" 字样）但尺寸缺失的合成
        // 变体 → 仍必须分类为 ENTRY_SIZE（种类由措辞证据决定，观测值退化为哨兵 + counters 字节证据）。
        String sizeNoSizes = "Zip bomb detected! The file would exceed the max size of the expanded data in the zip-file.";
        ExcelImportService.Counters fallbackCounters = new ExcelImportService.Counters(budgets(), 8192);
        fallbackCounters.expanded = 777;
        ExcelImportService.BudgetRefusal sizeFallback = ExcelImportService.poiZipBombRefusal(new java.io.IOException(sizeNoSizes), fallbackCounters);
        assertNotNull(sizeFallback);
        assertEquals("大小措辞无比例字样必须分类为 ENTRY_SIZE", "ENTRY_SIZE", sizeFallback.kind);
        assertEquals(String.valueOf(org.apache.poi.openxml4j.util.ZipSecureFile.getMaxEntrySize()), sizeFallback.limit);
        assertEquals("-1", sizeFallback.observed);
        assertEquals(Long.valueOf(777L), sizeFallback.observedBytes);
    }
    /** C3-REVIEW-06 改名对照回归（六审缺陷实证）：仅更换 ZIP 内部条目名即改变同一单条目大小
     *  超限的落库分类——探针 r5-classification-probe.log 两份合成 XLSX 均于 POI 单条目上限
     *  65536 字节、展开 66242 字节处被拒，sheet1.xml 落 ENTRY_SIZE/65536/66242，而条目名
     *  "calibration.xml" 含 "ratio" 子串被旧判别 contains("ratio")（搜索整条消息）误判为
     *  COMPRESSION_RATIO/100/observed=1。修复硬约束：分类前剥离 "Entry: <条目名>" 段（边界
     *  以 POI 4.1.2 javap 消息常量为准："\\nLimits: MAX_ENTRY_SIZE: %d, Entry: %s"），剥离后
     *  消息才用于一切词匹配；真实条目名含任意阈值字样不得影响分类。两份消息其余部分逐字一致。 */
    public void testEntryNameDoesNotAffectThresholdClassification() {
        String body = "Zip bomb detected! The file would exceed the max size of the expanded data in the zip-file.\n"
            + "This may indicates that the file is used to inflate memory usage and thus could pose a security risk.\n"
            + "You can adjust this limit via ZipSecureFile.setMaxEntrySize() if you need to work with files which are very large.\n"
            + "Uncompressed size: 66242, Raw/compressed size: 41472\n"
            + "Limits: MAX_ENTRY_SIZE: 65536, Entry: ";
        ExcelImportService.Counters counters = new ExcelImportService.Counters(budgets(), 8192);
        String expectedLimit = String.valueOf(org.apache.poi.openxml4j.util.ZipSecureFile.getMaxEntrySize());
        for (String entryName : new String[] { "sheet1.xml", "calibration.xml" }) {
            ExcelImportService.BudgetRefusal refusal =
                ExcelImportService.poiZipBombRefusal(new java.io.IOException(body + entryName), counters);
            assertNotNull("条目名 " + entryName + " 的拒绝必须被结构化识别", refusal);
            assertEquals("条目名 " + entryName + " 含阈值字样不得改变大小超限分类", "ENTRY_SIZE", refusal.kind);
            assertEquals("limit 必须取 POI 公开 getMaxEntrySize()", expectedLimit, refusal.limit);
            assertEquals("observed 必须取消息内展开字节（两例一致）", "66242", refusal.observed);
            assertNull("同口径无需第四字节证据", refusal.observedBytes);
        }
        // 互反守卫：比例拒绝的条目名含 "size" 字样（resize.xml）同样不得翻转为 ENTRY_SIZE。
        String ratioBody = "Zip bomb detected! The file would exceed the max. ratio of compressed file size to the size of the expanded data.\n"
            + "This may indicate that the file is used to inflate memory usage and thus could pose a security risk.\n"
            + "You can adjust this limit via ZipSecureFile.setMinInflateRatio() if you need to work with files which exceed this limit.\n"
            + "Uncompressed size: 106435, Raw/compressed size: 1024, ratio: 0.009621\n"
            + "Limits: MIN_INFLATE_RATIO: 0.010000, Entry: resize.xml";
        ExcelImportService.BudgetRefusal ratioRefusal =
            ExcelImportService.poiZipBombRefusal(new java.io.IOException(ratioBody), counters);
        assertNotNull(ratioRefusal);
        assertEquals("条目名 resize.xml 含 size 字样不得改变比例拒绝分类", "COMPRESSION_RATIO", ratioRefusal.kind);
        assertEquals("100", ratioRefusal.limit);
        assertEquals("103", ratioRefusal.observed);
        assertEquals(Long.valueOf(106435L), ratioRefusal.observedBytes);
    }

    /** 复审探针同款组合：高压缩工作表条目（500KB 注释填充）+ 低压缩随机填充条目（整包摊薄）。 */
    private byte[] paddedEntryBomb(byte[] input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream dst = new ZipOutputStream(out);
        try (ZipInputStream src = new ZipInputStream(new ByteArrayInputStream(input))) {
            ZipEntry e; byte[] buf = new byte[4096];
            while ((e = src.getNextEntry()) != null) {
                dst.putNextEntry(new ZipEntry(e.getName())); int n;
                while ((n = src.read(buf)) != -1) dst.write(buf, 0, n);
                if (e.getName().equals("xl/worksheets/sheet1.xml")) {
                    dst.write("<!--".getBytes(StandardCharsets.UTF_8));
                    byte[] pad = new byte[500000]; Arrays.fill(pad, (byte) 'x');
                    dst.write(pad); dst.write("-->".getBytes(StandardCharsets.UTF_8));
                }
                dst.closeEntry();
            }
        }
        dst.putNextEntry(new ZipEntry("padding.xml")); dst.write("<padding>".getBytes(StandardCharsets.UTF_8));
        Random r = new Random(5); byte[] noise = new byte[200000];
        for (int i = 0; i < noise.length; i++) noise[i] = (byte) ('a' + r.nextInt(26));
        dst.write(noise); dst.write("</padding>".getBytes(StandardCharsets.UTF_8)); dst.closeEntry();
        dst.close(); return out.toByteArray();
    }
}
