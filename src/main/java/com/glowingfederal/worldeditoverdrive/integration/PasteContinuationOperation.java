package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.RunContext;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Operation-owned lifecycle token for a future scheduler-driven paste.
 * This class performs no mutation and must not be passed to Enhanced's
 * synchronous {@code Operations.complete*} loop while it is pending.
 */
public final class PasteContinuationOperation implements Operation {
    public enum State { CREATED, SUBMITTED, RUNNING, COMMITTING, COMPLETED, FAILED, CANCELLED }
    private static final class Status {final State state;final Throwable failure;Status(State state,Throwable failure){this.state=state;this.failure=failure;}}
    private final AtomicReference<Status> status=new AtomicReference<Status>(new Status(State.CREATED,null));

    public State state(){return status.get().state;}
    public boolean isTerminal(){return isTerminal(status.get().state);}
    public Throwable failure(){return status.get().failure;}
    public boolean submitted(){return advance(State.CREATED,State.SUBMITTED);}
    public boolean running(){return advance(State.SUBMITTED,State.RUNNING);}
    public boolean committing(){return advance(State.RUNNING,State.COMMITTING);}
    public boolean complete(){return advance(State.COMMITTING,State.COMPLETED);}
    public boolean fail(Throwable cause){
        if(cause==null)throw new NullPointerException("cause");
        for(;;){Status current=status.get();if(isTerminal(current.state))return false;if(status.compareAndSet(current,new Status(State.FAILED,cause)))return true;}
    }
    public void cancel(){
        for(;;){Status current=status.get();if(isTerminal(current.state))return;if(status.compareAndSet(current,new Status(State.CANCELLED,null)))return;}
    }
    public Operation resume(RunContext run)throws WorldEditException{return isTerminal()?null:this;}
    public void addStatusMessages(List<String> messages){messages.add("Overdrive paste: "+status.get().state.name().toLowerCase());}
    private boolean advance(State expected,State next){Status current=status.get();return current.state==expected&&status.compareAndSet(current,new Status(next,null));}
    private static boolean isTerminal(State value){return value==State.COMPLETED||value==State.FAILED||value==State.CANCELLED;}
}
