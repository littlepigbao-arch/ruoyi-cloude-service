package com.ruoyi.ai.agent;

import com.ruoyi.ai.service.LlmClient;
import com.ruoyi.ai.service.LlmResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.ruoyi.ai.agent.AgentJson.*;

/** Isolated evidence only. Always calls the actual upstream; never substitutes or changes its response. */
@Component
@Primary
@ConditionalOnProperty(name="b3.protocol-evidence-enabled",havingValue="true")
public class B3EvidenceLlmClient extends LlmClient {
    private final AtomicInteger sequence=new AtomicInteger();
    @Override public LlmResponse chat(List<Map<String,String>> messages){
        LlmResponse response=super.chat(messages);
        try{
            Path root=Paths.get("D:/Code/agentReport/b3-output-20261018/model-evidence");Files.createDirectories(root);
            String name=System.currentTimeMillis()+"-"+sequence.incrementAndGet()+".json";
            Files.write(root.resolve(name),obj().put("source","REAL_UPSTREAM_NO_STUB").put("model",response.getModel()).put("totalTokens",response.getTotalTokens()).put("rawContent",response.getContent()).put("requestMessagesHash",WriteManifest.digest(JSON.valueToTree(messages))).toString().getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        }catch(Exception e){throw new IllegalStateException("B3 upstream evidence could not be retained",e);}
        return response;
    }
}
