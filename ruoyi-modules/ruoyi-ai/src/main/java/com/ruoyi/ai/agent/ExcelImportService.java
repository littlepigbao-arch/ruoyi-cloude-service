package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.RichTextString;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C3 backend XLSX import: POI 4.1.2 event model only ({@link XSSFReader} plus SAX handlers over the
 * sheet/sharedStrings/styles/workbook parts) - no {@code XSSFWorkbook} DOM load anywhere on the import
 * path. The upload is spooled to a temp file so {@link OPCPackage} reads parts lazily and every part
 * stream is wrapped in a counting stream, which is where all budgets are enforced BEFORE anything is
 * materialized (C2 WRITE_BUDGET_EXCEEDED precedent):
 * - SOURCE_BYTES raw upload cap; EXPANDED_BYTES total uncompressed cap; COMPRESSION_RATIO zip-bomb
 *   guard (uncompressed vs container size); ROWS/COLUMNS per Excel's own bounds; SHARED_STRINGS entry
 *   and char caps while the table streams in; CELL_TEXT per-cell cap (Excel's 32767); TOTAL_CELLS
 *   whole-workbook cap.
 * Cell typing is precise and aligned with the existing workbook cellData model
 * (t=1 string / t=2 number / t=3 boolean, see WriteManifest.java:172 and univer-action-executor.js:284):
 * numbers keep the file's literal digits as an exact decimal (never a double reparse); booleans import
 * as JSON booleans; date-formatted numerics import as t=2 serials with the workbook's 1904 date system
 * normalized to the 1900 basis (+1462 days, time fraction preserved); strings come from shared or
 * inline storage with rich runs concatenated; formulas keep the file's cached value verbatim and are
 * NEVER recomputed (SAX reading is not formula evaluation) - shared-formula slaves have no per-cell
 * formula text in the file, so they import the cached value without an f (documented drop, like
 * styles/merges/charts/macros/external links - import scope is cellData only).
 * Authorization is content-addressed: the job row records the sha256 of the uploaded bytes; the only
 * data-bearing follow-up (preview) must present exactly that hash, and no endpoint ever accepts a file
 * path or filesystem reference. The state machine PARSING -> MATERIALIZING -> SUCCEEDED / FAILED keeps
 * every transition in state_history_json and every refusal in fail_code/fail_phase/fail_detail_json;
 * budget refusals happen during parse, so a FAILED job never leaves artifact rows.
 */
@Service
public class ExcelImportService {
    /** Raw upload cap. */
    public static final long MAX_SOURCE_BYTES = 100L * 1024 * 1024;
    /** Total uncompressed-bytes cap across all parsed parts (zip bomb absolute bound). */
    public static final long MAX_EXPANDED_BYTES = 512L * 1024 * 1024;
    /** Uncompressed/compressed ratio cap (zip bomb ratio bound; real XLSX compresses ~10-20x). */
    public static final long MAX_COMPRESSION_RATIO = 100; // aligned with POI ZipSecureFile's 0.01 min-inflate boundary: audit refuses first, POI's guard never fires on accepted files
    /** Excel's own row bound (row index must stay below this). */
    public static final int MAX_ROWS = 1048576;
    /** Excel's own column bound (column index must stay below this). */
    public static final int MAX_COLUMNS = 16384;
    /** Shared string table entry cap, enforced while the table streams in. */
    public static final int MAX_SHARED_STRINGS = 2000000;
    /** Shared string table total character cap. */
    public static final long MAX_SHARED_STRING_CHARS = 64000000L;
    /** Per-cell text cap (Excel's own 32767 char cell limit). */
    public static final int MAX_CELL_TEXT_CHARS = 32767;
    /** Whole-workbook cell cap enforced during parse (materialization refuses beyond it). */
    public static final int MAX_TOTAL_CELLS = 1000000;
    /** Preview row hard bound - a preview response can never carry more than this many row records. */
    public static final int MAX_PREVIEW_ROWS = 200;
    /** Sentinel for an unobservable observed measurement (C3-REVIEW-05 generalized from ratio-only):
     *  the refusal message carried no parseable sizes, so the observed/limit caliber is genuinely
     *  unknown. The refusal then reports byte evidence separately (observedBytes) and renders the
     *  message as "观察值 未知（展开 N 字节）". Never a real measurement; any truly observed value is
     *  non-negative (a floor ratio multiple, or an expanded byte count). */
    public static final long UNKNOWN_RATIO = -1L;
    /** Artifact lane schema name (deliberately not the dataset schema - no dependency registration). */
    public static final String SCHEMA_NAME = "workbook_import";
    private static final int DAYS_1904_TO_1900 = 1462;
    private static final String[] DROPPED_FEATURES = {"styles", "merges", "charts", "macros", "externalLinks", "comments", "drawings", "sharedFormulaSlaveFormulas"};

    /** Immutable limits-only value object; the service lane always runs {@link #defaults()}. */
    public static final class Budgets {
        public long maxSourceBytes = MAX_SOURCE_BYTES;
        public long maxExpandedBytes = MAX_EXPANDED_BYTES;
        public long maxCompressionRatio = MAX_COMPRESSION_RATIO;
        public int maxRows = MAX_ROWS;
        public int maxColumns = MAX_COLUMNS;
        public int maxSharedStrings = MAX_SHARED_STRINGS;
        public long maxSharedStringChars = MAX_SHARED_STRING_CHARS;
        public int maxCellTextChars = MAX_CELL_TEXT_CHARS;
        public int maxTotalCells = MAX_TOTAL_CELLS;
        public static Budgets defaults() { return new Budgets(); }
    }

    /** Budget refusal carrying structured facts for the job ledger. Always kind/limit/observed;
     *  an optional fourth fact observedBytes (null = 不适用) carries byte evidence when the observed
     *  value and the byte evidence differ in caliber (POI mid-parse ratio refusal: observed is a
     *  floor ratio multiple or the {@link #UNKNOWN_RATIO} sentinel, observedBytes the uncompressed
     *  byte count). When observedBytes comes from the no-sizes fallback it is this service's
     *  CUMULATIVE whole-archive expanded-byte counter, a different caliber from POI's per-entry
     *  refusal-instant count - the refusal message says so explicitly. Package-private for offline
     *  white-box tests; not a public API surface. */
    static final class BudgetRefusal extends IllegalArgumentException {
        final String kind; final String limit; final String observed; final Long observedBytes;
        BudgetRefusal(String kind, long limit, long observed) { this(kind, limit, observed, null); }
        BudgetRefusal(String kind, long limit, long observed, Long observedBytes) {
            super(refusalMessage(kind, limit, observed, observedBytes));
            this.kind = kind; this.limit = String.valueOf(limit); this.observed = String.valueOf(observed);
            this.observedBytes = observedBytes;
        }
        private static String refusalMessage(String kind, long limit, long observed, Long observedBytes) {
            String observedText = observed == UNKNOWN_RATIO && observedBytes != null
                ? "未知（展开 " + observedBytes + " 字节，全档累计口径，非该条目单计）" : String.valueOf(observed);
            return "IMPORT_BUDGET_EXCEEDED: " + kind + " 上限 " + limit + "，观察值 " + observedText;
        }
    }

    private final JdbcTemplate db;
    /** C3-review F2: chunk blobs are re-parsed with floats mapped to BigDecimal so stored decimal
     *  literals (e.g. 29-digit precision) survive the read; Jackson's default yields DoubleNode. */
    private static final com.fasterxml.jackson.databind.ObjectMapper PRECISE_JSON =
        new com.fasterxml.jackson.databind.ObjectMapper().configure(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
    private final TransactionTemplate tx;
    private final com.ruoyi.ai.agent.data.ArtifactService artifacts;
    @Value("${ai.agent.import-enabled:false}") private boolean enabled;

    public ExcelImportService(JdbcTemplate db, PlatformTransactionManager manager) {
        this.db = db; this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.artifacts = new com.ruoyi.ai.agent.data.ArtifactService(db);
    }
    public boolean enabled() { return enabled; }

    // ------------------------------------------------------------------
    // parse core: bytes -> workbook cellData JSON (pure, no database access)
    // ------------------------------------------------------------------
    public static ObjectNode parse(byte[] source, Budgets budgets) {
        require(source != null && source.length > 0, "IMPORT_SOURCE_REQUIRED: 上传内容为空");
        if (source.length > budgets.maxSourceBytes) throw new BudgetRefusal("SOURCE_BYTES", budgets.maxSourceBytes, source.length);
        Path spool = null;
        try {
            spool = Files.createTempFile("c3-import-", ".xlsx");
            Files.write(spool, source);
            return parseSpooled(spool.toFile(), source.length, budgets);
        } catch (IOException e) {
            throw new IllegalArgumentException("IMPORT_INVALID: 无法读取上传内容", e);
        } finally {
            if (spool != null) try { Files.deleteIfExists(spool); } catch (IOException ignored) { }
        }
    }

    private static ObjectNode parseSpooled(File file, long containerBytes, Budgets limits) {
        Counters counters = new Counters(limits, containerBytes);
        // C3-review F1: POI reads package metadata (workbook.xml, rels, styles, sharedStrings) through
        // its own streams that our part-level CountingInputStream never sees, so expansion budgets were
        // bypassable via metadata, and the previous fix (per-request ZipSecureFile.setMinInflateRatio(0.0))
        // permanently disabled the JVM-global guard. The audit pass below counts EVERY zip entry through
        // our budgeted stream BEFORE POI opens the package: same physical bytes POI will later expand, so
        // no read path can escape accounting, and no global security setting is touched. With the audit
        // enforcing expanded <= containerBytes * maxCompressionRatio (default 100, aligned with POI's own
        // 0.01 min-inflate boundary) and the absolute maxExpandedBytes, budget refusals stay structured
        // (IMPORT_BUDGET_EXCEEDED kind/limit/observed). POI's guard stays the precise per-consumed-byte
        // backstop for shapes the a-priori audit bound cannot see (same entry mixing a high-compression
        // region with near-1:1 filler); its mid-parse refusal is remapped structurally by
        // poiZipBombRefusal so no path ends as an unstructured IMPORT_INVALID.
        auditZipEntries(file, counters);
        OPCPackage pkg = null;
        try {
            pkg = OPCPackage.open(file, PackageAccess.READ);
            XSSFReader reader = new XSSFReader(pkg);
            boolean date1904 = scanWorkbookPr(reader, counters);
            Styles styles = scanStyles(pkg, counters);
            SharedStrings strings = scanSharedStrings(pkg, counters);
            ObjectNode sheets = obj(); ArrayNode order = arr(); ArrayNode sheetStats = arr();
            long totalCells = 0;
            XSSFReader.SheetIterator iterator = (XSSFReader.SheetIterator) reader.getSheetsData();
            int index = 0;
            while (iterator.hasNext()) {
                InputStream part = iterator.next();
                String name = iterator.getSheetName();
                SheetScan scan = new SheetScan(styles, strings, date1904, limits, counters, name, index);
                try { xmlReader(scan).parse(new InputSource(counting(part, counters))); }
                finally { part.close(); }
                scan.finish();
                counters.slaveFormulas += scan.slaveFormulas;
                sheets.set(scan.sheetId, scan.sheetNode); order.add(scan.sheetId); sheetStats.add(scan.stats);
                totalCells += scan.cellCount;
                index++;
            }
            require(order.size() >= 1, "IMPORT_INVALID: 文件没有工作表");
            ObjectNode out = obj().put("id", "import-" + Long.toHexString(containerBytes) + "-" + order.size());
            out.set("sheetOrder", order); out.set("sheets", sheets);
            ObjectNode stats = obj().put("sheetCount", order.size()).put("totalCells", totalCells).put("date1904", date1904).put("sharedFormulaSlaves", counters.slaveFormulas);
            stats.set("sheets", sheetStats);
            ArrayNode dropped = arr(); for (String f : DROPPED_FEATURES) dropped.add(f);
            stats.set("dropped", dropped);
            out.set("stats", stats);
            return out;
        } catch (org.apache.poi.UnsupportedFileFormatException notXlsx) {
            throw new IllegalArgumentException("IMPORT_INVALID: 不是有效的 XLSX 文件", notXlsx);
        } catch (IllegalArgumentException e) {
            // Budget/require refusals keep their IMPORT_* codes; foreign runtime refusals normalize.
            if (e.getMessage() != null && e.getMessage().startsWith("IMPORT_")) throw e;
            throw new IllegalArgumentException("IMPORT_INVALID: " + e.getMessage(), e);
        } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
            // C3 re-review F1-b core: POI may also carry its mid-parse zip-bomb refusal inside an
            // InvalidFormatException wrapper - the same structured remap applies before the
            // unstructured "not a valid XLSX" normalization swallows the kind.
            BudgetRefusal zipBomb = poiZipBombRefusal(e, counters);
            if (zipBomb != null) throw zipBomb;
            throw new IllegalArgumentException("IMPORT_INVALID: 不是有效的 XLSX 文件", e);
        } catch (Exception e) {
            // C3 re-review F1-b core: POI's ZipArchiveThresholdInputStream refuses a mid-parse entry
            // with a raw "Zip bomb detected!" IOException; flattened here it would lose kind/limit/
            // observed. Recognize the chain first and rethrow the structured refusal instead.
            BudgetRefusal zipBomb = poiZipBombRefusal(e, counters);
            if (zipBomb != null) throw zipBomb;
            throw new IllegalArgumentException("IMPORT_INVALID: " + e.getMessage(), e);
        } finally {
            if (pkg != null) try { pkg.close(); } catch (IOException ignored) { }
        }
    }

    /** POI's static per-entry expansion boundary, read-only via {@code getMinInflateRatio()}:
     *  entries whose expansion exceeds {@code 1/minInflateRatio} times are refused by POI mid-parse.
     *  The audit pass and the mid-parse remap share this helper so both refusals carry the same
     *  limit semantics. Never written (no global security setting is read-modified or restored). */
    private static long poiPerEntryLimit() {
        double minRatio = org.apache.poi.openxml4j.util.ZipSecureFile.getMinInflateRatio();
        return minRatio > 0 ? (long) (1.0 / minRatio) : Long.MAX_VALUE;
    }
    /** POI's static per-entry expanded-size boundary, read-only via {@code getMaxEntrySize()}
     *  (public static in 4.1.2, javap-confirmed) - the ENTRY_SIZE refusal limit counterpart of
     *  {@link #poiPerEntryLimit()}. Same read-only convention: never written. */
    private static long poiMaxEntrySizeLimit() {
        return org.apache.poi.openxml4j.util.ZipSecureFile.getMaxEntrySize();
    }
    /** POI 4.1.2 ZipArchiveThresholdInputStream.checkThreshold() emits "Uncompressed size: %d,
     *  Raw/compressed size: %d, ratio: %f" (ratio variant) or the same two sizes without ", ratio:"
     *  (max-entry-size variant); the two sizes are REAL-TIME consumed byte counts at the refusal
     *  instant, not entry totals. */
    private static final java.util.regex.Pattern POI_ZIP_BOMB_SIZES =
        java.util.regex.Pattern.compile("Uncompressed size:\\s*(\\d+),\\s*Raw/compressed size:\\s*(\\d+)");
    /** Same-caliber observed pair for the POI mid-parse refusal remap: {@code observed} is the
     *  kind-appropriate measurement (a floor ratio multiple for COMPRESSION_RATIO, an expanded byte
     *  count for ENTRY_SIZE), or the {@link #UNKNOWN_RATIO} sentinel when the refusal message
     *  carried no parseable sizes; {@code observedBytes} is the byte evidence (uncompressed size
     *  parsed from the message, or this service's expanded-byte counter fallback) - never null on
     *  the remap path, so the observed value and its byte evidence stay in their own calibers. */
    static final class ZipBombObserved {
        final long observed; final Long observedBytes;
        ZipBombObserved(long observed, Long observedBytes) { this.observed = observed; this.observedBytes = observedBytes; }
    }
    /** C3-review-04 (P2) anchored remap: POI refuses a mid-parse entry whose REAL-TIME consumed
     *  compressed/uncompressed ratio drops below MIN_INFLATE_RATIO, or whose real-time expanded
     *  bytes exceed MAX_ENTRY_SIZE (both bytecode-verified against 4.1.2
     *  ZipArchiveThresholdInputStream.checkThreshold()), and POI layers then wrap that refusal in
     *  heterogeneous wrapper types - the field has proven three shapes: a bare
     *  {@link java.io.IOException} (sheet path), an {@code InvalidFormatException} wrapping another
     *  {@code InvalidFormatException} ("Can't read content types part!" -> "Zip bomb detected! ...",
     *  package metadata path, r3-ledger-probe.log), and the IOException/InvalidOperationException
     *  union of the previous fix. {@code InvalidFormatException} is NOT an IOException, so chasing
     *  wrapper types patch-by-patch runs after an unbounded variant list. Recognition therefore
     *  anchors on the message instead of the type: any layer of the cause chain whose message
     *  contains "zip bomb detected" (case-insensitive - the phrase is POI/commons-compress
     *  specific, fourth-review confirmed) is a candidate, provided evidence exists (the message
     *  carries parseable real-time sizes, or live counters are available).
     *  C3-review-04 约束1 + C3-REVIEW-05: kind FIRST from threshold wording evidence, then the
     *  observed measurement. C3-REVIEW-06 supersedes the raw wording scan: the entry name is stripped
     *  BEFORE any word match ({@link #POI_ENTRY_SEGMENT} - r5 probe proved a worksheet entry named
     *  "calibration.xml" poisoned contains("ratio") over the whole message), and kind evidence
     *  prioritizes the Limits marker ({@link #isRatioRefusalKind} - javap proves 4.1.2 marks ONLY the
     *  triggered threshold: ratio refusal "Limits: MIN_INFLATE_RATIO: ..." vs size refusal "Limits:
     *  MAX_ENTRY_SIZE: ...", so the marker set is discriminating), falling back to the stripped-body
     *  "ratio" wording rule only when the marker abstains. ratioEvidence=true -> kind=COMPRESSION_RATIO,
     *  limit=poiPerEntryLimit(), observed=poiZipBombObserved (floor ratio + uncompressed evidence,
     *  or the UNKNOWN_RATIO sentinel + counters.expanded fallback). ratioEvidence=false ->
     *  kind=ENTRY_SIZE, limit=poiMaxEntrySizeLimit() (ZipSecureFile.getMaxEntrySize(),
     *  javap-confirmed public in 4.1.2 - the refusal's own Limits line records the same value);
     *  observed = the message's uncompressed count (a real size refusal always carries sizes per
     *  bytecode), or the UNKNOWN_RATIO sentinel + counters.expanded for a sizes-missing synthetic.
     *  Package-private for offline white-box tests; returns null when the chain holds no zip-bomb
     *  evidence. */
    /** C3-REVIEW-06 (P2) entry-name stripping, hard precondition of every word match below. POI 4.1.2
     *  ZipArchiveThresholdInputStream (javap-verified) formats BOTH refusal variants with the entry
     *  name appended as the trailing segment: ratio variant "...ratio: %f\nLimits: MIN_INFLATE_RATIO:
     *  %f, Entry: %s", size variant "...size: %d\nLimits: MAX_ENTRY_SIZE: %d, Entry: %s". The entry
     *  name is ZIP-controlled attacker surface (r5 probe: a worksheet entry literally named
     *  "calibration.xml" carries the "ratio" substring) and must not be visible to threshold-word
     *  matching - strip "Entry: <name>" to end-of-message (DOTALL, name may embed anything) before
     *  kind classification. Real POI messages have no text after the name (format string ends at %s);
     *  synthetic messages without an Entry segment pass through unchanged (no match -> no-op). */
    private static final java.util.regex.Pattern POI_ENTRY_SEGMENT =
        java.util.regex.Pattern.compile(",?\\s*Entry: .*$", java.util.regex.Pattern.DOTALL);
    /** C3-REVIEW-06 (P2) kind evidence with Limits-marker priority (Kimi 裁决修法第2条). javap on
     *  poi-ooxml-4.1.2 proves each triggered threshold is marked ALONE in its Limits line - a ratio
     *  refusal says only "Limits: MIN_INFLATE_RATIO: ...", a size refusal only "Limits:
     *  MAX_ENTRY_SIZE: ..." - so the marker set IS discriminating and is the primary kind evidence.
     *  Only when the Limits section is absent or carries neither/both markers (not a 4.1.2 shape)
     *  does the marker evidence abstain ("标记无判别力") and the stripped body falls back to the
     *  C3-review-04 wording rule: contains "ratio" (ci) -> COMPRESSION_RATIO, else ENTRY_SIZE.
     *  Callers must pass the entry-name-stripped message. */
    private static boolean isRatioRefusalKind(String matchScope) {
        String lower = matchScope.toLowerCase(Locale.ROOT);
        int limits = lower.indexOf("limits:");
        if (limits >= 0) {
            String limitsSection = lower.substring(limits);
            boolean ratioMarker = limitsSection.contains("min_inflate_ratio");
            boolean sizeMarker = limitsSection.contains("max_entry_size");
            if (ratioMarker != sizeMarker) return ratioMarker; // 4.1.2 形态：只标被触发阈值，可直接判别
        }
        return lower.contains("ratio");
    }
    static BudgetRefusal poiZipBombRefusal(Throwable failure, Counters counters) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message == null || !message.toLowerCase(Locale.ROOT).contains("zip bomb detected")) continue;
            // C3-REVIEW-06: 条目名剥离是硬性前置——一切词匹配只用剥离后消息，真实条目名含任意
            // 阈值字样（ratio/size/MAX_ENTRY_SIZE 等）不再影响分类。
            String matchScope = POI_ENTRY_SEGMENT.matcher(message).replaceFirst("");
            boolean parseableSizes = POI_ZIP_BOMB_SIZES.matcher(matchScope).find();
            if (!parseableSizes && counters == null) continue; // anchor matched but no evidence at all
            boolean ratioEvidence = isRatioRefusalKind(matchScope);
            if (ratioEvidence) {
                ZipBombObserved observed = poiZipBombObserved(matchScope, counters);
                return new BudgetRefusal("COMPRESSION_RATIO", poiPerEntryLimit(), observed.observed, observed.observedBytes);
            }
            java.util.regex.Matcher m = POI_ZIP_BOMB_SIZES.matcher(matchScope);
            if (m.find()) {
                // same-caliber: the observed measurement for an ENTRY_SIZE refusal IS the expanded
                // byte count at the refusal instant (message-real, per-entry) - no separate byte
                // evidence field, so observedBytes stays null (不适用).
                long uncompressed = Long.parseLong(m.group(1));
                return new BudgetRefusal("ENTRY_SIZE", poiMaxEntrySizeLimit(), uncompressed, null);
            }
            return new BudgetRefusal("ENTRY_SIZE", poiMaxEntrySizeLimit(), UNKNOWN_RATIO, counters.expanded);
        }
        return null;
    }
    /** Observed value for the ratio-kind remap, same-caliber-first: extract the real-time consumed
     *  sizes from POI's message and report BOTH their floor expansion multiple (the same
     *  integer-division convention as the audit's per-entry check) AND the uncompressed byte count
     *  as evidence - two calibers, never mixed. If the message carries no parseable sizes, the
     *  observed value is genuinely unknown: {@code observed} becomes the {@link #UNKNOWN_RATIO}
     *  sentinel (explicit "观测值未知" marker) and {@code observedBytes} degrades to this service's
     *  total expanded-byte counter at the refusal instant, which the refusal message renders as
     *  "观察值 未知（展开 N 字节，...)".
     *  C3-review-04 约束2 - caliber note on the fallback: that counter is the CUMULATIVE expanded
     *  bytes across the WHOLE archive as seen by this service (it includes every other entry already
     *  audited/parsed), NOT the per-entry expanded bytes at POI's refusal instant; the two must not
     *  be equated by consumers. */
    static ZipBombObserved poiZipBombObserved(String message, Counters counters) {
        java.util.regex.Matcher m = POI_ZIP_BOMB_SIZES.matcher(message);
        if (m.find()) {
            long uncompressed = Long.parseLong(m.group(1));
            long compressed = Long.parseLong(m.group(2));
            if (compressed > 0) return new ZipBombObserved(uncompressed / compressed, uncompressed);
        }
        return new ZipBombObserved(UNKNOWN_RATIO, counters.expanded);
    }

    private static org.xml.sax.XMLReader xmlReader(DefaultHandler handler) throws SAXException, javax.xml.parsers.ParserConfigurationException {
        org.xml.sax.XMLReader xml = XMLHelper.newXMLReader();
        xml.setContentHandler(handler);
        try { xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (SAXException ignored) { }
        xml.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return xml;
    }
    private static InputStream counting(InputStream part, Counters counters) { return new CountingInputStream(part, counters); }
    private static String element(String localName, String qName) { return localName == null || localName.isEmpty() ? qName : localName; }

    /** Uncompressed-byte accounting with both absolute and ratio bounds checked on every read.
     *  Package-private for offline white-box tests of the POI refusal remap. */
    static final class Counters {
        final Budgets limits; final long containerBytes; long expanded = 0; long slaveFormulas = 0; long totalCells = 0;
        Counters(Budgets limits, long containerBytes) { this.limits = limits; this.containerBytes = containerBytes; }
        void expanded(long delta) {
            expanded += delta;
            if (expanded > limits.maxExpandedBytes) throw new BudgetRefusal("EXPANDED_BYTES", limits.maxExpandedBytes, expanded);
            long ratioFloor = limits.maxCompressionRatio * Math.max(1L, containerBytes);
            if (expanded > ratioFloor) throw new BudgetRefusal("COMPRESSION_RATIO", limits.maxCompressionRatio, expanded / Math.max(1L, containerBytes));
        }
        /** C3-review F3: the TOTAL_CELLS budget is workbook-wide - a shared counter, checked before the
         *  cell is materialized, so N single-cell sheets with a limit of N-1 refuse the whole import. */
        void cell() {
            if (++totalCells > limits.maxTotalCells) throw new BudgetRefusal("TOTAL_CELLS", limits.maxTotalCells, totalCells);
        }
    }
    /** C3-review F1 audit pass, re-review F1-a/F1-b hardened: expand EVERY zip entry through the
     *  budgeted stream - metadata included - before POI opens the package. The audit reads through
     *  {@link java.util.zip.ZipFile}, the same central-directory view POI's ZipSecureFile uses, so a
     *  prefixed/self-extracting layout that ZipInputStream would miss is still covered; a zero-entry
     *  archive, an unreadable one, or any audit IO failure refuses outright (never handed to POI). On
     *  top of the whole-archive EXPANDED_BYTES / COMPRESSION_RATIO budgets, each entry is checked
     *  against POI's own static min-inflate boundary (read, never written): an entry expanding beyond
     *  POI's per-entry threshold would otherwise make POI refuse mid-parse with an unstructured
     *  "Zip bomb detected", so the audit refuses first with structured kind/limit/observed facts.
     *  No global security setting is read-modified or restored.
     *  C3 re-review F1-b hardening: the per-entry ratio check runs INSIDE the read loop - the
     *  central-directory compressed size is known before the first byte, so a pure bomb is refused
     *  at the crossing chunk instead of after the whole entry is expanded. Same predicate as the
     *  former post-read check (total-compressed-size x limit as the a-priori bound); the semantic
     *  difference vs POI's per-consumed-byte check is deliberate - an entry that MIXES a
     *  high-compression region with a near-1:1 filler still reads below the a-priori bound while
     *  POI's real-time ratio briefly dips under MIN_INFLATE_RATIO mid-entry, so POI's guard
     *  remains the precise backstop for that shape and its refusal is remapped structurally by
     *  {@link #poiZipBombRefusal}. */
    private static void auditZipEntries(File file, Counters counters) throws IllegalArgumentException {
        java.util.zip.ZipFile zip = null;
        try {
            zip = new java.util.zip.ZipFile(file);
            long perEntryLimit = poiPerEntryLimit();
            int entries = 0;
            for (java.util.Enumeration<? extends java.util.zip.ZipEntry> it = zip.entries(); it.hasMoreElements(); ) {
                java.util.zip.ZipEntry entry = it.nextElement();
                if (entry.isDirectory()) continue;
                entries++;
                long compressed = entry.getCompressedSize(); // central-directory value, known before the first byte
                InputStream in = zip.getInputStream(entry);
                long entryExpanded = 0; byte[] buffer = new byte[8192]; int n;
                try {
                    while ((n = in.read(buffer)) != -1) {
                        entryExpanded += n; counters.expanded(n);
                        if (compressed > 0 && entryExpanded > perEntryLimit * compressed)
                            throw new BudgetRefusal("COMPRESSION_RATIO", perEntryLimit, entryExpanded / Math.max(1L, compressed));
                    }
                } finally { in.close(); }
            }
            require(entries >= 1, "IMPORT_INVALID: 压缩包没有任何可读条目");
        } catch (IllegalArgumentException e) { throw e; }
        catch (Exception e) { throw new IllegalArgumentException("IMPORT_INVALID: 压缩包审计失败，拒绝导入", e); }
        finally { if (zip != null) try { zip.close(); } catch (IOException ignored) { } }
    }
    private static final class CountingInputStream extends FilterInputStream {
        private final Counters counters;
        CountingInputStream(InputStream in, Counters counters) { super(in); this.counters = counters; }
        @Override public int read() throws IOException { int n = super.read(); if (n >= 0) counters.expanded(1); return n; }
        @Override public int read(byte[] b, int off, int len) throws IOException { int n = super.read(b, off, len); if (n > 0) counters.expanded(n); return n; }
    }

    /** SAX scan of workbook.xml for the 1904 date-system flag (workbookPr@date1904). */
    private static final class WorkbookPrScan extends DefaultHandler {
        boolean date1904 = false;
        @Override public void startElement(String uri, String localName, String qName, Attributes attrs) {
            if ("workbookPr".equals(element(localName, qName))) {
                String flag = attrs.getValue("date1904");
                date1904 = "1".equals(flag) || "true".equals(flag);
            }
        }
    }
    private static boolean scanWorkbookPr(XSSFReader reader, Counters counters) throws Exception {
        InputStream part = reader.getWorkbookData();
        if (part == null) return false;
        try {
            WorkbookPrScan handler = new WorkbookPrScan();
            xmlReader(handler).parse(new InputSource(counting(part, counters)));
            return handler.date1904;
        } finally { part.close(); }
    }

    /** Minimal styles model needed for date detection: cellXfs order -> numFmtId, plus custom numFmts. */
    private static final class Styles {
        final List<Integer> cellXfNumFmtIds = new ArrayList<>();
        final Map<Integer, String> customFormats = new HashMap<>();
        private Boolean[] dateCache;
        boolean isDateStyle(int styleIndex) {
            if (styleIndex < 0 || styleIndex >= cellXfNumFmtIds.size()) return false; // C3-review F4: index 0 is a real xf (numFmtId 0 is General and stays non-date, a date numFmtId must convert)
            if (dateCache == null) dateCache = new Boolean[cellXfNumFmtIds.size()];
            Boolean cached = dateCache[styleIndex];
            if (cached != null) return cached;
            int numFmtId = cellXfNumFmtIds.get(styleIndex);
            boolean date = numFmtId != 0 && DateUtil.isADateFormat(numFmtId, customFormats.containsKey(numFmtId) ? customFormats.get(numFmtId) : "");
            dateCache[styleIndex] = date;
            return date;
        }
    }
    /**
     * Part lookup by content type. POI 4.1.2's {@code XSSFReader.getStylesData()}/{@code getSharedStringsData()}
     * NPE when the part is absent, so the scanner resolves the parts itself; a missing styles or
     * sharedStrings part is legal (empty model).
     */
    private static InputStream partOf(OPCPackage pkg, org.apache.poi.xssf.usermodel.XSSFRelation relation) throws Exception {
        java.util.ArrayList<org.apache.poi.openxml4j.opc.PackagePart> parts = pkg.getPartsByContentType(relation.getContentType());
        return parts.isEmpty() ? null : parts.get(0).getInputStream();
    }
    private static Styles scanStyles(OPCPackage pkg, Counters counters) throws Exception {
        Styles styles = new Styles();
        InputStream part = partOf(pkg, org.apache.poi.xssf.usermodel.XSSFRelation.STYLES);
        if (part == null) return styles;
        try {
            DefaultHandler handler = new DefaultHandler() {
                boolean inCellXfs = false;
                @Override public void startElement(String uri, String localName, String qName, Attributes attrs) {
                    String el = element(localName, qName);
                    if ("numFmt".equals(el)) {
                        try { styles.customFormats.put(Integer.parseInt(attrs.getValue("numFmtId")), attrs.getValue("formatCode") == null ? "" : attrs.getValue("formatCode")); }
                        catch (NumberFormatException ignored) { }
                    } else if ("cellXfs".equals(el)) {
                        inCellXfs = true;
                    } else if ("xf".equals(el) && inCellXfs) {
                        String id = attrs.getValue("numFmtId");
                        try { styles.cellXfNumFmtIds.add(id == null ? 0 : Integer.parseInt(id)); }
                        catch (NumberFormatException e) { styles.cellXfNumFmtIds.add(0); }
                    }
                }
                @Override public void endElement(String uri, String localName, String qName) {
                    if ("cellXfs".equals(element(localName, qName))) inCellXfs = false;
                }
            };
            xmlReader(handler).parse(new InputSource(counting(part, counters)));
        } finally { part.close(); }
        return styles;
    }

    /** Budgeted SAX loader for xl/sharedStrings.xml implementing POI's {@link SharedStrings} read view. */
    private static final class BoundedSharedStrings extends DefaultHandler implements SharedStrings {
        final List<String> items = new ArrayList<>();
        final Budgets limits;
        long totalChars = 0;
        StringBuilder current; boolean inItem; boolean inText; int phoneticDepth;
        BoundedSharedStrings(Budgets limits) { this.limits = limits; }
        @Override public void startElement(String uri, String localName, String qName, Attributes attrs) {
            String el = element(localName, qName);
            if ("si".equals(el)) { inItem = true; current = new StringBuilder(); }
            else if ("rPh".equals(el)) phoneticDepth++;
            else if ("t".equals(el) && inItem && phoneticDepth == 0) inText = true;
        }
        @Override public void characters(char[] ch, int start, int length) { if (inText && current != null) current.append(ch, start, length); }
        @Override public void endElement(String uri, String localName, String qName) {
            String el = element(localName, qName);
            if ("t".equals(el)) inText = false;
            else if ("rPh".equals(el)) phoneticDepth--;
            else if ("si".equals(el)) {
                String text = current.toString();
                inItem = false; current = null;
                items.add(text);
                totalChars += text.length();
                if (items.size() > limits.maxSharedStrings) throw new BudgetRefusal("SHARED_STRINGS", limits.maxSharedStrings, items.size());
                if (totalChars > limits.maxSharedStringChars) throw new BudgetRefusal("SHARED_STRING_CHARS", limits.maxSharedStringChars, totalChars);
            }
        }
        @Override public RichTextString getItemAt(int index) {
            require(index >= 0 && index < items.size(), "IMPORT_INVALID: 共享字符串索引越界 " + index);
            return new XSSFRichTextString(items.get(index));
        }
        @Override public int getCount() { return items.size(); }
        @Override public int getUniqueCount() { return items.size(); }
    }
    private static SharedStrings scanSharedStrings(OPCPackage pkg, Counters counters) throws Exception {
        InputStream part = partOf(pkg, org.apache.poi.xssf.usermodel.XSSFRelation.SHARED_STRINGS);
        if (part == null) return new BoundedSharedStrings(counters.limits);
        BoundedSharedStrings strings = new BoundedSharedStrings(counters.limits);
        try { xmlReader(strings).parse(new InputSource(counting(part, counters))); }
        finally { part.close(); }
        return strings;
    }

    /** One sheet's SAX scan producing cellData plus metadata. */
    private static final class SheetScan extends DefaultHandler {
        final Styles styles; final SharedStrings strings; final boolean date1904; final Budgets limits; final Counters counters;
        final String sheetId; final String name; final int sheetIndex;
        final ObjectNode sheetNode; final ObjectNode cellData; final ObjectNode stats;
        int cellCount = 0; int slaveFormulas = 0; int maxRow = -1; int maxCol = -1;

        int currentRow = -1; int lastRowWritten = -1; int lastColumn = -1; boolean rowOpen = false;
        // per-cell state
        int cellRow; int cellColumn; String cellType = "n"; int styleIndex;
        final StringBuilder value = new StringBuilder(); final StringBuilder formula = new StringBuilder(); final StringBuilder inline = new StringBuilder();
        boolean inValue; boolean inFormula; boolean inInlineText; int inlinePhonetic;
        String formulaType; boolean sawFormula;

        SheetScan(Styles styles, SharedStrings strings, boolean date1904, Budgets limits, Counters counters, String name, int sheetIndex) {
            this.styles = styles; this.strings = strings; this.date1904 = date1904; this.limits = limits; this.counters = counters;
            this.name = name == null || name.trim().isEmpty() ? "Sheet" + (sheetIndex + 1) : name;
            this.sheetIndex = sheetIndex; this.sheetId = "sheet-" + sheetIndex;
            this.cellData = obj();
            this.sheetNode = obj().put("id", sheetId).put("name", this.name);
            this.stats = obj().put("index", sheetIndex).put("sheetId", sheetId).put("name", this.name);
        }
        @Override public void startElement(String uri, String localName, String qName, Attributes attrs) {
            String el = element(localName, qName);
            if ("row".equals(el)) {
                int row = lastRowWritten + 1;
                String ref = attrs.getValue("r");
                if (ref != null && !ref.trim().isEmpty()) {
                    try { row = Integer.parseInt(ref.trim()) - 1; }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("IMPORT_INVALID: 非法行号 " + ref, e); }
                }
                require(row >= 0, "IMPORT_INVALID: 非法行号");
                if (row >= limits.maxRows) throw new BudgetRefusal("ROWS", limits.maxRows, row + 1L);
                currentRow = row; lastRowWritten = row; lastColumn = -1; rowOpen = true;
            } else if ("c".equals(el)) {
                int row = currentRow, column = lastColumn + 1;
                String ref = attrs.getValue("r");
                if (ref != null && !ref.trim().isEmpty()) {
                    int[] coords = coordinates(ref.trim());
                    row = coords[0]; column = coords[1];
                }
                require(row >= 0, "IMPORT_INVALID: 单元格缺少有效行上下文");
                if (row >= limits.maxRows) throw new BudgetRefusal("ROWS", limits.maxRows, row + 1L);
                if (column < 0 || column >= limits.maxColumns) throw new BudgetRefusal("COLUMNS", limits.maxColumns, column + 1L);
                cellRow = row; cellColumn = column; lastColumn = column;
                String type = attrs.getValue("t");
                cellType = type == null || type.trim().isEmpty() ? "n" : type.trim();
                String style = attrs.getValue("s");
                try { styleIndex = style == null ? 0 : Integer.parseInt(style); }
                catch (NumberFormatException e) { styleIndex = 0; }
                value.setLength(0); formula.setLength(0); inline.setLength(0);
                inValue = inFormula = inInlineText = sawFormula = false; inlinePhonetic = 0; formulaType = null;
            } else if ("v".equals(el)) {
                inValue = true;
            } else if ("f".equals(el)) {
                inFormula = true; sawFormula = true; formulaType = attrs.getValue("t");
            } else if ("is".equals(el)) {
                inline.setLength(0); inlinePhonetic = 0;
            } else if ("rPh".equals(el)) {
                inlinePhonetic++;
            } else if ("t".equals(el)) {
                if (!inValue && !inFormula && inlinePhonetic == 0 && (cellType.equals("inlineStr"))) inInlineText = true;
            }
        }
        @Override public void characters(char[] ch, int start, int length) {
            if (inValue) value.append(ch, start, length);
            if (inFormula) formula.append(ch, start, length);
            if (inInlineText) inline.append(ch, start, length);
        }
        @Override public void endElement(String uri, String localName, String qName) {
            String el = element(localName, qName);
            if ("v".equals(el)) inValue = false;
            else if ("f".equals(el)) inFormula = false;
            else if ("t".equals(el)) inInlineText = false;
            else if ("rPh".equals(el)) inlinePhonetic--;
            else if ("c".equals(el)) emitCell();
            else if ("row".equals(el)) rowOpen = false;
        }
        private void emitCell() {
            String vText = value.toString().trim();
            String fText = formula.toString().trim();
            boolean sharedSlave = sawFormula && "shared".equals(formulaType) && fText.isEmpty();
            if (sharedSlave) slaveFormulas++;
            ObjectNode cell = obj();
            JsonNode typed = typedValue(vText);
            if (typed != null) { cell.set("v", typed); cell.put("t", typeCode); }
            if (!fText.isEmpty() && !sharedSlave) cell.put("f", normalizeFormula(fText));
            if (cell.size() == 0) return; // style-only/empty cell carries no cellData entry
            cellCount++;
            counters.cell(); // C3-review F3: workbook-wide budget on the shared counter
            cellData.with(String.valueOf(cellRow)).set(String.valueOf(cellColumn), cell);
            if (cellRow > maxRow) maxRow = cellRow;
            if (cellColumn > maxCol) maxCol = cellColumn;
        }
        /** cellData model type code for the current conversion (1 string / 2 number / 3 boolean). */
        private int typeCode = 2;
        /** Precise per-type conversion; null means "no value present in the file". */
        private JsonNode typedValue(String vText) {
            if (cellType.equals("s")) {
                if (vText.isEmpty()) return null;
                int index;
                try { index = Integer.parseInt(vText); }
                catch (NumberFormatException e) { throw new IllegalArgumentException("IMPORT_INVALID: 非法共享字符串索引 " + vText, e); }
                String text = strings.getItemAt(index).toString();
                if (text.length() > limits.maxCellTextChars) throw new BudgetRefusal("CELL_TEXT", limits.maxCellTextChars, text.length());
                typeCode = 1;
                return textNode(text);
            }
            if (cellType.equals("inlineStr") || cellType.equals("str") || cellType.equals("e")) {
                String text = cellType.equals("inlineStr") ? inline.toString() : vText;
                if (text.isEmpty()) return null;
                if (text.length() > limits.maxCellTextChars) throw new BudgetRefusal("CELL_TEXT", limits.maxCellTextChars, text.length());
                typeCode = 1;
                return textNode(text);
            }
            if (cellType.equals("b")) {
                if (vText.isEmpty()) return null;
                if (vText.equals("1") || vText.equalsIgnoreCase("true")) { typeCode = 3; return BooleanNode.TRUE; }
                if (vText.equals("0") || vText.equalsIgnoreCase("false")) { typeCode = 3; return BooleanNode.FALSE; }
                throw new IllegalArgumentException("IMPORT_INVALID: 非法布尔值 " + vText);
            }
            if (cellType.equals("d")) { // ISO-8601 date literal (rare): normalize to a 1900-basis serial
                if (vText.isEmpty()) return null;
                LocalDateTime at = LocalDateTime.parse(vText, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                typeCode = 2;
                return DecimalNode.valueOf(BigDecimal.valueOf(DateUtil.getExcelDate(at, false)));
            }
            // default numeric (t="n" or absent)
            if (vText.isEmpty()) return null;
            BigDecimal number;
            try { number = new BigDecimal(vText); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("IMPORT_INVALID: 非法数值字面量 " + vText, e); }
            if (styles.isDateStyle(styleIndex) && date1904) {
                // Date-formatted numeric in a 1904-system workbook: canonicalize into the 1900 basis
                // (Excel's fake 1900-02-29 leap day makes the constant 1462 exact for every date from
                // 1900-03-01 on; the time fraction rides in the decimal part untouched).
                number = number.add(BigDecimal.valueOf(DAYS_1904_TO_1900));
            }
            typeCode = 2;
            return DecimalNode.valueOf(wholeFractionNormalized(number));
        }
        ObjectNode finish() {
            sheetNode.put("rowCount", maxRow + 1).put("columnCount", maxCol + 1);
            sheetNode.set("cellData", cellData);
            stats.put("rowCount", maxRow + 1).put("columnCount", maxCol + 1).put("cellCount", cellCount);
            return sheetNode;
        }
    }
    private static JsonNode textNode(String text) { return com.fasterxml.jackson.databind.node.TextNode.valueOf(text); }
    /** Numeric literals keep the file's exact digits: fold exponent notation into plain whole+fraction
     *  form (a negative scale would resurface as E-notation and break round-trip equality, e.g. "123")
     *  and trim only fraction trailing zeros ("1.230" -> "1.23"); precision-bearing literals such as
     *  "1.234567890123456789012345678901" and "0.30000000000000004" survive digit-for-digit. */
    private static BigDecimal wholeFractionNormalized(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
    /** Formulas import verbatim: normalized to the model's leading '=' convention, never evaluated. */
    private static String normalizeFormula(String raw) { return raw.startsWith("=") ? raw : "=" + raw; }
    /** "A1" -> {row 0, column 0}; malformed refs refuse the import. */
    private static int[] coordinates(String ref) {
        int split = 0;
        while (split < ref.length() && Character.isLetter(ref.charAt(split))) split++;
        require(split > 0 && split < ref.length(), "IMPORT_INVALID: 非法单元格坐标 " + ref);
        String letters = ref.substring(0, split).toUpperCase(Locale.ROOT);
        long column = 0;
        for (char c : letters.toCharArray()) { column = column * 26 + (c - 'A' + 1); }
        column--;
        require(column >= 0 && column <= MAX_COLUMNS, "IMPORT_INVALID: 非法单元格坐标 " + ref); // bound itself is the caller's budget kind
        int row;
        try { row = Integer.parseInt(ref.substring(split)) - 1; }
        catch (NumberFormatException e) { throw new IllegalArgumentException("IMPORT_INVALID: 非法单元格坐标 " + ref, e); }
        return new int[]{row, (int) column};
    }

    // ------------------------------------------------------------------
    // job lane: durable state machine + hash-authorized bounded preview
    // ------------------------------------------------------------------
    /** Create (or idempotently replay) one import job: record PARSING, parse outside any transaction, materialize atomically. */
    public ObjectNode create(ObjectNode request, long userId, byte[] source) {
        require(enabled(), "AI_CAPABILITY_BLOCKED: 工作簿导入未开放");
        String requestId = text(request, "requestId");
        require(requestId.length() <= 80, "IMPORT_REQUEST_INVALID: 请求ID过长");
        String fileName = request.path("fileName").asText("");
        if (fileName.trim().isEmpty()) fileName = "import.xlsx";
        if (fileName.length() > 255) fileName = fileName.substring(0, 255);
        require(source != null && source.length > 0, "IMPORT_SOURCE_REQUIRED: 上传内容为空");
        if (source.length > MAX_SOURCE_BYTES) throw new BudgetRefusal("SOURCE_BYTES", MAX_SOURCE_BYTES, source.length);
        String contentHash = sha256Hex(source);
        final String displayName = fileName; // effectively-final copy for the lambda below
        final long byteSize = source.length;
        String requestHash = WriteManifest.digest(obj().put("requestId", requestId).put("fileName", fileName).put("sourceContentHash", contentHash));
        String jobId = UUID.randomUUID().toString();
        // Record-before-parse: the job row exists (and replays idempotently) before any byte is parsed.
        ObjectNode existing = tx.execute(status -> {
            List<Map<String, Object>> rows = db.queryForList("select * from ai_workbook_import_job where user_id=? and request_id=?", userId, requestId);
            if (!rows.isEmpty()) {
                require(requestHash.equals(String.valueOf(rows.get(0).get("request_hash"))), "IMPORT_IDEMPOTENCY_CONFLICT: 相同请求ID参数不同");
                return rowView(rows.get(0));
            }
            db.update("insert into ai_workbook_import_job(job_id,user_id,request_id,request_hash,source_name,source_content_hash,source_byte_size,status,state_history_json,created_at,updated_at) values(?,?,?,?,?,?,?,'PARSING',?,now(3),now(3))",
                jobId, userId, requestId, requestHash, displayName, contentHash, byteSize, arr().add("PARSING").toString());
            return null;
        });
        if (existing != null) return existing;
        JsonNode parsed;
        try {
            parsed = parse(source, Budgets.defaults());
        } catch (IllegalArgumentException refusal) {
            fail(jobId, "PARSE", refusal);
            throw refusal;
        }
        try {
            ArrayNode flatRows = flatten(parsed);
            JsonNode stats = parsed.path("stats");
            String statsJson = stats.toString();
            int sheetCount = stats.path("sheetCount").asInt();
            int totalCells = stats.path("totalCells").asInt();
            return tx.execute(status -> {
                transition(jobId, "MATERIALIZING");
                // One transaction materializes the whole parsed workbook through the C1 chunk lane;
                // any failure rolls the artifact back leaving zero residue.
                // workbook_id/branch_id are primitive longs on the artifact lane; 0 marks "no workbook
                // binding" for imports (schema_name=workbook_import never registers a dependency row).
                ObjectNode meta = artifacts.writeArtifact(jobId, userId, 0L, 0L, SCHEMA_NAME, contentHash, flatRows, null);
                int recorded = db.queryForObject("select row_count from ai_analysis_artifact where artifact_id=?", Integer.class, meta.path("id").asText());
                require(recorded == flatRows.size(), "IMPORT_MATERIALIZE_INVALID: 分块物化行数与解析结果不符");
                require(db.update("update ai_workbook_import_job set status='SUCCEEDED',artifact_id=?,sheet_count=?,total_cells=?,parse_stats_json=?,updated_at=now(3) where job_id=? and status='MATERIALIZING'",
                    meta.path("id").asText(), sheetCount, totalCells, statsJson, jobId) == 1, "IMPORT_STATE_CONFLICT");
                appendHistory(jobId, "SUCCEEDED");
                return rowView(jobRow(jobId));
            });
        } catch (IllegalArgumentException refusal) {
            fail(jobId, "MATERIALIZING", refusal);
            throw refusal;
        }
    }
    /** Flatten the parsed snapshot into row records for the chunk lane: {sheetId,sheetIndex,rowIndex,cells}. */
    private static ArrayNode flatten(JsonNode parsed) {
        ArrayNode rows = arr();
        JsonNode sheets = parsed.path("sheets");
        int index = 0;
        for (JsonNode sheetId : parsed.path("sheetOrder")) {
            JsonNode cellData = sheets.path(sheetId.asText()).path("cellData");
            List<Integer> rowKeys = new ArrayList<>();
            cellData.fieldNames().forEachRemaining(k -> rowKeys.add(Integer.parseInt(k)));
            Collections.sort(rowKeys);
            for (Integer rowKey : rowKeys) {
                ObjectNode record = obj().put("sheetId", sheetId.asText()).put("sheetIndex", index).put("rowIndex", rowKey);
                record.set("cells", cellData.path(rowKey.toString()).deepCopy());
                rows.add(record);
            }
            index++;
        }
        return rows;
    }
    private void transition(String jobId, String status) {
        require(db.update("update ai_workbook_import_job set status=?,updated_at=now(3) where job_id=?", status, jobId) == 1, "IMPORT_STATE_CONFLICT");
        appendHistory(jobId, status);
    }
    private void appendHistory(String jobId, String status) {
        String current = db.queryForObject("select state_history_json from ai_workbook_import_job where job_id=?", String.class, jobId);
        ArrayNode history = (ArrayNode) AgentJson.parse(current);
        history.add(status);
        db.update("update ai_workbook_import_job set state_history_json=? where job_id=?", history.toString(), jobId);
    }
    /** Record the refusal on the job row in its own short transaction; never masks the original error. */
    private void fail(String jobId, String phase, IllegalArgumentException error) {
        String message = error.getMessage() == null ? "IMPORT_INVALID" : error.getMessage();
        String code = message.split(":")[0].trim();
        ObjectNode detail = obj().put("message", message.length() > 480 ? message.substring(0, 480) : message);
        if (error instanceof BudgetRefusal) {
            BudgetRefusal budget = (BudgetRefusal) error;
            detail.put("kind", budget.kind).put("limit", budget.limit).put("observed", budget.observed);
            // C3-review-04 (P2): fourth fact only when present - byte evidence is a different caliber
            // than the ratio limit and only the POI mid-parse remap carries it; the three legacy keys
            // stay exactly as-is for every existing consumer (query-side failDetail passthrough).
            if (budget.observedBytes != null) detail.put("observedBytes", budget.observedBytes);
        }
        try {
            tx.execute(status -> {
                db.update("update ai_workbook_import_job set status='FAILED',fail_code=?,fail_phase=?,fail_message=?,fail_detail_json=?,updated_at=now(3) where job_id=? and status not in ('SUCCEEDED','FAILED')",
                    code, phase, message.length() > 480 ? message.substring(0, 480) : message, detail.toString(), jobId);
                appendHistory(jobId, "FAILED");
                return null;
            });
        } catch (Exception traceFailure) {
            org.slf4j.LoggerFactory.getLogger(ExcelImportService.class).warn("C3 import job {} failure trace lost: {}", jobId, traceFailure.getMessage());
        }
    }
    private Map<String, Object> jobRow(String jobId) {
        return db.queryForMap("select * from ai_workbook_import_job where job_id=?", jobId);
    }
    private Map<String, Object> ownedRow(String jobId, long userId) {
        require(jobId != null && !jobId.trim().isEmpty(), "IMPORT_ID_REQUIRED: 缺少任务标识");
        List<Map<String, Object>> rows = db.queryForList("select * from ai_workbook_import_job where job_id=?", jobId);
        require(!rows.isEmpty() && ((Number) rows.get(0).get("user_id")).longValue() == userId, "IMPORT_FORBIDDEN: 任务不存在或仅任务所有者可访问");
        return rows.get(0);
    }
    public ObjectNode view(String jobId, long userId) { return rowView(ownedRow(jobId, userId)); }
    public ObjectNode findByRequestId(String requestId, long userId) {
        List<Map<String, Object>> rows = db.queryForList("select * from ai_workbook_import_job where user_id=? and request_id=?", userId, requestId);
        require(!rows.isEmpty(), "IMPORT_NOT_FOUND: 该请求尚无导入任务");
        return rowView(rows.get(0));
    }
    private ObjectNode rowView(Map<String, Object> row) {
        ObjectNode out = obj().put("jobId", String.valueOf(row.get("job_id"))).put("userId", ((Number) row.get("user_id")).longValue())
            .put("requestId", String.valueOf(row.get("request_id"))).put("status", String.valueOf(row.get("status")))
            .put("sourceName", String.valueOf(row.get("source_name"))).put("sourceContentHash", String.valueOf(row.get("source_content_hash")))
            .put("sourceByteSize", ((Number) row.get("source_byte_size")).longValue())
            .put("createdAt", epoch(row.get("created_at"))).put("updatedAt", epoch(row.get("updated_at")));
        nullable(out, "artifactId", row.get("artifact_id"));
        Object sheetCount = row.get("sheet_count"); if (sheetCount == null) out.putNull("sheetCount"); else out.put("sheetCount", ((Number) sheetCount).intValue());
        Object totalCells = row.get("total_cells"); if (totalCells == null) out.putNull("totalCells"); else out.put("totalCells", ((Number) totalCells).intValue());
        nullable(out, "failCode", row.get("fail_code"));
        nullable(out, "failPhase", row.get("fail_phase"));
        Object failMessage = row.get("fail_message"); if (failMessage == null) out.putNull("failMessage"); else out.put("failMessage", String.valueOf(failMessage));
        Object failDetail = row.get("fail_detail_json"); if (failDetail == null) out.putNull("failDetail"); else out.set("failDetail", AgentJson.parse(String.valueOf(failDetail)));
        Object stats = row.get("parse_stats_json"); if (stats == null) out.putNull("parseStats"); else out.set("parseStats", AgentJson.parse(String.valueOf(stats)));
        out.set("stateHistory", AgentJson.parse(String.valueOf(row.get("state_history_json"))));
        return out;
    }
    private static void nullable(ObjectNode out, String field, Object column) { if (column == null) out.putNull(field); else out.put(field, String.valueOf(column)); }
    private static long epoch(Object datetime) {
        if (datetime == null) return 0L;
        if (datetime instanceof java.util.Date) return ((java.util.Date) datetime).getTime();
        // JDBC 4.2 drivers hand DATETIME back as LocalDateTime (JDK 8 has it); interpret in the
        // server timezone the connection was opened with (URL pins serverTimezone).
        if (datetime instanceof java.time.LocalDateTime)
            return ((java.time.LocalDateTime) datetime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        return java.sql.Timestamp.valueOf(String.valueOf(datetime)).getTime();
    }
    /**
     * Bounded preview: metadata plus at most {@code rows} (1..{@link #MAX_PREVIEW_ROWS}) row records
     * from the first verified chunk. The response never carries the full source, states limited=true
     * and fullSourceCoverage=false, and requires the sha256 of the uploaded bytes as the access
     * credential - a file path is accepted nowhere.
     */
    public ObjectNode preview(String jobId, long userId, String sourceHash, int rows) {
        require(rows >= 1 && rows <= MAX_PREVIEW_ROWS, "IMPORT_PREVIEW_INVALID: rows 必须在 1 到 " + MAX_PREVIEW_ROWS + " 之间");
        Map<String, Object> row = ownedRow(jobId, userId);
        require("SUCCEEDED".equals(String.valueOf(row.get("status"))), "IMPORT_NOT_READY: 任务尚未成功，无法预览");
        String recorded = String.valueOf(row.get("source_content_hash"));
        require(sourceHash != null && recorded.equals(sourceHash), "IMPORT_HASH_MISMATCH: sourceHash 与该任务授权的上传内容不符");
        String artifactId = String.valueOf(row.get("artifact_id"));
        // C3-review F2 + re-review F6: the chunk blob stores exact decimal literals, but the default
        // chunk parse materializes JSON numbers as DoubleNode, silently re-rounding 29-digit literals on
        // the way out. The preview therefore reads through the C1 readChunkPage precision overload -
        // the SAME unified gate, full chunk-sequence/slice/hash/row-count integrity checks (a rewritten
        // empty chunk, a broken slice or a missing tail chunk refuse exactly as the C1 lane does) - with
        // only the blob parse switched to floats-as-BigDecimal so the precision contract holds across
        // upload -> materialize -> read -> respond. A direct fetch here would have bypassed the sequence
        // checks; the overload keeps C1's guarantees and the parse swappable in one place.
        ObjectNode page = artifacts.readChunkPage(artifactId, userId, null, PRECISE_JSON);
        JsonNode pageRows = page.path("rows");
        int totalRowCount = db.queryForObject("select row_count from ai_analysis_artifact where artifact_id=?", Integer.class, artifactId);
        ArrayNode limited = arr();
        for (int i = 0; i < Math.min(rows, pageRows.size()); i++) limited.add(pageRows.get(i));
        ObjectNode out = obj().put("jobId", jobId).put("sourceHash", recorded)
            .put("sheetCount", ((Number) row.get("sheet_count")).intValue())
            .put("totalRowCount", totalRowCount).put("requestedRows", rows).put("returnedRows", limited.size())
            .put("limited", true).put("fullSourceCoverage", false)
            .put("note", "预览仅返回有限行与元信息，不承诺覆盖全量源工作簿");
        Object stats = row.get("parse_stats_json");
        out.set("sheets", stats == null ? arr() : AgentJson.parse(String.valueOf(stats)).path("sheets").deepCopy());
        out.set("rows", limited);
        return out;
    }
    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(bytes);
            StringBuilder hex = new StringBuilder(out.length * 2);
            for (byte b : out) hex.append(String.format("%02x", b & 0xff));
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法计算导入内容哈希", e);
        }
    }
}
