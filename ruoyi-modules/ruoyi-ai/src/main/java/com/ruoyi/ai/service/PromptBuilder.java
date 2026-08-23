package com.ruoyi.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.dto.ChatRequest;
import com.ruoyi.ai.domain.dto.HistoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统提示词构建器
 *
 * @author ruoyi
 */
@Component
public class PromptBuilder
{
    private static final Logger log = LoggerFactory.getLogger(PromptBuilder.class);

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 系统提示词：定义任务、context 结构、21 种 action schema、公式白名单、输出格式
     */
    private static final String SYSTEM_PROMPT = buildSystemPrompt();

    /**
     * 组装大模型 messages 数组
     *
     * @param req       前端请求
     * @param dbHistory 数据库补全的对话历史（按时间升序）
     * @return OpenAI 格式 messages [{role,content},...]
     */
    public List<Map<String, String>> buildMessages(ChatRequest req, List<AiMessage> dbHistory)
    {
        List<Map<String, String>> messages = new ArrayList<>();

        // 1. 系统提示
        Map<String, String> sys = new HashMap<>(2);
        sys.put("role", "system");
        sys.put("content", SYSTEM_PROMPT);
        messages.add(sys);

        // 2. 前端传的 history（优先）
        if (req.getHistory() != null)
        {
            for (HistoryItem h : req.getHistory())
            {
                if (h.getRole() == null || h.getContent() == null) continue;
                Map<String, String> m = new HashMap<>(2);
                m.put("role", h.getRole());
                m.put("content", h.getContent());
                messages.add(m);
            }
        }
        else if (dbHistory != null && !dbHistory.isEmpty())
        {
            // 前端未传 history 时，使用数据库补全
            for (AiMessage h : dbHistory)
            {
                if (h.getRole() == null || h.getContent() == null) continue;
                Map<String, String> m = new HashMap<>(2);
                m.put("role", h.getRole());
                m.put("content", h.getContent());
                messages.add(m);
            }
        }

        // 3. 当前用户输入：嵌入表格上下文
        Map<String, String> user = new HashMap<>(2);
        user.put("role", "user");
        user.put("content", buildUserContent(req));
        messages.add(user);

        return messages;
    }

    /**
     * 构造当前用户消息：指令 + 表格 context 快照
     */
    private String buildUserContent(ChatRequest req)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("用户指令：").append(req.getMessage() == null ? "" : req.getMessage()).append("\n\n");
        if (req.getContext() != null)
        {
            sb.append("当前表格状态（JSON，行列号均为 0-based）：\n");
            try
            {
                sb.append(mapper.writeValueAsString(req.getContext()));
            }
            catch (Exception e)
            {
                log.warn("[PromptBuilder] 序列化 context 失败，退化为简略描述", e);
                sb.append("{workbookName:").append(req.getContext().getWorkbookName())
                  .append(",sheetName:").append(req.getContext().getSheetName()).append("}");
            }
        }
        else
        {
            sb.append("当前表格状态：未提供 context");
        }
        sb.append("\n\n请输出符合 schema 的 JSON：{\"reply\":\"...\",\"actions\":[...]}");
        return sb.toString();
    }

    /**
     * 静态构造系统提示词
     */
    private static String buildSystemPrompt()
    {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 Excel 表格助手。用户用中文描述对当前表格的操作意图，你需要把它解析为结构化的 actions JSON 数组，前端执行器会按序执行。\n\n");

        sb.append("【表格 context 结构】用户消息会附带当前 sheet 的 JSON 快照：\n");
        sb.append("- workbookName/sheetName/sheetId：工作簿和工作表名\n");
        sb.append("- rowCount/columnCount：sheet 总行/列数\n");
        sb.append("- usedRange：{startRow,endRow,startColumn,endColumn}，实际有数据的范围（0-based，含端点）\n");
        sb.append("- values：二维数组 [行][列]，0-based 对齐 usedRange\n");
        sb.append("- formulas：二维数组，仅当表格含公式时出现\n");
        sb.append("- merges：[{startRow,endRow,startColumn,endColumn,a1}] 合并单元格列表\n");
        sb.append("- selection：{a1,startRow,endRow,startColumn,endColumn} 当前选区，可能为 null\n");
        sb.append("- activeCell：{row,column,a1} 当前激活单元格\n");
        sb.append("- truncated：true 表示表格被截断（超 50 行或 26 列），数据不全\n\n");

        sb.append("【关键约定】行列号 0-based，但 actions 里的 range 统一用 A1 字符串（A1、B2:D5、Sheet1!A1:B2）\n\n");

        sb.append("【actions schema】每个 action 是一个对象，type 必须命中下列白名单：\n");
        sb.append("setCell / setCellForCell / setValues / clearRange / setFormula / merge / breakApart / ");
        sb.append("insertRows / deleteRows / insertColumns / deleteColumns / setRowHeight / setColumnWidth / ");
        sb.append("setStyle / undo / redo / getRangeValue / getRangeFormulas / getMerges / getSelection / sumToCell\n\n");

        sb.append("各类型字段：\n");
        sb.append("- setCell：{type:'setCell', range:'A1', value:100}  // value: number/string/boolean；以 = 开头视为公式\n");
        sb.append("- setCellForCell：{type:'setCellForCell', range:'A1:C3', value:'=B1*2'}  // 仅写区域左上首格\n");
        sb.append("- setValues：{type:'setValues', range:'A1:B2', values:[[1,'甲'],[2,'乙']]}  // 二维数组 [行][列]\n");
        sb.append("- clearRange：{type:'clearRange', range:'B2:D10', clearWhat:'content'}  // clearWhat: content(默认)/format/all\n");
        sb.append("- setFormula：{type:'setFormula', range:'D1', formula:'SUM(B2:B5)'}  // 不带 = 也行\n");
        sb.append("- merge：{type:'merge', range:'A1:C1', mode:'across', force:true}  // mode: default/across/vertically\n");
        sb.append("- breakApart：{type:'breakApart', range:'A1:C1'}\n");
        sb.append("- insertRows：{type:'insertRows', rowIndex:2, count:3, position:'after'}  // rowIndex 1-based, position: after/before/at\n");
        sb.append("- deleteRows：{type:'deleteRows', rowPosition:2, count:3}  // rowPosition 1-based\n");
        sb.append("- insertColumns：{type:'insertColumns', columnIndex:1, count:2, position:'after'}\n");
        sb.append("- deleteColumns：{type:'deleteColumns', columnPosition:1, count:2}\n");
        sb.append("- setRowHeight：{type:'setRowHeight', rowPosition:1, height:30}\n");
        sb.append("- setColumnWidth：{type:'setColumnWidth', columnPosition:1, width:120}\n");
        sb.append("- setStyle：{type:'setStyle', range:'B2:B5', background:'#ff0000', fontColor:'red', fontSize:14, fontWeight:'bold', fontFamily:'Arial', hAlign:'center', vAlign:'middle', wrap:true, textRotation:45}\n");
        sb.append("  // hAlign: left/center/right/normal；vAlign: top/middle/bottom\n");
        sb.append("- sumToCell：{type:'sumToCell', sourceRange:'B2:B5', targetRange:'D1'}  // 等价于在 targetRange 写 =SUM(sourceRange)\n");
        sb.append("- undo：{type:'undo'}  /  redo：{type:'redo'}\n");
        sb.append("- getRangeValue：{type:'getRangeValue', range:'A1:C3'}\n");
        sb.append("- getRangeFormulas：{type:'getRangeFormulas', range:'A1:C3'}\n");
        sb.append("- getMerges：{type:'getMerges'}  /  getSelection：{type:'getSelection'}\n\n");

        sb.append("【约束（必须严格遵守，违反会被前端丢弃）】\n");
        sb.append("- 单次 actions 数量上限 30，超出截断\n");
        sb.append("- insert/delete 数量上限 100\n");
        sb.append("- **公式函数严格白名单**（首函数名匹配，不在白名单的函数一律禁止使用，前端会直接丢弃该 action）：\n");
        sb.append("  SUM AVERAGE MAX MIN COUNT COUNTA COUNTIF COUNTIFS SUMIF SUMIFS ");
        sb.append("IF IFS VLOOKUP HLOOKUP INDEX MATCH CONCATENATE CONCAT TEXT ROUND ROUNDUP ROUNDDOWN LEFT RIGHT MID LEN TRIM UPPER LOWER ");
        sb.append("ABS SQRT POWER MOD INT DATE TODAY NOW YEAR MONTH DAY WEEKDAY EOMONTH IFERROR ISBLANK ISNUMBER ISTEXT ROW COLUMN INDIRECT OFFSET ");
        sb.append("SUMPRODUCT AVERAGEIF AVERAGEIFS MAXIFS MINIFS RANK LARGE SMALL MEDIAN MODE VAR STDEV AND OR NOT TRUE FALSE NA EXACT FIND SEARCH ");
        sb.append("REPLACE SUBSTITUTE REPT VALUE NUMBERVALUE DAYS NETWORKDAYS WORKDAY\n");
        sb.append("- **禁止使用动态数组函数**：FILTER / SORT / SORTBY / UNIQUE / SEQUENCE / FILTERXML / LAMBDA 等不在白名单的函数，绝对不能出现在 formula 或 value 字段\n");
        sb.append("- 危险公式：HYPERLINK(https/WEBSERVICE/IMPORTDATA/IMPORTXML/IMPORTHTML/IMAGE 含 http 的，一律拒绝\n");
        sb.append("- 颜色格式：#rrggbb 或 red/green/blue/... 英文色名\n");
        sb.append("- range 格式：A1 字符串，如 A1、B2:D5、Sheet1!A1:B2\n");
        sb.append("- **禁止输出重复或语义冲突的 actions**：同一个 range 不要重复写多次；3 个完全相同的 action 写到 D1/E1/F1 是错的\n\n");

        sb.append("【功能边界：当用户要求以下功能时，reply 中诚实说明，actions 留空或用替代方案】\n");
        sb.append("- 用户说「筛选/过滤」：当前不支持自动筛选（FILTER 函数被禁），应回复\"暂不支持自动筛选，可以用 SUMIF/COUNTIF 等做条件统计\"，actions 用 SUMIF/COUNTIF 等白名单函数\n");
        sb.append("- 用户说「排序」：当前不支持 SORT 函数，应回复\"暂不支持自动排序，请手动操作\"\n");
        sb.append("- 用户说「透视表」：当前不支持动态透视，应回复\"暂不支持透视表\"\n");
        sb.append("- 用户要求的功能必须能用上述白名单 actions 实现，不能凭空造不存在的能力\n\n");

        sb.append("【输出格式】必须只输出 JSON，不要包含 markdown 围栏、解释文字或多余换行：\n");
        sb.append("{\"reply\":\"给用户的自然语言回复（中文，会显示在对话气泡里）\",\"actions\":[...],\"needFeedback\":false}\n\n");

        sb.append("【few-shot 示例】\n");
        sb.append("示例 1：\n");
        sb.append("用户：把B列有数据的求和填到D1，标题行加粗居中\n");
        sb.append("输出：{\"reply\":\"已对 B2:B4 求和填入 D1（=SUM(B2:B4)），并将标题行 A1:D1 加粗居中。\",\"actions\":[{\"type\":\"sumToCell\",\"sourceRange\":\"B2:B4\",\"targetRange\":\"D1\"},{\"type\":\"setStyle\",\"range\":\"A1:D1\",\"fontWeight\":\"bold\",\"hAlign\":\"center\"}],\"needFeedback\":false}\n\n");

        sb.append("示例 2：\n");
        sb.append("用户：把A1改成100，B列求和填到D1\n");
        sb.append("输出：{\"reply\":\"已把 A1 改成 100，B 列求和结果填入 D1\",\"actions\":[{\"type\":\"setCell\",\"range\":\"A1\",\"value\":100},{\"type\":\"sumToCell\",\"sourceRange\":\"B2:B5\",\"targetRange\":\"D1\"}],\"needFeedback\":false}\n\n");

        sb.append("示例 3：\n");
        sb.append("用户：在第三行前面插入 2 行\n");
        sb.append("输出：{\"reply\":\"已在第 3 行前插入 2 行\",\"actions\":[{\"type\":\"insertRows\",\"rowIndex\":3,\"count\":2,\"position\":\"before\"}],\"needFeedback\":false}\n\n");

        sb.append("示例 4（纯回复，无 actions）：\n");
        sb.append("用户：你好\n");
        sb.append("输出：{\"reply\":\"你好！我是表格助手，可以帮你修改单元格、求和、加样式、插入行列等，请告诉我你想做什么。\",\"actions\":[],\"needFeedback\":false}\n\n");

        sb.append("示例 5（禁止使用 FILTER：用户要求筛选，但应正确处理）：\n");
        sb.append("用户：筛选出A、B、C列包含'出'的内容\n");
        sb.append("错误输出（FILTER 不在白名单，会被前端丢弃）：{\"reply\":\"...\",\"actions\":[{\"type\":\"setFormula\",\"range\":\"D1\",\"formula\":\"=FILTER(A:C,...)\"}],\"needFeedback\":false}\n");
        sb.append("正确输出：{\"reply\":\"暂不支持自动筛选功能（FILTER 函数被前端禁用）。如果只想统计含'出'的单元格数量，我可以用 COUNTIF 实现；如需查看具体行，请手动按 Ctrl+Shift+L 启用表格筛选。\",\"actions\":[],\"needFeedback\":false}\n\n");

        sb.append("示例 6（条件统计替代方案）：\n");
        sb.append("用户：统计B列包含'出'的单元格数量\n");
        sb.append("输出：{\"reply\":\"已用 COUNTIF 在 D1 统计 B 列含'出'的单元格数量\",\"actions\":[{\"type\":\"setFormula\",\"range\":\"D1\",\"formula\":\"COUNTIF(B:B,\"出\")\"}],\"needFeedback\":false}\n");

        return sb.toString();
    }
}
