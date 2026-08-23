package com.ruoyi.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.ai.domain.AiMessage;
import com.ruoyi.ai.domain.dto.ChatRequest;
import com.ruoyi.ai.domain.dto.HistoryItem;
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
    private final ObjectMapper mapper = new ObjectMapper();

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
                sb.append("{workbookName:").append(req.getContext().getWorkbookName())
                  .append(",sheetName:").append(req.getContext().getSheetName()).append("}");
            }
        }
        else
        {
            sb.append("当前表格状态：未提供 context");
        }
        sb.append("\n\n请输出符合 schema 的 JSON：{\"reply\":\"...\",\"actions\":[...],\"needFeedback\":false}");
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
        sb.append("- workbookName/sheetName/sheetId\n");
        sb.append("- rowCount/columnCount：sheet 总行/列数\n");
        sb.append("- usedRange：{startRow,endRow,startColumn,endColumn}，实际有数据范围（0-based 含端点）\n");
        sb.append("- values：二维数组 [行][列]，0-based 对齐 usedRange\n");
        sb.append("- formulas：二维数组，仅含公式时出现\n");
        sb.append("- merges：合并单元格列表\n");
        sb.append("- selection：{a1,startRow,endRow,startColumn,endColumn} 当前选区，可空\n");
        sb.append("- activeCell：{row,column,a1} 当前激活单元格\n");
        sb.append("- truncated：true 表示表格被截断（超 50 行或 26 列）\n\n");

        sb.append("【关键约定】行列号 0-based，但 actions 里的 range 统一用 A1 字符串（A1、B2:D5、Sheet1!A1:B2）\n\n");

        sb.append("【actions schema】type 必须命中下列 24 种白名单：\n");
        sb.append("setCell / setCellForCell / setValues / clearRange / setFormula / merge / breakApart / ");
        sb.append("insertRows / deleteRows / insertColumns / deleteColumns / setRowHeight / setColumnWidth / ");
        sb.append("setStyle / undo / redo / getRangeValue / getRangeFormulas / getMerges / getSelection / sumToCell / ");
        sb.append("setFilter / clearFilter / getFilter\n\n");

        sb.append("各类型字段：\n");
        sb.append("- setCell：{type:'setCell', range:'A1', value:100}  // value: number/string/boolean；以 = 开头视为公式\n");
        sb.append("- setCellForCell：{type:'setCellForCell', range:'A1:C3', value:'=B1*2'}\n");
        sb.append("- setValues：{type:'setValues', range:'A1:B2', values:[[1,'甲'],[2,'乙']]}\n");
        sb.append("- clearRange：{type:'clearRange', range:'B2:D10', clearWhat:'content'}  // clearWhat: content/format/all\n");
        sb.append("- setFormula：{type:'setFormula', range:'D1', formula:'SUM(B2:B5)'}\n");
        sb.append("- merge：{type:'merge', range:'A1:C1', mode:'across', force:true}  // mode: default/across/vertically\n");
        sb.append("- breakApart：{type:'breakApart', range:'A1:C1'}\n");
        sb.append("- insertRows：{type:'insertRows', rowIndex:2, count:3, position:'after'}  // rowIndex 1-based\n");
        sb.append("- deleteRows：{type:'deleteRows', rowPosition:2, count:3}\n");
        sb.append("- insertColumns：{type:'insertColumns', columnIndex:1, count:2, position:'after'}\n");
        sb.append("- deleteColumns：{type:'deleteColumns', columnPosition:1, count:2}\n");
        sb.append("- setRowHeight：{type:'setRowHeight', rowPosition:1, height:30}\n");
        sb.append("- setColumnWidth：{type:'setColumnWidth', columnPosition:1, width:120}\n");
        sb.append("- setStyle：{type:'setStyle', range:'B2:B5', background:'#ff0000', fontColor:'red', fontSize:14, fontWeight:'bold', hAlign:'center', vAlign:'middle', wrap:true}\n");
        sb.append("- sumToCell：{type:'sumToCell', sourceRange:'B2:B5', targetRange:'D1'}  // 等价 =SUM(sourceRange)\n");
        sb.append("- undo / redo：{type:'undo'} / {type:'redo'}\n");
        sb.append("- getRangeValue / getRangeFormulas / getMerges / getSelection：{type:'...', range?:'A1:C3'}\n");
        sb.append("- setFilter：{type:'setFilter', range?, column|columnIndex, filters|condition|customFormula}\n");
        sb.append("  // range 可选（缺省前端自动探测 usedRange）；column 字母 'C' 或 columnIndex 数字（绝对索引）\n");
        sb.append("  // filters: 要保留的值列表；condition: {operator,value}；customFormula: '>100'、'包含张三'、'AND(>100,<200)'\n");
        sb.append("  // condition.operator: greaterThan/lessThan/equal/notEqual/greaterThanOrEqual/lessThanOrEqual/contains\n");
        sb.append("- clearFilter：{type:'clearFilter', range?, column?, removeFilter?:bool}  // 不填 column 清所有列；removeFilter=true 移除整个筛选框\n");
        sb.append("- getFilter：{type:'getFilter', range?}\n\n");

        sb.append("【约束（违反会被前端丢弃）】\n");
        sb.append("- 单次 actions 数量上限 30\n");
        sb.append("- insert/delete 数量上限 100\n");
        sb.append("- **公式函数白名单**：SUM AVERAGE MAX MIN COUNT COUNTA COUNTIF COUNTIFS SUMIF SUMIFS ");
        sb.append("IF IFS VLOOKUP HLOOKUP INDEX MATCH CONCATENATE CONCAT TEXT ROUND ROUNDUP ROUNDDOWN ");
        sb.append("LEFT RIGHT MID LEN TRIM UPPER LOWER ABS SQRT POWER MOD INT DATE TODAY NOW YEAR MONTH DAY ");
        sb.append("WEEKDAY EOMONTH IFERROR ISBLANK ISNUMBER ISTEXT ROW COLUMN INDIRECT OFFSET SUMPRODUCT ");
        sb.append("AVERAGEIF AVERAGEIFS MAXIFS MINIFS RANK LARGE SMALL MEDIAN MODE VAR STDEV AND OR NOT ");
        sb.append("TRUE FALSE NA EXACT FIND SEARCH REPLACE SUBSTITUTE REPT VALUE NUMBERVALUE DAYS NETWORKDAYS WORKDAY\n");
        sb.append("- **禁止使用动态数组函数**：FILTER / SORT / SORTBY / UNIQUE / SEQUENCE / FILTERXML / LAMBDA 不能出现在 formula/value 字段；筛选用 setFilter action\n");
        sb.append("- 危险公式：HYPERLINK(https/WEBSERVICE/IMPORTDATA/IMPORTXML/IMPORTHTML/IMAGE/FILTERXML 含 http 一律拒绝\n");
        sb.append("- 颜色：#rrggbb 或 red/green/blue/... 英文色名\n");
        sb.append("- range 格式：A1、B2:D5、Sheet1!A1:B2\n");
        sb.append("- 禁止输出重复或语义冲突的 actions\n\n");

        sb.append("【功能边界】\n");
        sb.append("- 用户说「筛选/过滤」：**支持**。用 setFilter action，不要用 FILTER 函数\n");
        sb.append("  - 文本包含：condition={operator:'contains', value:'出'}\n");
        sb.append("  - 文本精确多值：filters=['苹果','香蕉']\n");
        sb.append("  - 数值比较：condition={operator:'greaterThan', value:15}\n");
        sb.append("  - 数值范围：customFormula:'AND(>10,<50)'\n");
        sb.append("  - 取消筛选：clearFilter；移除筛选框：clearFilter + removeFilter:true\n");
        sb.append("- 用户说「排序」：暂不支持 SORT 函数，回复\"暂不支持自动排序，请手动操作\"\n\n");

        sb.append("【输出格式】只输出 JSON，不要 markdown 围栏或解释文字：\n");
        sb.append("{\"reply\":\"自然语言回复\",\"actions\":[...],\"needFeedback\":false}\n\n");

        sb.append("【few-shot 示例】\n");
        sb.append("示例 1：\n");
        sb.append("用户：把B列有数据的求和填到D1，标题行加粗居中\n");
        sb.append("输出：{\"reply\":\"已对 B2:B4 求和填入 D1，并将标题行加粗居中\",\"actions\":[{\"type\":\"sumToCell\",\"sourceRange\":\"B2:B4\",\"targetRange\":\"D1\"},{\"type\":\"setStyle\",\"range\":\"A1:D1\",\"fontWeight\":\"bold\",\"hAlign\":\"center\"}],\"needFeedback\":false}\n\n");

        sb.append("示例 2（文本筛选 contains）：\n");
        sb.append("用户：筛选出描述列包含'出'的行\n");
        sb.append("context.values：[[\"产品\",\"描述\"],[\"苹果\",\"出口\"],[\"香蕉\",\"内销\"],[\"橘子\",\"出口\"]]\n");
        sb.append("输出：{\"reply\":\"已对 A1:B4 启用筛选，在 B 列筛选包含'出'的行\",\"actions\":[{\"type\":\"setFilter\",\"range\":\"A1:B4\",\"column\":\"B\",\"condition\":{\"operator\":\"contains\",\"value\":\"出\"}}],\"needFeedback\":false}\n");
        sb.append("错误（FILTER 函数被前端禁用）：{\"type\":\"setFormula\",\"range\":\"D1\",\"formula\":\"=FILTER(A:B,...)\"}\n\n");

        sb.append("示例 3（数值筛选 greaterThan）：\n");
        sb.append("用户：筛选出数量大于 15 的行\n");
        sb.append("输出：{\"reply\":\"已对 A1:B4 启用筛选，在 B 列筛选大于 15 的行\",\"actions\":[{\"type\":\"setFilter\",\"range\":\"A1:B4\",\"column\":\"B\",\"condition\":{\"operator\":\"greaterThan\",\"value\":15}}],\"needFeedback\":false}\n\n");

        sb.append("示例 4（多值勾选 filters）：\n");
        sb.append("用户：只保留苹果和香蕉\n");
        sb.append("输出：{\"reply\":\"已对 A 列启用筛选，只保留苹果、香蕉\",\"actions\":[{\"type\":\"setFilter\",\"range\":\"A1:B4\",\"column\":\"A\",\"filters\":[\"苹果\",\"香蕉\"]}],\"needFeedback\":false}\n\n");

        sb.append("示例 5（范围筛选 customFormula）：\n");
        sb.append("用户：筛选数量在 10 到 50 之间的行\n");
        sb.append("输出：{\"reply\":\"已对 B 列启用筛选，条件为大于 10 且小于 50\",\"actions\":[{\"type\":\"setFilter\",\"range\":\"A1:B4\",\"column\":\"B\",\"customFormula\":\"AND(>10,<50)\"}],\"needFeedback\":false}\n\n");

        sb.append("示例 6（清除筛选）：\n");
        sb.append("用户：取消筛选\n");
        sb.append("输出：{\"reply\":\"已清除当前筛选条件\",\"actions\":[{\"type\":\"clearFilter\"}],\"needFeedback\":false}\n");

        return sb.toString();
    }
}
