package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import junit.framework.TestCase;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C3 job-lane regressions against real isolated MySQL: state machine (PARSING -> MATERIALIZING ->
 * SUCCEEDED / FAILED with traceable refusal facts), content-hash authorization (follow-up data reads
 * must present the sha256 of the uploaded bytes - no file path exists on any surface), bounded preview
 * (metadata + limited rows, never the full source) and zero residue on refusal (no artifact rows for a
 * FAILED job - budgets are enforced before materialization). setUp mirrors
 * {@link ArtifactRegistrationIdentityMysqlTest} (re-plays the v2 sql tolerating duplicates).
 */
public class ExcelImportJobMysqlTest extends TestCase {
    /** Per-run unique requestId prefix: the isolated DB persists across suite runs, and create() is
     *  idempotent on request_id - fixed literals would collide with rows left by earlier runs (C2
     *  tests avoid this with UUIDs; the idempotency semantics are still exercised within one run). */
    private static final String RUN = "c3j-" + Long.toHexString(System.nanoTime()) + "-";
    private A1TestFixture fixture; private ExcelImportService svc;
    @Override protected void setUp() throws Exception {
        if(!Boolean.getBoolean("a1.schema.prepared"))throw new IllegalStateException("Prepared isolated C3 schema required");
        fixture=new A1TestFixture();String module=System.getProperty("a1.module");
        String sql=new String(Files.readAllBytes(Paths.get(module,"sql","ai_workbook_import_job_v2.sql")),StandardCharsets.UTF_8);
        for(String statement:sql.replaceAll("(?m)^--[^\\r\\n]*","").trim().split(";")){
            String trimmed=statement.trim();if(trimmed.isEmpty())continue;
            try{fixture.db.execute(trimmed);}
            catch(org.springframework.dao.DataAccessException alreadyMigrated){if(!String.valueOf(alreadyMigrated.getMessage()).contains("Duplicate"))throw alreadyMigrated;}
        }
        svc=new ExcelImportService(fixture.db,fixture.tx.getTransactionManager());
        A1TestFixture.inject(svc,"enabled",true);
    }
    @Override protected void tearDown(){fixture.close();}

    // ---------- fixtures ----------
    private byte[] smallWorkbook() throws Exception {
        XSSFWorkbook wb=new XSSFWorkbook();Sheet s=wb.createSheet("Data");
        Row r0=s.createRow(0);r0.createCell(0).setCellValue("Name");r0.createCell(1).setCellValue("Qty");
        Row r1=s.createRow(1);r1.createCell(0).setCellValue("apple");r1.createCell(1).setCellValue(3);
        Row r2=s.createRow(2);r2.createCell(0).setCellValue(true);r2.createCell(1).setCellValue(false);
        ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);wb.close();return out.toByteArray();
    }
    private byte[] wideWorkbook(int rows) throws Exception {
        XSSFWorkbook wb=new XSSFWorkbook();Sheet s=wb.createSheet("Wide");
        for(int i=0;i<rows;i++){Row r=s.createRow(i);r.createCell(0).setCellValue("row-"+i);r.createCell(1).setCellValue(i);}
        ByteArrayOutputStream out=new ByteArrayOutputStream();wb.write(out);wb.close();return out.toByteArray();
    }
    /** 小压缩、大展开：默认压缩比阈值下必须被拒绝的 zip bomb 形态。 */
    private byte[] compressionBomb() throws Exception {
        StringBuilder body=new StringBuilder();
        while(body.length()<3000000)body.append("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>0123456789012345678901234567890123456789</t></is></c></row>");
        String sheet="<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"
            +body+"</sheetData></worksheet>";
        String types="<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            +"<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            +"<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            +"<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            +"<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>";
        String rootRels="<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            +"<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";
        String wbRels="<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            +"<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>";
        String workbook="<?xml version=\"1.0\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            +"<sheets><sheet name=\"S1\" sheetId=\"1\" r:id=\"rId1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/></sheets></workbook>";
        ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry("[Content_Types].xml"));zip.write(types.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("_rels/.rels"));zip.write(rootRels.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("xl/workbook.xml"));zip.write(workbook.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));zip.write(wbRels.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));zip.write(sheet.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.close();return out.toByteArray();
    }
    private ObjectNode createOk(String requestId,String fileName,byte[] bytes){return svc.create(obj().put("requestId",requestId).put("fileName",fileName),1,bytes);}
    /** 测试侧独立实现的字节级 sha256，避免与被测实现共用代码导致假阳性。 */
    private static String sha256Hex(byte[] bytes){
        try{
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            byte[] out=digest.digest(bytes);
            StringBuilder hex=new StringBuilder(out.length*2);
            for(byte b:out)hex.append(String.format("%02x",b&0xff));
            return hex.toString();
        }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private Map<String,Object> jobRow(String jobId){return fixture.db.queryForMap("select * from ai_workbook_import_job where job_id=?",jobId);}
    private int artifactRows(String jobId){return fixture.db.queryForObject("select count(*) from ai_analysis_artifact where run_id=?",Integer.class,jobId);}
    private int chunkRows(String jobId){return fixture.db.queryForObject("select count(*) from ai_analysis_artifact_chunk c join ai_analysis_artifact a on a.artifact_id=c.artifact_id where a.run_id=?",Integer.class,jobId);}

    // ---------- state machine ----------
    public void testCreateSucceedsAndStateMachineReachesSucceeded() throws Exception {
        byte[] bytes=smallWorkbook();
        ObjectNode created=createOk(RUN+"job-ok-1","demo.xlsx",bytes);
        assertEquals("SUCCEEDED",created.path("status").asText());
        String jobId=created.path("jobId").asText();
        Map<String,Object> row=jobRow(jobId);
        assertEquals("状态机终态落库","SUCCEEDED",String.valueOf(row.get("status")));
        assertEquals("源 hash 授权凭据即上传字节 sha256（测试侧独立计算）",sha256Hex(bytes),String.valueOf(row.get("source_content_hash")));
        JsonNode history=parse(String.valueOf(row.get("state_history_json")));
        assertEquals("状态历史完整可追溯",arr().add("PARSING").add("MATERIALIZING").add("SUCCEEDED").toString(),history.toString());
        assertEquals("C1 分块制品已物化且 ACTIVE","ACTIVE",fixture.db.queryForObject("select status from ai_analysis_artifact where artifact_id=?",String.class,String.valueOf(row.get("artifact_id"))));
        assertEquals("chunked",fixture.db.queryForObject("select storage_kind from ai_analysis_artifact where artifact_id=?",String.class,String.valueOf(row.get("artifact_id"))));
        assertEquals("workbook_import",fixture.db.queryForObject("select schema_name from ai_analysis_artifact where artifact_id=?",String.class,String.valueOf(row.get("artifact_id"))));
        assertEquals(1,((Number)row.get("sheet_count")).intValue());
        assertEquals(6,((Number)row.get("total_cells")).intValue());
        assertTrue("无失败字段",row.get("fail_code")==null);
        assertEquals("view 与 create 返回一致",created.toString(),svc.view(jobId,1).toString());
        assertEquals("requestId 反查同任务",jobId,svc.findByRequestId(RUN+"job-ok-1",1).path("jobId").asText());
    }

    public void testIdempotentReplayReturnsExistingJobAndConflictsOnChangedParams() throws Exception {
        byte[] bytes=smallWorkbook();
        ObjectNode first=createOk(RUN+"job-idem","a.xlsx",bytes);
        ObjectNode replay=createOk(RUN+"job-idem","a.xlsx",bytes);
        assertEquals("幂等重放返回同一任务",first.path("jobId").asText(),replay.path("jobId").asText());
        assertEquals("只有一行任务",1,fixture.db.queryForObject("select count(*) from ai_workbook_import_job where request_id=?",Integer.class,RUN+"job-idem").intValue());
        try{createOk(RUN+"job-idem","renamed.xlsx",bytes);fail("参数变化必须冲突");}
        catch(IllegalArgumentException e){assertTrue("unexpected: "+e.getMessage(),e.getMessage().startsWith("IMPORT_IDEMPOTENCY_CONFLICT"));}
    }

    /** 预算拒绝：默认阈值真实 zip bomb，任务 FAILED、原因可追溯、零制品残留。 */
    public void testZipBombRefusalFailsJobWithTraceableReasonAndZeroResidue() throws Exception {
        byte[] bomb=compressionBomb();
        assertTrue("fixture 应显著小于展开预算",bomb.length<100000);
        try{createOk(RUN+"job-bomb","bomb.xlsx",bomb);fail("压缩比超限必须拒绝");}
        catch(IllegalArgumentException e){
            assertTrue("unexpected: "+e.getMessage(),e.getMessage().startsWith("IMPORT_BUDGET_EXCEEDED"));
            assertTrue(e.getMessage().contains("COMPRESSION_RATIO"));
        }
        ObjectNode failed=svc.findByRequestId(RUN+"job-bomb",1);
        assertEquals("FAILED",failed.path("status").asText());
        assertEquals("IMPORT_BUDGET_EXCEEDED",failed.path("failCode").asText());
        assertEquals("PARSE",failed.path("failPhase").asText());
        assertEquals("拒绝原因种类可追溯","COMPRESSION_RATIO",failed.path("failDetail").path("kind").asText());
        JsonNode history=parse(failed.path("stateHistory").toString());
        assertEquals("拒绝发生在物化之前",arr().add("PARSING").add("FAILED").toString(),history.toString());
        assertEquals("零制品残留",0,artifactRows(failed.path("jobId").asText()));
        assertEquals("零分块残留",0,chunkRows(failed.path("jobId").asText()));
    }

    // ---------- authorization + bounded preview ----------
    public void testPreviewIsBoundedAndRequiresMatchingSourceHash() throws Exception {
        byte[] bytes=wideWorkbook(300);
        ObjectNode job=createOk(RUN+"job-prev","wide.xlsx",bytes);
        String jobId=job.path("jobId").asText(),hash=job.path("sourceContentHash").asText();
        try{svc.preview(jobId,1,"deadbeef",50);fail("错误 hash 必须拒绝");}
        catch(IllegalArgumentException e){assertTrue("unexpected: "+e.getMessage(),e.getMessage().startsWith("IMPORT_HASH_MISMATCH"));}
        ObjectNode preview=svc.preview(jobId,1,hash,50);
        assertTrue("预览必须声明有限",preview.path("limited").asBoolean());
        assertFalse("不得声明全量覆盖",preview.path("fullSourceCoverage").asBoolean());
        assertEquals(50,preview.path("returnedRows").asInt());
        assertEquals(300,preview.path("totalRowCount").asInt());
        assertTrue("末行标记不得出现在预览响应中",!preview.toString().contains("row-299"));
        assertTrue("预览行含真实单元格",preview.path("rows").get(0).path("cells").path("0").path("v").asText().equals("row-0"));
        try{svc.preview(jobId,1,hash,0);fail("行数下界必须校验");}
        catch(IllegalArgumentException e){assertTrue(e.getMessage().startsWith("IMPORT_PREVIEW_INVALID"));}
        try{svc.preview(jobId,1,hash,ExcelImportService.MAX_PREVIEW_ROWS+1);fail("行数上界必须校验");}
        catch(IllegalArgumentException e){assertTrue(e.getMessage().startsWith("IMPORT_PREVIEW_INVALID"));}
    }

    public void testPreviewRefusedForNonSucceededJob() throws Exception {
        try{createOk(RUN+"job-failed","bomb.xlsx",compressionBomb());fail("bomb 必须拒绝");}
        catch(IllegalArgumentException ignored){}
        ObjectNode failed=svc.findByRequestId(RUN+"job-failed",1);
        try{svc.preview(failed.path("jobId").asText(),1,failed.path("sourceContentHash").asText(),10);fail("未成功任务不得预览");}
        catch(IllegalArgumentException e){assertTrue("unexpected: "+e.getMessage(),e.getMessage().startsWith("IMPORT_NOT_READY"));}
    }

    public void testForeignUserSeesNothing() throws Exception {
        ObjectNode job=createOk(RUN+"job-mine","m.xlsx",smallWorkbook());
        try{svc.view(job.path("jobId").asText(),2);fail("他人任务必须拒绝");}
        catch(IllegalArgumentException e){assertTrue("unexpected: "+e.getMessage(),e.getMessage().startsWith("IMPORT_FORBIDDEN"));}
    }

    public void testGateDisabledByDefault() throws Exception {
        ExcelImportService blocked=new ExcelImportService(fixture.db,fixture.tx.getTransactionManager());
        try{blocked.create(obj().put("requestId","x").put("fileName","y"),1,smallWorkbook());fail("门禁默认关闭");}
        catch(IllegalArgumentException e){assertTrue("unexpected: "+e.getMessage(),e.getMessage().startsWith("AI_CAPABILITY_BLOCKED"));}
    }

    // ---------- C3-review F2/F5 regressions (precision across the full chain, structured ledger facts) ----------
    /** 独立构造合成 sheet XML（与 ExcelImportParseTest.rawXlsx 同形，避免跨测试类依赖）。 */
    private byte[] rawRows(String rowsXml) throws Exception {
        String ns="xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"";
        String types="<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            +"<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            +"<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            +"<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            +"<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>";
        String rootRels="<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            +"<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>";
        String wbRels="<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            +"<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>";
        String workbook="<?xml version=\"1.0\"?><workbook "+ns+"><sheets><sheet name=\"S1\" sheetId=\"1\" r:id=\"rId1\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"/></sheets></workbook>";
        String sheet="<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet "+ns+"><sheetData>"+rowsXml+"</sheetData></worksheet>";
        ByteArrayOutputStream out=new ByteArrayOutputStream();ZipOutputStream zip=new ZipOutputStream(out);
        zip.putNextEntry(new ZipEntry("[Content_Types].xml"));zip.write(types.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("_rels/.rels"));zip.write(rootRels.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("xl/workbook.xml"));zip.write(workbook.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("xl/_rels/workbook.xml.rels"));zip.write(wbRels.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));zip.write(sheet.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        zip.close();return out.toByteArray();
    }
    /** F2：精度契约必须贯穿 上传→物化→读取→响应 全链路——29 位小数字面量经预览返回必须逐位一致且为
     *  DecimalNode（审核探针原场景：blob 完整但读取被 Jackson 默认 DoubleNode 重排为 0.12345678901234568）。 */
    public void testPreviewPreservesExactDecimalPrecisionAcrossFullChain() throws Exception {
        String literal="0.12345678901234567890123456789";
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(XSSFWorkbook wb=new XSSFWorkbook()){wb.createSheet("Data").createRow(0).createCell(0);
            wb.getSheet("Data").getRow(0).getCell(0).getCTCell().setV(literal);wb.write(out);}
        ObjectNode job=createOk(RUN+"precise","precise.xlsx",out.toByteArray());
        ObjectNode preview=svc.preview(job.path("jobId").asText(),1,job.path("sourceContentHash").asText(),1);
        com.fasterxml.jackson.databind.JsonNode v=preview.path("rows").get(0).path("cells").path("0").path("v");
        assertTrue("预览必须携带全精度数值节点",v.isBigDecimal());
        assertEquals("29 位小数逐位一致",new java.math.BigDecimal(literal),v.decimalValue());
    }
    /** F5：预算拒绝的落库证据必须结构化（failDetail 携带 kind/limit/observed，而非只有 message）——
     *  覆盖 job 通道可达的 ROWS / COLUMNS / CELL_TEXT 三类（审核探针原场景：ROWS 的 failDetail 缺结构化字段）。 */
    public void testBudgetRefusalsCarryStructuredLedgerEvidence() throws Exception {
        String[][] cases={
            {"rowlimit","<row r=\"1048577\"><c r=\"A1048577\"><v>1</v></c></row>","ROWS","1048576","1048577"},
            {"collimit","<row r=\"1\"><c r=\"XFE1\"><v>1</v></c></row>","COLUMNS","16384","16385"},
        };
        for(String[] c:cases){
            String rid=RUN+c[0];
            try{svc.create(obj().put("requestId",rid).put("fileName",c[0]+".xlsx"),1,rawRows(c[1]));fail(c[2]+" 必须拒绝");}
            catch(IllegalArgumentException expected){assertTrue("unexpected: "+expected.getMessage(),expected.getMessage().startsWith("IMPORT_BUDGET_EXCEEDED"));}
            ObjectNode detail=(ObjectNode)svc.findByRequestId(rid,1).path("failDetail");
            assertEquals(c[2]+" 证据种类可追溯",c[2],detail.path("kind").asText());
            assertEquals(c[2]+" 上限可追溯",c[3],detail.path("limit").asText());
            assertEquals(c[2]+" 观察值可追溯",c[4],detail.path("observed").asText());
        }
        String rid=RUN+"celltext";
        // 随机字符：重复字符会把表条目压到 >100x，先撞单条目压缩比守卫而非 CELL_TEXT
        StringBuilder huge=new StringBuilder();Random cr=new Random(7);
        for(int i=0;i<32768;i++)huge.append((char)('a'+cr.nextInt(26)));
        try{svc.create(obj().put("requestId",rid).put("fileName","cell.xlsx"),1,rawRows("<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>"+huge+"</t></is></c></row>"));fail("CELL_TEXT 必须拒绝");}
        catch(IllegalArgumentException expected){assertTrue("unexpected: "+expected.getMessage(),expected.getMessage().startsWith("IMPORT_BUDGET_EXCEEDED"));}
        ObjectNode detail=(ObjectNode)svc.findByRequestId(rid,1).path("failDetail");
        assertEquals("CELL_TEXT 证据种类可追溯","CELL_TEXT",detail.path("kind").asText());
        assertEquals("CELL_TEXT 上限可追溯","32767",detail.path("limit").asText());
        assertEquals("CELL_TEXT 观察值可追溯","32768",detail.path("observed").asText());
    }

    // ---------- C3 re-review F1-b (re-open): same-entry mixed compression ratio ----------
    /** 同条目混合压缩比夹具（复现复核探针 C3R2BoundaryProbe 的精确构造）：真实 POI 工作簿的
     *  xl/worksheets/sheet1.xml 条目被重建为 [真实行 XML + &lt;!-- + 45 万 'x' 高压缩区 + 20 万
     *  固定种子随机字母近 1:1 填充 + --&gt;]——条目最终展开比 ~5x，读后条目审计与整档预算均放行；
     *  但 POI 4.1.2 ZipArchiveThresholdInputStream 按"实时消耗压缩字节/展开字节"在展开 ~103KB 处
     *  触发 "Zip bomb detected"（展开 &gt;102400 后才查比例）。 */
    private byte[] mixedEntryBomb() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream src = new ZipInputStream(new java.io.ByteArrayInputStream(smallWorkbook()));
             ZipOutputStream dst = new ZipOutputStream(out)) {
            ZipEntry e; byte[] buf = new byte[4096];
            while ((e = src.getNextEntry()) != null) {
                dst.putNextEntry(new ZipEntry(e.getName())); int n;
                while ((n = src.read(buf)) != -1) dst.write(buf, 0, n);
                if ("xl/worksheets/sheet1.xml".equals(e.getName())) {
                    dst.write("<!--".getBytes(StandardCharsets.UTF_8));
                    byte[] repeated = new byte[450000]; Arrays.fill(repeated, (byte) 'x');
                    dst.write(repeated);
                    byte[] varied = new byte[200000]; Random random = new Random(42);
                    for (int i = 0; i < varied.length; i++) varied[i] = (byte) ('a' + random.nextInt(26));
                    dst.write(varied);
                    dst.write("-->".getBytes(StandardCharsets.UTF_8));
                }
                dst.closeEntry();
            }
        }
        return out.toByteArray();
    }
    /** F1-b 复开原场景：同条目混合压缩比文件安全上必须被拦截，且拒绝证据必须结构化——
     *  任务 FAILED、fail_code=IMPORT_BUDGET_EXCEEDED、failDetail.kind=COMPRESSION_RATIO、
     *  limit/observed 三字段齐全非空（旧行为：POI 中途拒绝被吞成 IMPORT_INVALID 且 kind 为空）。 */
    public void testMixedCompressionRatioEntryRefusesWithStructuredFacts() throws Exception {
        byte[] mixed = mixedEntryBomb();
        String rid = RUN + "mixratio";
        IllegalArgumentException refusal = null;
        try { createOk(rid, "mixed.xlsx", mixed); }
        catch (IllegalArgumentException e) { refusal = e; }
        ObjectNode failed = svc.findByRequestId(rid, 1);
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("结构化拒绝码", "IMPORT_BUDGET_EXCEEDED", failed.path("failCode").asText());
        assertEquals("PARSE", failed.path("failPhase").asText());
        JsonNode detail = failed.path("failDetail");
        assertEquals("拒绝种类必须结构化", "COMPRESSION_RATIO", detail.path("kind").asText());
        assertFalse("limit 必须非空", detail.path("limit").isNull() || detail.path("limit").asText().isEmpty());
        assertFalse("observed 必须非空", detail.path("observed").isNull() || detail.path("observed").asText().isEmpty());
        long observed = Long.parseLong(detail.path("observed").asText());
        assertTrue("POI 实时比例阈值触发时展开倍数必 >=100x: " + observed, observed >= 100);
        System.out.println("[c3-f1b] mixed-entry refusal failCode=" + failed.path("failCode").asText()
            + " kind=" + detail.path("kind").asText() + " limit=" + detail.path("limit").asText()
            + " observed=" + observed);
        assertNotNull("导入必须抛出拒绝", refusal);
        assertTrue("unexpected: " + refusal.getMessage(), refusal.getMessage().startsWith("IMPORT_BUDGET_EXCEEDED"));
        assertTrue(refusal.getMessage().contains("COMPRESSION_RATIO"));
        JsonNode history = parse(failed.path("stateHistory").toString());
        assertEquals("拒绝发生在物化之前", arr().add("PARSING").add("FAILED").toString(), history.toString());
        assertEquals("零制品残留", 0, artifactRows(failed.path("jobId").asText()));
        assertEquals("零分块残留", 0, chunkRows(failed.path("jobId").asText()));
    }
    /** F1-b 回归红线：解析全程（含 POI 中途拒绝路径与成功路径）只读 getMinInflateRatio()，
     *  绝不 read-modify 全局 ZipSecureFile 安全设置。断言值记录于测试输出与报告。 */
    public void testPoiGlobalMinInflateRatioImmutableAcrossImports() throws Exception {
        double before = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
        try { createOk(RUN + "immut-bomb", "mixed.xlsx", mixedEntryBomb()); fail("混合压缩比必须拒绝"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().startsWith("IMPORT_BUDGET_EXCEEDED")); }
        ObjectNode ok = createOk(RUN + "immut-ok", "ok.xlsx", smallWorkbook());
        assertEquals("SUCCEEDED", ok.path("status").asText());
        double after = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
        System.out.println("[c3-f1b] ZipSecureFile.getMinInflateRatio before=" + before + " after=" + after);
        assertEquals("导入全程不得修改全局压缩比保护", before, after, 0.0);
        assertEquals("全局保护保持 POI 4.1.2 默认 0.01", 0.01d, after, 0.0);
    }

    // ---------- C3-review-04 缺口1：包元数据 [Content_Types].xml 内的 zip bomb（P2 反例） ----------
    /** 炸弹藏进包元数据条目：[Content_Types].xml = 原始内容类型 + 高压缩 Override 行（大量重复风格行，
     *  ~2MB 展开 ~1KB 压缩）+ &lt;!-- --&gt; 注释内固定种子随机填充（纯字母无 "--"，~2MB 近 1:1）——
     *  条目终比 ~2x、整档审计与读后单条目审计全部放行；POI 打开包读该条目时按实时消耗比例触发拒绝，
     *  异常链是 InvalidFormatException 包装（"Can't read content types part!" -> "Zip bomb detected! ..."），
     *  非 IOException/InvalidOperationException。 */
    private byte[] metadataRatioBomb() throws Exception {
        String line = "<Override PartName=\"/xl/dummy.xml\" ContentType=\"application/xml\" p=\""
            + "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp"
            + "pppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppppp"
            + "pppppppppppppppppppppppppppppppppppppppppppppppppp\"/>";
        StringBuilder bomb = new StringBuilder();
        while (bomb.length() < 2_000_000) bomb.append(line);
        bomb.append("<!--");
        Random r = new Random(42);
        while (bomb.length() < 4_000_000) bomb.append((char) ('a' + r.nextInt(26)));
        bomb.append("-->");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipInputStream src = new ZipInputStream(new java.io.ByteArrayInputStream(smallWorkbook()));
             ZipOutputStream dst = new ZipOutputStream(out)) {
            ZipEntry e; byte[] buf = new byte[4096];
            while ((e = src.getNextEntry()) != null) {
                dst.putNextEntry(new ZipEntry(e.getName())); int n;
                ByteArrayOutputStream item = new ByteArrayOutputStream();
                while ((n = src.read(buf)) != -1) item.write(buf, 0, n);
                byte[] original = item.toByteArray();
                if ("[Content_Types].xml".equals(e.getName())) {
                    // 在 </Types> 之前注入：保留全部原始 Override，包内容类型完整保真
                    String text = new String(original, StandardCharsets.UTF_8);
                    int cut = text.lastIndexOf("</Types>");
                    if (cut < 0) throw new IllegalStateException("fixture: </Types> missing");
                    byte[] merged = (text.substring(0, cut) + bomb + "</Types>").getBytes(StandardCharsets.UTF_8);
                    dst.write(merged);
                } else {
                    dst.write(original);
                }
                dst.closeEntry();
            }
        }
        return out.toByteArray();
    }
    /** C3-review-04 反例：包元数据 zip bomb 必须结构化落库——FAILED、IMPORT_BUDGET_EXCEEDED、
     *  failDetail.kind=COMPRESSION_RATIO、limit=100、observed>=100（红：IMPORT_INVALID + kind 空），
     *  且零制品残留、全局压缩比保护不变。 */
    public void testPackageMetadataZipBombRefusesWithStructuredFacts() throws Exception {
        double ratioBefore = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
        byte[] bomb = metadataRatioBomb();
        String rid = RUN + "metabomb";
        IllegalArgumentException refusal = null;
        try { createOk(rid, "meta-bomb.xlsx", bomb); }
        catch (IllegalArgumentException e) { refusal = e; }
        ObjectNode failed = svc.findByRequestId(rid, 1);
        assertEquals("FAILED", failed.path("status").asText());
        assertEquals("结构化拒绝码", "IMPORT_BUDGET_EXCEEDED", failed.path("failCode").asText());
        assertEquals("PARSE", failed.path("failPhase").asText());
        JsonNode detail = failed.path("failDetail");
        assertEquals("拒绝种类必须结构化", "COMPRESSION_RATIO", detail.path("kind").asText());
        assertEquals("上限沿用 POI 单条目边界", "100", detail.path("limit").asText());
        long observed = Long.parseLong(detail.path("observed").asText());
        assertTrue("POI 实时比例触发时展开倍数必 >=100x: " + observed, observed >= 100);
        assertTrue("有尺寸消息必须落展开字节证据", detail.path("observedBytes").isNumber()
            && detail.path("observedBytes").asLong() > 102400);
        System.out.println("[c3-r04] metadata-bomb failCode=" + failed.path("failCode").asText()
            + " kind=" + detail.path("kind").asText() + " limit=" + detail.path("limit").asText()
            + " observed=" + observed + " observedBytes=" + detail.path("observedBytes").asLong());
        assertNotNull("导入必须抛出拒绝", refusal);
        assertTrue("unexpected: " + refusal.getMessage(), refusal.getMessage().startsWith("IMPORT_BUDGET_EXCEEDED"));
        assertTrue(refusal.getMessage().contains("COMPRESSION_RATIO"));
        JsonNode history = parse(failed.path("stateHistory").toString());
        assertEquals("拒绝发生在物化之前", arr().add("PARSING").add("FAILED").toString(), history.toString());
        assertEquals("零制品残留", 0, artifactRows(failed.path("jobId").asText()));
        assertEquals("零分块残留", 0, chunkRows(failed.path("jobId").asText()));
        assertEquals("全局压缩比保护不变", ratioBefore, org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio(), 0.0);
    }

    // ---------- C3 re-review F6: preview keeps every C1 chunk-integrity check ----------
    /** 双块任务夹具：5500 行 → 5000+500 两块（C1 CHUNK_ROWS=5000）。
     *  静态缓存：XSSFWorkbook 每次写出嵌入不同时间戳，字节不同会撞 IMPORT_IDEMPOTENCY_CONFLICT；
     *  三个 F6 测试共用同一任务（各自修改后恢复）。 */
    private static ObjectNode TWO_CHUNK_JOB;
    private ObjectNode twoChunkJob() throws Exception {
        if(TWO_CHUNK_JOB!=null)return TWO_CHUNK_JOB;
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(XSSFWorkbook wb=new XSSFWorkbook()){Sheet s=wb.createSheet("Wide");
            for(int i=0;i<5500;i++){Row r=s.createRow(i);r.createCell(0).setCellValue("row-"+i);r.createCell(1).setCellValue(i);}
            wb.write(out);}
        TWO_CHUNK_JOB=createOk(RUN+"twoc","twoc.xlsx",out.toByteArray());
        return TWO_CHUNK_JOB;
    }
    private String artifactIdOf(ObjectNode job){return fixture.db.queryForObject("select artifact_id from ai_workbook_import_job where job_id=?",String.class,job.path("jobId").asText());}
    /** F6：预览走 C1 readChunkPage 精度过载——空块（改 [] 并同步块哈希，保留声明行数）必须与 C1 一样以
     *  ARTIFACT_CHUNK_SEQUENCE_INVALID 拒绝，不得返回 returnedRows=0 的假成功（审核探针原场景）。 */
    public void testPreviewRefusesEmptyFirstChunkLikeC1() throws Exception {
        ObjectNode job=twoChunkJob();String artifact=artifactIdOf(job),hash=job.path("sourceContentHash").asText();
        Map<String,Object> original=fixture.db.queryForMap("select content_blob,content_hash from ai_analysis_artifact_chunk where artifact_id=? and chunk_index=0",artifact);
        try{
            fixture.db.update("update ai_analysis_artifact_chunk set content_blob='[]',content_hash=? where artifact_id=? and chunk_index=0",com.ruoyi.ai.util.ContentHashUtils.sha256("[]"),artifact);
            try{svc.preview(job.path("jobId").asText(),1,hash,10);fail("空块预览必须拒绝");}
            catch(IllegalArgumentException e){assertTrue("unexpected: "+e.getMessage(),e.getMessage().startsWith("ARTIFACT_CHUNK_SEQUENCE_INVALID"));}
        }finally{fixture.db.update("update ai_analysis_artifact_chunk set content_blob=?,content_hash=? where artifact_id=? and chunk_index=0",original.get("content_blob"),original.get("content_hash"),artifact);}
    }
    /** F6：切片不符（chunk 0 的 row_start 被改）必须拒绝——C1 序列/切片检查在精确预览中全部保留。 */
    public void testPreviewRefusesBrokenSlice() throws Exception {
        ObjectNode job=twoChunkJob();String artifact=artifactIdOf(job),hash=job.path("sourceContentHash").asText();
        try{
            fixture.db.update("update ai_analysis_artifact_chunk set row_start=3 where artifact_id=? and chunk_index=0",artifact);
            try{svc.preview(job.path("jobId").asText(),1,hash,10);fail("切片不符必须拒绝");}
            catch(IllegalArgumentException e){assertTrue("unexpected: "+e.getMessage(),e.getMessage().startsWith("ARTIFACT_CHUNK_SEQUENCE_INVALID"));}
        }finally{fixture.db.update("update ai_analysis_artifact_chunk set row_start=0 where artifact_id=? and chunk_index=0",artifact);}
    }
    /** F6：缺尾块（删除 chunk 1）必须拒绝——块数与 row_count 推算块数不符。 */
    public void testPreviewRefusesMissingTailChunk() throws Exception {
        ObjectNode job=twoChunkJob();String artifact=artifactIdOf(job),hash=job.path("sourceContentHash").asText();
        try{
            fixture.db.update("delete from ai_analysis_artifact_chunk where artifact_id=? and chunk_index=1",artifact);
            try{svc.preview(job.path("jobId").asText(),1,hash,10);fail("缺尾块必须拒绝");}
            catch(IllegalArgumentException e){assertTrue("unexpected: "+e.getMessage(),e.getMessage().startsWith("ARTIFACT_CHUNK_SEQUENCE_INVALID"));}
        }finally{fixture.db.update("insert into ai_analysis_artifact_chunk(artifact_id,chunk_index,row_start,row_count,content_blob,content_hash) select ?,1,5000,500,content_blob,content_hash from ai_analysis_artifact_chunk where artifact_id=? and chunk_index=0",artifact,artifact);}
    }
}
