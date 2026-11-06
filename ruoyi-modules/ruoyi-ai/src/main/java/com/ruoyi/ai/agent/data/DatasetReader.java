package com.ruoyi.ai.agent.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruoyi.ai.agent.AgentStore;
import static com.ruoyi.ai.agent.AgentJson.*;

/**
 * C1b: resolves a resultRef into dataset rows consumable by the DatasetService operators.
 * Inline artifacts read content_json directly; chunked artifacts (including C1b chunked
 * dataset envelopes) are reassembled and hash-verified inside AgentStore.artifactRecord's
 * dual read, so this reader never widens access or relaxes SOURCE_STALE. Messages and the
 * 200000-cell budget semantics are moved verbatim from AgentTools.data.
 */
public class DatasetReader {
    private final AgentStore store;
    public DatasetReader(AgentStore store) { this.store = store; }
    public JsonNode dataset(ObjectNode run, JsonNode reference) {
        require(reference != null && reference.has("resultRef"), "缺少真实上游结果引用");
        ObjectNode record = store.artifactRecord(run, text(reference, "resultRef"));
        require(record.path("sourceRevision").asText().equals(reference.path("sourceRevision").asText()), "SOURCE_STALE: 引用声明与制品权威来源不一致");
        return record.path("data");
    }
}
