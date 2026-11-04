package com.ruoyi.ai.service;
import com.ruoyi.ai.service.impl.AiChatServiceImpl;
import com.ruoyi.ai.domain.dto.ChatResponse;
import java.lang.reflect.Method;
import java.util.Arrays;
import junit.framework.TestCase;
public class B10ReadChatCompatibilityTest extends TestCase {
    private ChatResponse parse(String raw)throws Exception{Method method=AiChatServiceImpl.class.getDeclaredMethod("parseChatResponse",String.class);method.setAccessible(true);return(ChatResponse)method.invoke(new AiChatServiceImpl(),raw);}
    public void testMultipleSeriesNamesSurviveTheRealChatParser()throws Exception{ChatResponse r=parse("{\"reply\":\"chart\",\"actions\":[{\"type\":\"createChart\",\"sheetId\":\"s\",\"chartType\":\"line\",\"categoryRange\":\"A2:A4\",\"seriesRange\":\"B2:C4\",\"seriesNames\":[\"Amount\",\"Other\"]}]}");assertEquals(1,r.getActions().size());assertEquals(Arrays.asList("Amount","Other"),r.getActions().get(0).getSeriesNames());}
    public void testUnregisteredFieldsAreStillNotIgnored()throws Exception{ChatResponse r=parse("{\"reply\":\"read\",\"actions\":[{\"type\":\"getRangeValue\",\"sheetId\":\"s\",\"range\":\"A1\",\"unregisteredPayload\":\"bad\"}]}");assertEquals(0,r.getActions().size());}
}
