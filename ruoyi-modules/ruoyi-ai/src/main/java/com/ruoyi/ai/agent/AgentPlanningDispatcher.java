package com.ruoyi.ai.agent;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded model-call workers. Scheduler scans and database transitions stay on their own thread. */
final class AgentPlanningDispatcher implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    AgentPlanningDispatcher(int parallelism,int waiting) {
        if(parallelism<1||waiting<1)throw new IllegalArgumentException("Planning capacity must be positive");
        AtomicInteger sequence=new AtomicInteger();
        workers=new ThreadPoolExecutor(parallelism,parallelism,0L,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(waiting),task->{Thread thread=new Thread(task,"agent-planning-"+sequence.incrementAndGet());thread.setDaemon(true);return thread;},
            new ThreadPoolExecutor.AbortPolicy());
    }
    boolean submit(Runnable task) {
        try{workers.execute(task);return true;}catch(RejectedExecutionException rejected){return false;}
    }
    @Override public void close(){workers.shutdownNow();}
}
