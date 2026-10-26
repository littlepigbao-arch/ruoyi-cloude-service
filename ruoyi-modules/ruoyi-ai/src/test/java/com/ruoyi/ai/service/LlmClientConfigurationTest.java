package com.ruoyi.ai.service;

import junit.framework.TestCase;
import java.util.Collections;

public class LlmClientConfigurationTest extends TestCase {
    public void testMissingKeyNamesActualSpringProperty() {
        try {
            new LlmClient().chat(Collections.emptyList());
            fail("Missing model key should be rejected before a network request");
        } catch (LlmUnavailableException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("ai.model.api-key"));
        }
    }
}
