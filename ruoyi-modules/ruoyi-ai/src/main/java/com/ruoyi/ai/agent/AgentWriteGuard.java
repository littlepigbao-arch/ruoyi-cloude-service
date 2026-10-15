package com.ruoyi.ai.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.sql.ResultSet;
import org.springframework.jdbc.core.ConnectionCallback;
import static com.ruoyi.ai.agent.AgentJson.*;

@Component
public class AgentWriteGuard {
    private final JdbcTemplate db;
    @Value("${ai.agent.enabled:false}") private boolean enabled;
    public AgentWriteGuard(JdbcTemplate db) { this.db = db; }
    public boolean enabled() { return enabled; }
    public boolean recoveryAvailable() { return tableExists("ai_agent_lease") && tableExists("ai_agent_run"); }
    private boolean tableExists(String name) {
        return Boolean.TRUE.equals(db.execute((ConnectionCallback<Boolean>) connection -> {
            try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, name, new String[]{"TABLE"})) {
                return tables.next();
            }
        }));
    }
    public void lock(Long workbookId, Long userId) {
        require(!db.queryForList("select workbook_id from ai_workbook where workbook_id=? and user_id=? for update", workbookId, userId).isEmpty(), "工作簿不存在或无权访问");
    }
    public void manual(Long workbookId, Long userId) {
        lock(workbookId, userId);
        // Disabling new tasks never releases an existing lease. Metadata failures
        // fail closed; an old deployment without additive tables can still save.
        if (!tableExists("ai_agent_lease")) {
            require(!enabled && !tableExists("ai_agent_run"), "智能体租约表尚未准备，拒绝写入");
            return;
        }
        List<String> owners = db.queryForList("select run_id from ai_agent_lease where workbook_id=? and run_id is not null", String.class, workbookId);
        require(owners.isEmpty(), "工作簿有活动智能体任务，请先停止或恢复任务后再编辑");
    }
}
