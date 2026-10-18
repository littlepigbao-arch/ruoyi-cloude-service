package com.ruoyi.ai.service;

import com.ruoyi.ai.agent.*;
import com.ruoyi.ai.domain.*;
import com.ruoyi.ai.domain.dto.*;
import com.ruoyi.ai.mapper.*;
import com.ruoyi.ai.service.impl.AiChatServiceImpl;
import com.ruoyi.common.core.context.SecurityContextHolder;
import com.ruoyi.common.core.domain.R;
import junit.framework.TestCase;
import java.lang.reflect.Proxy;
import java.util.*;

/** Service unit test: real parsing/target/policy logic, explicit in-memory DB and LLM boundaries. */
public class AiChatControlledPolicyTest extends TestCase {
    private AiChatServiceImpl service;private ChatRequest request;private String actions;private final List<AiMessage> messages=new ArrayList<>();
    @SuppressWarnings("unchecked") private <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler handler){return(T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},handler);}
    protected void setUp()throws Exception{
        messages.clear();SecurityContextHolder.setUserId("1");SecurityContextHolder.setUserName("b2-unit");service=new AiChatServiceImpl();
        A1TestFixture.inject(service,"conversationMapper",proxy(AiConversationMapper.class,(o,m,a)->m.getName().equals("selectByConvId")?null:1));
        A1TestFixture.inject(service,"workbookMapper",proxy(AiWorkbookMapper.class,(o,m,a)->1));
        A1TestFixture.inject(service,"messageMapper",proxy(AiMessageMapper.class,(o,m,a)->{if(m.getName().equals("insertMessage")){messages.add((AiMessage)a[0]);return 1;}return Collections.emptyList();}));
        A1TestFixture.inject(service,"promptBuilder",new PromptBuilder(){public List<Map<String,String>> buildMessages(ChatRequest r,List<AiMessage> h){return Collections.emptyList();}});
        A1TestFixture.inject(service,"llmClient",new LlmClient(){public LlmResponse chat(List<Map<String,String>> m){LlmResponse r=new LlmResponse();r.setContent("{\"reply\":\"proposed\",\"actions\":"+actions+"}");return r;}});
        A1TestFixture.inject(service,"controlledWrites",new ControlledWriteService(null,null,null,null,new org.springframework.jdbc.datasource.DataSourceTransactionManager()){public boolean enabled(){return true;}});
        request=new ChatRequest();request.setMessage("test");WorkbookContext context=new WorkbookContext();context.setWorkbookId(1L);context.setActiveSheetId("s");SheetContext sheet=new SheetContext();sheet.setSheetId("s");sheet.setSheetName("Orders");context.setSheets(Collections.singletonList(sheet));request.setContext(context);
    }
    private String cell(){return "[{\"type\":\"setCell\",\"schemaVersion\":\"1.0\",\"range\":\"B2\",\"value\":11}]";}
    public void testNewClientKeepsNaturalLanguageActionsWithoutPlan(){actions=cell();request.setClientMeta(Collections.singletonMap("controlledWriteTransport",2));R<ChatResponse> result=service.chat(request);assertEquals(200,result.getCode());assertEquals("setCell",result.getData().getActions().get(0).getType());assertEquals("2.0",result.getData().getActions().get(0).getSchemaVersion());assertTrue(messages.get(1).getContent().contains("未代表已执行或保存"));}
    public void testOldClientWriteExplicitlyRejectedBeforeAssistantActionsPersist(){actions=cell();assertEquals(409,service.chat(request).getCode());assertEquals(1,messages.size());}
    public void testReadOnlyOldClientStillWorks(){actions="[{\"type\":\"getRangeValue\",\"range\":\"B2\"}]";assertEquals(200,service.chat(request).getCode());assertEquals("proposed",messages.get(1).getContent());}
    public void testUnmigratedWriteCannotEscapeViaChat(){actions="[{\"type\":\"setStyle\",\"range\":\"B2\"}]";request.setClientMeta(Collections.singletonMap("controlledWriteTransport",2));assertEquals(409,service.chat(request).getCode());assertEquals(1,messages.size());}
    public void testInvalidV2TargetRemainsRejected(){actions="[{\"type\":\"setCell\",\"schemaVersion\":\"2.0\",\"range\":\"B2\",\"value\":11}]";request.setClientMeta(Collections.singletonMap("controlledWriteTransport",2));assertEquals(422,service.chat(request).getCode());}
}
