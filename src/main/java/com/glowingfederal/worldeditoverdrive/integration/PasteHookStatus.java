package com.glowingfederal.worldeditoverdrive.integration;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Diagnostics owned exclusively by the optional Stage 5C paste bridge. */
public final class PasteHookStatus {
    public enum RuntimeShape { NOT_SEEN, SEEN_INCOMPATIBLE, SEEN_COMPATIBLE, HOOK_INSTALLED }
    private static final AtomicReference<RuntimeShape> runtimeShape=new AtomicReference<RuntimeShape>(RuntimeShape.NOT_SEEN);
    public static volatile boolean pasteHookInstalled;
    public static volatile boolean pasteBytecodeModified;
    public static volatile String hookReason="ForwardExtentCopy not observed";
    public static volatile String lastPasteFallbackReason;
    public static volatile String lastPasteGraphDiagnostic;
    public static volatile String lastPasteDeferredReason;
    public static volatile String lastPasteAccelerationFallbackReason;
    public static volatile String lastPasteTransform;
    public static volatile boolean lastPasteIgnoreAir;
    public static volatile String queueImplementationClass="unknown",editSessionExtentClass="unknown";
    public static volatile boolean queueEnabled;
    public static volatile boolean incrementalCommitSupported;
    public static volatile boolean topLevelCommitReturnedNull,commitCompletedNormally;
    public static volatile String commitOperationClass="none",activeCommitOperationClassBeforeResume="none",activeCommitOperationClassAfterResume="none";
    public static final AtomicLong pasteBridgeInvocations=new AtomicLong();
    public static final AtomicLong pasteAccelerated=new AtomicLong();
    public static final AtomicLong pasteFallbacks=new AtomicLong();
    public static final AtomicLong pasteDeferredActive=new AtomicLong();
    public static final AtomicLong pasteDeferredCompleted=new AtomicLong();
    public static final AtomicLong pasteDeferredFailed=new AtomicLong();
    public static final AtomicLong pasteAccelerationFallbacks=new AtomicLong();
    public static final AtomicLong pastePlanningActive=new AtomicLong(),pasteCommitActive=new AtomicLong();
    public static final AtomicLong pasteWorkerTasksSubmitted=new AtomicLong(),pasteWorkerTasksCompleted=new AtomicLong(),pasteWorkerActive=new AtomicLong(),pasteWorkerPlanNanos=new AtomicLong(),pasteWorkerMaxConcurrency=new AtomicLong();
    public static final AtomicLong pastePreparedBlocks=new AtomicLong(),pastePlannedBlocks=new AtomicLong(),pasteSubmittedBlocks=new AtomicLong(),pasteCommittedBlocks=new AtomicLong();
    public static final AtomicLong pasteSourceAirCells=new AtomicLong(),pasteIgnoreAirFilteredCells=new AtomicLong(),pasteDestinationMatchedCells=new AtomicLong(),pasteOtherwiseFilteredCells=new AtomicLong();
    public static final AtomicLong pastePreparedTiles=new AtomicLong(),pasteCommittedTiles=new AtomicLong(),pastePreparedEntities=new AtomicLong(),pasteCommittedEntities=new AtomicLong(),pasteTransformedBlocks=new AtomicLong();
    public static final AtomicLong lastPastePrepareMillis=new AtomicLong(),lastPastePlanMillis=new AtomicLong(),lastPasteCommitMillis=new AtomicLong();
    public static final AtomicLong lastOperationCommandInterceptMillis=new AtomicLong(),lastOperationSnapshotWallMillis=new AtomicLong(),lastOperationSnapshotActiveMillis=new AtomicLong();
    public static final AtomicLong lastOperationPlanWallMillis=new AtomicLong(),lastOperationCommitWallMillis=new AtomicLong(),lastOperationCommitActiveMillis=new AtomicLong(),lastOperationWallMillis=new AtomicLong(),lastOperationMaxServerSliceMillis=new AtomicLong();
    public static final AtomicLong sourceCaptureServerMillis=new AtomicLong(),destinationCaptureServerMillis=new AtomicLong(),commitServerMillis=new AtomicLong(),queueDrainServerMillis=new AtomicLong(),finalizationServerMillis=new AtomicLong();
    public static final AtomicLong submittedSinceLastDrain=new AtomicLong(),chunksSinceLastDrain=new AtomicLong(),flushCount=new AtomicLong(),lastFlushMillis=new AtomicLong(),totalFlushNanos=new AtomicLong(),maxFlushMillis=new AtomicLong(),maxSubmissionSliceMillis=new AtomicLong(),maxFinalFlushMillis=new AtomicLong(),finalFlushQueuedMutations=new AtomicLong(),finalFlushMillis=new AtomicLong();
    public static final AtomicLong finalFlushChunks=new AtomicLong(),uninterruptibleFlushOverBudgetCount=new AtomicLong();
    public static final AtomicLong incrementalCommitSlices=new AtomicLong(),commitResumeCalls=new AtomicLong(),maxCommitResumeMillis=new AtomicLong(),commitOperationRemaining=new AtomicLong(-1),finalSynchronousFlushCount=new AtomicLong();
    public static final AtomicLong reorderStage1Remaining=new AtomicLong(-1),reorderStage2Remaining=new AtomicLong(-1),reorderStage3Remaining=new AtomicLong(-1);
    public static final AtomicLong blockMapPlacementsThisResume=new AtomicLong(),stage3ChainsThisResume=new AtomicLong(),deadlineYieldCount=new AtomicLong(),blockMapDeadlineYields=new AtomicLong(),stage3DeadlineYields=new AtomicLong();
    public static final AtomicLong snapshotProcessed=new AtomicLong(),snapshotTotalEstimate=new AtomicLong(),workerQueuedChunks=new AtomicLong(),workerCompletedChunks=new AtomicLong(),commitRemaining=new AtomicLong();
    public static volatile String activePhase="IDLE";
    public static RuntimeShape runtimeShape(){return runtimeShape.get();}
    public static boolean forwardExtentCopySeen(){return runtimeShape.get()!=RuntimeShape.NOT_SEEN;}
    public static boolean runtimeShapeCompatible(){RuntimeShape state=runtimeShape.get();return state==RuntimeShape.SEEN_COMPATIBLE||state==RuntimeShape.HOOK_INSTALLED;}
    static void observedCompatible(){if(pasteHookInstalled)return;runtimeShape.set(RuntimeShape.SEEN_COMPATIBLE);hookReason="compatible; ClipboardCommands paste hook not observed";}
    static void observedIncompatible(String reason){runtimeShape.set(RuntimeShape.SEEN_INCOMPATIBLE);pasteHookInstalled=false;hookReason="incompatible runtime shape: "+reason;}
    static void hookInstalled(){runtimeShape.set(RuntimeShape.HOOK_INSTALLED);pasteHookInstalled=true;pasteBytecodeModified=true;hookReason="installed";}
    static void resetForTests(){runtimeShape.set(RuntimeShape.NOT_SEEN);pasteHookInstalled=false;pasteBytecodeModified=false;hookReason="ForwardExtentCopy not observed";lastPasteGraphDiagnostic=null;}
    private PasteHookStatus() { }
}
