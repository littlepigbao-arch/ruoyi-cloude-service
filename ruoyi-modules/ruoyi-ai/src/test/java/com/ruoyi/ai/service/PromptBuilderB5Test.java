package com.ruoyi.ai.service;

import com.ruoyi.ai.domain.dto.ChatRequest;
import junit.framework.TestCase;
import java.util.Collections;

/** Candidate prompt contract; production B5 gate remains closed. */
public class PromptBuilderB5Test extends TestCase {
    public void testClosedPromptDoesNotAdvertiseFormatWrites() {
        String prompt=new PromptBuilder().buildMessages(new ChatRequest(),Collections.emptyList()).get(0).get("content");
        assertTrue(prompt.contains("其他写动作未开放"));
    }
    public void testCandidatePromptOnlyDescribesRegisteredFormatContract() {
        String prompt=new PromptBuilder().buildMessages(new ChatRequest(),Collections.emptyList(),true).get(0).get("content");
        assertTrue(prompt.contains("setStyle"));
        assertTrue(prompt.contains("setRowHeight"));
        assertTrue(prompt.contains("setColumnWidth"));
        assertTrue(prompt.contains("\"type\":\"setCell\""));
        assertTrue(prompt.contains("#rrggbb"));
        assertTrue(prompt.contains("20-400"));
        assertTrue(prompt.contains("40-800"));
        assertTrue(prompt.contains("target:{sheetId,range}"));
        assertTrue(prompt.contains("target:{sheetId}"));
        assertFalse(prompt.contains("fontWeight:'bold'"));
        assertFalse(prompt.contains("筛选/过滤」：**支持**"));
        assertFalse(prompt.contains("格式写入尚未开放"));
    }
}
