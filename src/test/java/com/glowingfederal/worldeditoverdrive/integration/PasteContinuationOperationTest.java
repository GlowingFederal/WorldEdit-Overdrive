package com.glowingfederal.worldeditoverdrive.integration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PasteContinuationOperationTest {
    @Test public void pendingIsNotCompletion()throws Exception{
        PasteContinuationOperation op=new PasteContinuationOperation();assertFalse(op.isTerminal());assertSame(op,op.resume(null));
        assertTrue(op.submitted());assertTrue(op.running());assertTrue(op.committing());assertSame(op,op.resume(null));
        assertTrue(op.complete());assertNull(op.resume(null));assertFalse(op.complete());
    }
    @Test public void failureAndCancellationRemainDistinct(){
        PasteContinuationOperation failed=new PasteContinuationOperation();assertTrue(failed.fail(new Exception("failure")));assertEquals(PasteContinuationOperation.State.FAILED,failed.state());assertNotNull(failed.failure());assertFalse(failed.complete());
        PasteContinuationOperation cancelled=new PasteContinuationOperation();cancelled.cancel();assertEquals(PasteContinuationOperation.State.CANCELLED,cancelled.state());cancelled.cancel();assertNull(cancelled.failure());
    }
    @Test public void illegalTransitionsAreRejected(){PasteContinuationOperation op=new PasteContinuationOperation();assertFalse(op.running());assertFalse(op.complete());assertEquals(PasteContinuationOperation.State.CREATED,op.state());}
    @Test public void concurrentCompletionWinsExactlyOnce()throws Exception{
        final PasteContinuationOperation op=new PasteContinuationOperation();op.submitted();op.running();op.committing();final AtomicInteger wins=new AtomicInteger();final CountDownLatch start=new CountDownLatch(1);Thread[] threads=new Thread[12];
        for(int i=0;i<threads.length;i++){threads[i]=new Thread(new Runnable(){public void run(){try{start.await();if(op.complete())wins.incrementAndGet();}catch(InterruptedException e){Thread.currentThread().interrupt();}}});threads[i].start();}
        start.countDown();for(Thread thread:threads)thread.join();assertEquals(1,wins.get());assertEquals(PasteContinuationOperation.State.COMPLETED,op.state());
    }
}
