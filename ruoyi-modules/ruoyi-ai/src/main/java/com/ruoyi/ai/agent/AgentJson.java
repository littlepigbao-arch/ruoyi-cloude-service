package com.ruoyi.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.ruoyi.ai.util.ContentHashUtils;

public final class AgentJson {
    public static final ObjectMapper JSON = new ObjectMapper();
    private AgentJson() { }
    public static ObjectNode obj() { return JSON.createObjectNode(); }
    public static ArrayNode arr() { return JSON.createArrayNode(); }
    public static JsonNode parse(String text) {
        try { return JSON.readTree(text); }
        catch (Exception e) { throw new IllegalArgumentException("JSON 数据无效", e); }
    }
    public static String hash(JsonNode node) { return ContentHashUtils.sha256(node.toString()); }
    public static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
    public static String text(JsonNode n, String field) {
        String s = n.path(field).asText("");
        require(!s.trim().isEmpty(), "缺少字段 " + field);
        return s;
    }
}
