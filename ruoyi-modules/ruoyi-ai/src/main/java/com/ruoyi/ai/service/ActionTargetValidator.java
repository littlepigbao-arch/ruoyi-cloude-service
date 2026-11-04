package com.ruoyi.ai.service;

import com.ruoyi.ai.domain.dto.*;
import java.util.*;

/** A1 target contract, shared in intent with normalizeAction/resolveSheet. */
public final class ActionTargetValidator {
    private static final Set<String> READS = new HashSet<>(Arrays.asList(
        "getRangeValue", "getRangeFormulas", "getMerges", "getSelection", "getFilter"));
    private ActionTargetValidator() { }
    private static boolean present(String value) { return value != null && !value.trim().isEmpty(); }
    private static void check(boolean ok, String message) { if (!ok) throw new IllegalArgumentException(message); }
    public static void normalize(Action action, WorkbookContext context) {
        check(action != null && context != null, "动作或工作簿上下文缺失");
        String version = action.getSchemaVersion();
        check(!present(version) || "1.0".equals(version) || "2.0".equals(version), "不支持的动作协议版本");
        boolean b5Format=Arrays.asList("setStyle","setRowHeight","setColumnWidth").contains(action.getType());
        boolean b6Filter=Arrays.asList("setFilter","clearFilter").contains(action.getType());
        boolean b7Formula=Arrays.asList("setFormula","sumToCell").contains(action.getType());
        if("sumToCell".equals(action.getType())&&present(action.getTargetRange())){
            check(action.getTarget()!=null,"B10 sumToCell 必须显式指定 target.sheetId");
            check(action.getTarget().getRange()==null||action.getTargetRange().equals(action.getTarget().getRange()),"B10 sumToCell 目标范围冲突");
            action.getTarget().setRange(action.getTargetRange());action.setTargetRange(null);
        }
        boolean b8Merge=Arrays.asList("merge","breakApart").contains(action.getType());
        boolean b8Structure=Arrays.asList("insertRows","deleteRows","insertColumns","deleteColumns","createSheet","renameSheet","copySheet","deleteSheet").contains(action.getType());
        if(b5Format)check("2.0".equals(version)&&action.getTarget()!=null&&present(action.getTarget().getSheetId()),"B5 格式动作必须显式指定 V2 target.sheetId");
        if(b6Filter)check("2.0".equals(version)&&action.getTarget()!=null&&present(action.getTarget().getSheetId())&&present(action.getTarget().getRange()),"B6 筛选动作必须显式指定 V2 target.sheetId/range");
        if(b7Formula)check("2.0".equals(version)&&action.getTarget()!=null&&present(action.getTarget().getSheetId())&&present(action.getTarget().getRange()),"B7 公式动作必须显式指定 V2 target.sheetId/range");
        if(b8Merge)check("2.0".equals(version)&&action.getTarget()!=null&&present(action.getTarget().getSheetId())&&present(action.getTarget().getRange()),"B8b 合并动作必须显式指定 V2 target.sheetId/range");
        if(b8Structure)check("2.0".equals(version)&&action.getTarget()!=null&&present(action.getTarget().getSheetId())&&!present(action.getTarget().getRange()),"B8c 行列动作必须显式指定 V2 target.sheetId，且不携带 range");
        boolean v2 = "2.0".equals(version) || (!present(version) && action.getTarget() != null);
        Action.Target target = action.getTarget();
        String id = target != null && present(target.getSheetId()) ? target.getSheetId() : action.getSheetId();
        String name = target != null && present(target.getSheetName()) ? target.getSheetName() : action.getSheetName();
        String range = target != null && present(target.getRange()) ? target.getRange() : action.getRange();
        if(b5Format)check("setStyle".equals(action.getType())?present(range):!present(range),"B5 格式目标范围无效");
        if (target != null) {
            check(!present(target.getSheetId()) || !present(action.getSheetId()) || target.getSheetId().equals(action.getSheetId()), "Sheet ID 冲突");
            check(!present(target.getSheetName()) || !present(action.getSheetName()) || target.getSheetName().equals(action.getSheetName()), "Sheet 名称冲突");
            check(!present(target.getRange()) || !present(action.getRange()) || target.getRange().equals(action.getRange()), "目标区域冲突");
        }
        if (v2 && !READS.contains(action.getType())) check(target != null && present(target.getSheetId()), "V2 写动作必须指定 target.sheetId");
        if (range != null && range.contains("!")) {
            int bang = range.indexOf('!');String prefix = range.substring(0,bang).trim();
            if (prefix.length()>1 && ((prefix.startsWith("'")&&prefix.endsWith("'"))||(prefix.startsWith("\"")&&prefix.endsWith("\"")))) prefix=prefix.substring(1,prefix.length()-1);
            check(!present(name)||name.equals(prefix), "区域前缀与 Sheet 名称冲突");
            name=prefix;range=range.substring(bang+1).trim();
        }
        List<SheetContext> sheets = context.getSheets()==null?Collections.emptyList():context.getSheets();
        SheetContext selected = null;
        for (SheetContext sheet : sheets) {
            if (present(id) ? id.equals(sheet.getSheetId()) : present(name) ? name.equals(sheet.getSheetName()) : context.getActiveSheetId()!=null&&context.getActiveSheetId().equals(sheet.getSheetId())) {
                check(selected==null,"Sheet 目标不唯一");selected=sheet;
            }
        }
        check(selected!=null,"目标 Sheet 不存在");
        check(!present(name)||name.equals(selected.getSheetName()),"Sheet ID 与名称冲突");
        Action.Target normalized=new Action.Target();normalized.setSheetId(selected.getSheetId());normalized.setSheetName(b5Format||b6Filter||b7Formula||b8Merge||b8Structure?null:selected.getSheetName());normalized.setRange(b8Structure||b5Format&&!"setStyle".equals(action.getType())?null:range);
        action.setTarget(normalized);action.setSheetId(null);action.setSheetName(null);action.setRange(b5Format||b6Filter||b7Formula||b8Merge||b8Structure?null:range);action.setSchemaVersion("2.0");
    }
}
