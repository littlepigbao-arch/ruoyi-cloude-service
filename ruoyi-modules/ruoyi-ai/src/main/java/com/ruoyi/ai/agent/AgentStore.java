package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.ruoyi.ai.agent.AgentJson.*;

/** All mutation callers hold the workbook row lock, then the run row lock. */
@Component
public class AgentStore {
    public final JdbcTemplate db;
    public AgentStore(JdbcTemplate db) { this.db = db; }
    public ObjectNode get(String id) {
        List<String> rows = db.queryForList("select state_json from ai_agent_run where run_id=?", String.class, id);
        require(!rows.isEmpty(), "任务不存在");
        return (ObjectNode) parse(rows.get(0));
    }
    public ObjectNode locked(String id) {
        return (ObjectNode) parse(db.queryForObject("select state_json from ai_agent_run where run_id=? for update", String.class, id));
    }
    public void save(ObjectNode r) {
        long old = r.path("version").asLong();
        r.put("version", old + 1);
        require(db.update("update ai_agent_run set status=?,state_json=?,row_version=row_version+1,updated_at=now(3) where run_id=? and row_version=?",
            r.path("status").asText(), r.toString(), r.path("runId").asText(), old) == 1, "任务版本冲突");
    }
    public void event(ObjectNode r, String type, String message) {
        long seq = r.path("eventSeq").asLong() + 1;
        r.put("eventSeq", seq);
        ObjectNode e = obj().put("sequence", seq).put("type", type).put("message", message).put("time", System.currentTimeMillis());
        db.update("insert into ai_agent_event values (?,?,?,now(3))", r.path("runId").asText(), seq, e.toString());
    }
    public void call(ObjectNode r, ObjectNode c) {
        db.update("insert into ai_agent_tool_call values (?,?,?,?,?) on duplicate key update status=values(status),call_json=values(call_json)",
            r.path("runId").asText(), c.path("callId").asText(), c.path("status").asText(), c.path("argsHash").asText(), c.toString());
    }
    public String artifact(ObjectNode r, JsonNode data) {
        String id = UUID.randomUUID().toString();
        db.update("insert into ai_analysis_artifact values (?,?,?,?,?,now(3))", id, r.path("runId").asText(), r.path("userId").asLong(), r.path("revision").asText(), data.toString());
        return id;
    }
    public JsonNode artifact(ObjectNode r, String id) {
        return artifactRecord(r,id).path("data");
    }
    /** Metadata comes from the persisted artifact and its owning run, never the reference payload. */
    public ObjectNode artifactRecord(ObjectNode r, String id) {
        List<Map<String,Object>> rows = db.queryForList(
            "select a.source_revision,a.content_json,r.workbook_id,r.branch_id from ai_analysis_artifact a " +
            "join ai_agent_run r on r.run_id=a.run_id and r.user_id=a.user_id " +
            "join ai_workbook w on w.workbook_id=r.workbook_id and w.user_id=r.user_id " +
            "join ai_workbook_branch b on b.branch_id=r.branch_id and b.workbook_id=w.workbook_id and b.owner_user_id=r.user_id and b.status='active' " +
            "where a.artifact_id=? and a.run_id=? and a.user_id=? and r.workbook_id=? and r.branch_id=?",
            id,r.path("runId").asText(),r.path("userId").asLong(),r.path("workbookId").asLong(),r.path("branchId").asLong());
        require(rows.size()==1,"SOURCE_FORBIDDEN: 结果引用不存在或来源工作簿/分支无权访问");
        Map<String,Object> row=rows.get(0);String revision=String.valueOf(row.get("source_revision"));
        require(!revision.isEmpty() && revision.equals(r.path("revision").asText()),"SOURCE_STALE: 制品权威来源版本已变化，请重新提取");
        ObjectNode record=obj().put("resultRef",id).put("sourceRevision",revision)
            .put("workbookId",((Number)row.get("workbook_id")).longValue()).put("branchId",((Number)row.get("branch_id")).longValue());
        record.set("data",parse(String.valueOf(row.get("content_json"))));return record;
    }
}
