package com.ruoyi.ai.agent;

import junit.framework.TestCase;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure concurrency contract; no database, model or service startup. */
public class AgentPlanningDispatcherTest extends TestCase {
    public void testBlockedModelLeavesDispatcherNonBlockingAndBounded() throws Exception {
        AgentPlanningDispatcher dispatcher=new AgentPlanningDispatcher(1,1);
        CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(2);
        AtomicInteger calls=new AtomicInteger();
        try {
            assertTrue(dispatcher.submit(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}calls.incrementAndGet();done.countDown();}));
            assertTrue("first model task did not start",started.await(3,TimeUnit.SECONDS));
            long before=System.nanoTime();
            assertTrue(dispatcher.submit(()->{calls.incrementAndGet();done.countDown();}));
            assertFalse("full queue must refuse a third model call",dispatcher.submit(()->calls.incrementAndGet()));
            assertTrue("submission blocked on model",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-before)<1000);
            release.countDown();
            assertTrue("accepted work did not finish",done.await(3,TimeUnit.SECONDS));
            assertEquals(2,calls.get());
        } finally {release.countDown();dispatcher.close();}
    }
    public void testClosedDispatcherCannotAcceptModelCall() {
        AgentPlanningDispatcher dispatcher=new AgentPlanningDispatcher(1,1);
        dispatcher.close();
        assertFalse(dispatcher.submit(()->fail("closed dispatcher ran work")));
    }
    public void testStaleQueuedPlanningCannotStartModelCall() {
        long now=1000L;
        ObjectNode queued=AgentJson.obj().put("runId","r").put("userId",7).put("fencingToken",3).put("planningToken","token").put("status","PLANNING").put("deadline",2000);
        assertTrue(AgentRunService.planningStillCurrent(queued,queued,now));
        assertTrue(AgentRunService.planningStillCurrent(AgentJson.parse(queued.toString()),queued,now));
        ObjectNode paused=queued.deepCopy().put("status","PAUSED");
        assertFalse(AgentRunService.planningStillCurrent(paused,queued,now));
        ObjectNode replaced=queued.deepCopy().put("planningToken","replacement");
        assertFalse(AgentRunService.planningStillCurrent(replaced,queued,now));
        ObjectNode fenced=queued.deepCopy().put("fencingToken",4);
        assertFalse(AgentRunService.planningStillCurrent(fenced,queued,now));
        assertFalse(AgentRunService.planningStillCurrent(queued,queued,2000));
        ObjectNode noDeadline=queued.deepCopy();noDeadline.remove("deadline");
        assertFalse(AgentRunService.planningStillCurrent(noDeadline,queued,now));
    }
}
