package com.glowingfederal.worldeditoverdrive;

import com.glowingfederal.worldeditoverdrive.execution.OverdriveConfiguration;
import com.glowingfederal.worldeditoverdrive.execution.OverdriveCoordinator;
import com.glowingfederal.worldeditoverdrive.integration.OverdriveEditSummary;
import com.glowingfederal.worldeditoverdrive.integration.OverdriveSummaries;
import com.glowingfederal.worldeditoverdrive.integration.Stage4HookStatus;
import com.glowingfederal.worldeditoverdrive.integration.PasteHookStatus;
import com.glowingfederal.worldeditoverdrive.integration.DeferredPasteManager;
import com.glowingfederal.worldeditoverdrive.integration.CommandHookStatus;
import com.glowingfederal.worldeditoverdrive.integration.PlacementProfile;
import com.glowingfederal.worldeditoverdrive.execution.AdaptiveServerBudget;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import java.util.Arrays;
import java.util.List;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;

/** Dedicated-server console/operator diagnostics; no client command handler is involved. */
public final class OverdriveCommand extends CommandBase {
    private final WorldEditOverdrive mod;
    OverdriveCommand(WorldEditOverdrive mod){this.mod=mod;}
    public String getCommandName(){return "overdrive";}
    public String getCommandUsage(ICommandSender sender){return "/overdrive <status|stats|preparation|placement|history|pacing|paste|undo|redo|profiling> [view]; profiling [sample|full|off|native|optimized]";}
    public int getRequiredPermissionLevel(){return 2;}
    public List getCommandAliases(){return Arrays.asList("worldeditoverdrive");}
    public void processCommand(ICommandSender sender,String[] args){
        if(args.length<1||args.length>2){send(sender,getCommandUsage(sender));return;}
        String command=args[0].toLowerCase(java.util.Locale.ROOT),view=args.length==2?args[1].toLowerCase(java.util.Locale.ROOT):"all";
        if(command.equals("profiling")){profiling(sender,view);return;}
        if(args.length==2&&(!(command.equals("paste")||command.equals("undo")||command.equals("redo"))||!(view.equals("preparation")||view.equals("placement")||view.equals("pacing")))){send(sender,getCommandUsage(sender));return;}
        if("status".equalsIgnoreCase(args[0]))status(sender);
        else if("stats".equalsIgnoreCase(args[0]))stats(sender);
        else if(command.equals("preparation"))preparation(sender);
        else if(command.equals("placement"))placement(sender);
        else if(command.equals("pacing"))pacing(sender,PasteHookStatus.pacing);
        else if(command.equals("paste")){if(view.equals("all")||view.equals("preparation"))preparation(sender);if(view.equals("all")||view.equals("placement"))placement(sender);if(view.equals("pacing"))pacing(sender,PasteHookStatus.pastePacing);}
        else if(command.equals("undo")||command.equals("redo"))history(sender,command.equals("redo"),view);
        else if(command.equals("history")){history(sender,false,"all");history(sender,true,"all");}
        else send(sender,getCommandUsage(sender));
    }
    public List addTabCompletionOptions(ICommandSender sender,String[] args){
        if(args.length==1)return getListOfStringsMatchingLastWord(args,"status","stats","preparation","placement","history","pacing","paste","undo","redo","profiling");
        if(args.length==2&&args[0].equalsIgnoreCase("profiling"))return getListOfStringsMatchingLastWord(args,"sample","full","off","native","optimized");
        if(args.length==2&&(args[0].equalsIgnoreCase("paste")||args[0].equalsIgnoreCase("undo")||args[0].equalsIgnoreCase("redo")))return getListOfStringsMatchingLastWord(args,"preparation","placement","pacing");return null;
    }
    private static void preparation(ICommandSender sender){
        send(sender,"Paste preparation: operation="+PasteHookStatus.pasteOperationId+" phase="+PasteHookStatus.activePhase+" captureStage="+PasteHookStatus.captureWorkStage+" preparationComplete="+PasteHookStatus.pastePreparationComplete);
        send(sender,"sourceCellsVisited="+PasteHookStatus.snapshotProcessed.get()+" sourceCellsTotal="+PasteHookStatus.snapshotTotalEstimate.get()+" sourceCellsRemaining="+PasteHookStatus.pasteSourceCellsRemaining.get()+" mutationsPlanned="+PasteHookStatus.pastePlannedBlocks.get()+" mutationsSubmitted="+PasteHookStatus.pasteSubmittedBlocks.get());
        send(sender,"capturePagesResident="+PasteHookStatus.pasteCapturePagesResident.get()+" capturePagesReleased="+PasteHookStatus.pasteCapturePagesReleased.get()+" workerTasksCompleted="+PasteHookStatus.pasteWorkerTasksCompleted.get()+" workerTasksSubmitted="+PasteHookStatus.pasteWorkerTasksSubmitted.get()+" workerTasksActive="+PasteHookStatus.pasteWorkerActive.get());
        send(sender,"captureTargetMillis="+OverdriveEditSummary.ms(PasteHookStatus.captureTargetNanos.get())+" submissionTargetMillis="+OverdriveEditSummary.ms(PasteHookStatus.submissionTargetNanos.get())+" liveMemoryBytes="+PasteHookStatus.pasteLiveMemoryBytes.get()+" peakLiveMemoryBytes="+PasteHookStatus.pastePeakLiveMemoryBytes.get()+" spillBytes="+PasteHookStatus.pasteSpillBytes.get()+" waitReason="+PasteHookStatus.pastePacing.waitReason());
        pacingRates(sender,PasteHookStatus.pastePacing);
    }
    private static void profiling(ICommandSender sender,String mode){
        if(mode.equals("sample"))PlacementProfile.sampleEvery=64;
        else if(mode.equals("full"))PlacementProfile.sampleEvery=1;
        else if(mode.equals("off"))PlacementProfile.sampleEvery=0;
        else if(mode.equals("native"))PlacementProfile.optimized=false;
        else if(mode.equals("optimized"))PlacementProfile.optimized=true;
        else if(!mode.equals("all")){send(sender,"profiling [sample|full|off|native|optimized]");return;}
        if(!mode.equals("all"))send(sender,"Profiling mode: optimized="+PlacementProfile.optimized+" sampleEvery="+PlacementProfile.sampleEvery+". Use a new equivalent paste for each comparison; mode changes do not reset the current profile.");
        PlacementProfile p=PlacementProfile.latest;if(p==null)send(sender,"No accelerated mutation profile yet");else for(String line:p.describe())send(sender,line);
    }
    private static void placement(ICommandSender sender){
        send(sender,"Paste placement: operation="+PasteHookStatus.pasteOperationId+" stage="+PasteHookStatus.pastePlacementStage+" committedMutations="+PasteHookStatus.pasteCommittedBlocks.get()+" remainingMutations="+PasteHookStatus.commitRemaining.get()+" complete="+PasteHookStatus.commitCompletedNormally);
        send(sender,"stage1Remaining="+PasteHookStatus.reorderStage1Remaining.get()+" stage2Remaining="+PasteHookStatus.reorderStage2Remaining.get()+" stage3Remaining="+PasteHookStatus.reorderStage3Remaining.get()+" tilesCommitted="+PasteHookStatus.pasteCommittedTiles.get()+" entitiesCommitted="+PasteHookStatus.pasteCommittedEntities.get()+" entitiesPrepared="+PasteHookStatus.pastePreparedEntities.get());
        send(sender,"reorderTargetMillis="+OverdriveEditSummary.ms(PasteHookStatus.commitTargetNanos.get())+" commitResumes="+PasteHookStatus.commitResumeCalls.get()+" placementsLastResume="+PasteHookStatus.placementsThisResume.get()+" commitActiveMillis="+PasteHookStatus.commitStateActiveServerMillis.get()+" commitWallMillis="+PasteHookStatus.commitStateElapsedWallMillis.get()+" waitReason="+PasteHookStatus.pastePacing.waitReason());
        pacingRates(sender,PasteHookStatus.pastePacing);
    }
    private static void history(ICommandSender sender,boolean redo,String view){
        PasteHookStatus.HistoryProgress p=redo?PasteHookStatus.redoProgress:PasteHookStatus.undoProgress;
        send(sender,(redo?"Redo":"Undo")+": operation="+p.operationId+" phase="+p.phase+" historyEntriesRemaining="+p.entriesRemaining+" waitReason="+p.waitReason);
        if(view.equals("all")||view.equals("preparation"))send(sender,"historyRecordsStaged="+p.processed+" selectedHistoryRecordsTotal="+p.total+" liveMemoryBytes="+p.liveBytes+" peakLiveMemoryBytes="+p.peakBytes);
        if(view.equals("all")||view.equals("placement"))send(sender,"blocksCommitted="+p.committed+" entitiesApplied="+p.entities+" stage1Remaining="+p.stage1+" stage2Remaining="+p.stage2+" stage3Remaining="+p.stage3);
        if(view.equals("pacing"))pacing(sender,p.pacing);else pacingRates(sender,p.pacing);
    }
    private static void pacing(ICommandSender sender,com.glowingfederal.worldeditoverdrive.integration.PastePacingDiagnostics diagnostics){for(String line:diagnostics.describe())send(sender,line);}
    private static void pacingRates(ICommandSender sender,com.glowingfederal.worldeditoverdrive.integration.PastePacingDiagnostics diagnostics){for(String line:diagnostics.describe())if(line.startsWith("pasteRecentPlacementsPerSecond="))send(sender,line);}
    private void status(ICommandSender sender){
        ModContainer we=Loader.instance().getIndexedModList().get("worldedit");OverdriveCoordinator coordinator=mod.getCoordinator();
        OverdriveConfiguration c=mod.getConfiguration();
        send(sender,"WorldEdit="+(we==null?"not present":we.getVersion())+" Overdrive="+WorldEditOverdrive.VERSION+" hook="+(Stage4HookStatus.activeSetCommandHookInstalled?"ACTIVE":"INACTIVE"));
        send(sender,"corePlugin="+Stage4HookStatus.corePluginLoaded+" transformer="+Stage4HookStatus.transformerRegistered+" selectionCommandSeen="+Stage4HookStatus.selectionCommandSeen+" activeDescriptorMatched="+Stage4HookStatus.selectionCommandDescriptorMatched);
        send(sender,"legacySetBlocksHookInstalled="+Stage4HookStatus.legacySetBlocksHookInstalled+" activeSetCommandHookInstalled="+Stage4HookStatus.activeSetCommandHookInstalled);
        send(sender,"hookReason="+Stage4HookStatus.hookReason);
        send(sender,"operationSupport set="+hooked(Stage4HookStatus.activeSetCommandHookInstalled)+" paste="+(PasteHookStatus.pasteHookInstalled?"ACTIVE":"UNAVAILABLE")+" replace="+hooked(CommandHookStatus.replaceHookInstalled)+" walls="+hooked(CommandHookStatus.geometryHookInstalled)+" faces="+hooked(CommandHookStatus.geometryHookInstalled)+" outline="+hooked(CommandHookStatus.geometryHookInstalled)+" center="+hooked(CommandHookStatus.geometryHookInstalled)+" overlay="+hooked(CommandHookStatus.overlayHookInstalled)+" naturalize="+hooked(CommandHookStatus.overlayHookInstalled)+" stack="+hooked(CommandHookStatus.copyMoveHookInstalled)+" move="+hooked(CommandHookStatus.copyMoveHookInstalled)+" line=VANILLA curve=VANILLA smooth=VANILLA deform=VANILLA hollow=VANILLA regen=VANILLA forest=VANILLA");
        send(sender,"commandHooks replace="+CommandHookStatus.replaceHookInstalled+" geometry="+CommandHookStatus.geometryHookInstalled+" copyMove="+CommandHookStatus.copyMoveHookInstalled+" overlay="+CommandHookStatus.overlayHookInstalled);
        send(sender,"commandBridges replace="+CommandHookStatus.replaceBridgeInvoked.get()+" geometry="+CommandHookStatus.geometryBridgeInvoked.get()+" copyMove="+CommandHookStatus.copyMoveBridgeInvoked.get()+" overlay="+CommandHookStatus.overlayBridgeInvoked.get());
        send(sender,"commandAccelerated replace="+CommandHookStatus.replaceAccelerated.get()+" geometry="+CommandHookStatus.geometryAccelerated.get()+" stack="+CommandHookStatus.stackAccelerated.get()+" move="+CommandHookStatus.moveAccelerated.get()+" overlay="+CommandHookStatus.overlayAccelerated.get()+" naturalize="+CommandHookStatus.naturalizeAccelerated.get());
        send(sender,"lastOperationType="+CommandHookStatus.lastOperationType+" lastOperationFallbackReason="+CommandHookStatus.lastOperationFallbackReason+" snapshotMillis="+CommandHookStatus.lastOperationSnapshotMillis.get()+" planMillis="+CommandHookStatus.lastOperationPlanMillis.get()+" commitMillis="+CommandHookStatus.lastOperationCommitMillis.get()+" wallMillis="+CommandHookStatus.lastOperationWallMillis.get());
        send(sender,"bridge="+Stage4HookStatus.bridgeInvocations.get()+" accelerated="+Stage4HookStatus.acceleratedInvocations.get()+" fallbacks="+Stage4HookStatus.fallbackInvocations.get()+" lastFallback="+Stage4HookStatus.lastFallbackReason);
        send(sender,"pasteHookInstalled="+PasteHookStatus.pasteHookInstalled+" pasteBridgeInvocations="+PasteHookStatus.pasteBridgeInvocations.get()+" pasteAccelerated="+PasteHookStatus.pasteAccelerated.get()+" pasteFallbacks="+PasteHookStatus.pasteFallbacks.get()+" lastPasteFallbackReason="+PasteHookStatus.lastPasteFallbackReason);
        send(sender,"pasteDeferredActive="+PasteHookStatus.pasteDeferredActive.get()+" pasteDeferredCompleted="+PasteHookStatus.pasteDeferredCompleted.get()+" pasteDeferredFailed="+PasteHookStatus.pasteDeferredFailed.get()+" lastPasteDeferredReason="+PasteHookStatus.lastPasteDeferredReason);
        send(sender,"pasteAccelerationFallbacks="+PasteHookStatus.pasteAccelerationFallbacks.get()+" lastPasteAccelerationFallbackReason="+PasteHookStatus.lastPasteAccelerationFallbackReason);
        send(sender,"pasteAdmissionRejectedLifetime="+PasteHookStatus.pasteAdmissionRejected.get()+" lastPasteAdmissionRejection="+PasteHookStatus.lastPasteAdmissionRejection+" captureWorkStage="+PasteHookStatus.captureWorkStage+" captureSlices="+PasteHookStatus.captureSlices.get()+" capturePagesAllocated="+PasteHookStatus.capturePagesAllocated.get()+" maxCaptureSliceMillis="+OverdriveEditSummary.ms(PasteHookStatus.maxCaptureSliceNanos.get()));
        send(sender,"pastePlanningActive="+PasteHookStatus.pastePlanningActive.get()+" pasteCommitActive="+PasteHookStatus.pasteCommitActive.get()+" lastPasteSourceCellsVisited="+PasteHookStatus.pastePreparedBlocks.get()+" lastPasteSourceAirCells="+PasteHookStatus.pasteSourceAirCells.get()+" lastPasteIgnoreAirFilteredCells="+PasteHookStatus.pasteIgnoreAirFilteredCells.get());
        send(sender,"lastPasteDestinationMatchedCells="+PasteHookStatus.pasteDestinationMatchedCells.get()+" lastPasteOtherwiseFilteredCells="+PasteHookStatus.pasteOtherwiseFilteredCells.get()+" lastPastePlannedMutations="+PasteHookStatus.pastePlannedBlocks.get()+" lastPasteSubmittedMutations="+PasteHookStatus.pasteSubmittedBlocks.get()+" lastPasteCommittedMutations="+PasteHookStatus.pasteCommittedBlocks.get());
        send(sender,"lastPastePreparedTiles="+PasteHookStatus.pastePreparedTiles.get()+" lastPasteCommittedTiles="+PasteHookStatus.pasteCommittedTiles.get()+" lastPastePreparedEntities="+PasteHookStatus.pastePreparedEntities.get()+" lastPasteCommittedEntities="+PasteHookStatus.pasteCommittedEntities.get()+" lastPasteTransformedBlocks="+PasteHookStatus.pasteTransformedBlocks.get()+" pasteTransform="+PasteHookStatus.lastPasteTransform+" pasteIgnoreAir="+PasteHookStatus.lastPasteIgnoreAir);
        send(sender,"lastPastePrepareMillis="+PasteHookStatus.lastPastePrepareMillis.get()+" lastPastePlanMillis="+PasteHookStatus.lastPastePlanMillis.get()+" lastPasteCommitMillis="+PasteHookStatus.lastPasteCommitMillis.get());
        send(sender,"activePhase="+PasteHookStatus.activePhase+" snapshotProcessed="+PasteHookStatus.snapshotProcessed.get()+" snapshotTotalEstimate="+PasteHookStatus.snapshotTotalEstimate.get()+" workerQueuedChunks="+PasteHookStatus.workerQueuedChunks.get()+" workerCompletedChunks="+PasteHookStatus.workerCompletedChunks.get()+" commitRemaining="+PasteHookStatus.commitRemaining.get());
        send(sender,"workerTasksSubmitted="+PasteHookStatus.pasteWorkerTasksSubmitted.get()+" workerTasksCompleted="+PasteHookStatus.pasteWorkerTasksCompleted.get()+" workerActive="+PasteHookStatus.pasteWorkerActive.get()+" workerPlanMillis="+PasteHookStatus.pasteWorkerPlanNanos.get()/1000000L+" averageWorkerTaskMillis="+(PasteHookStatus.pasteWorkerTasksCompleted.get()==0?0:PasteHookStatus.pasteWorkerPlanNanos.get()/1000000L/PasteHookStatus.pasteWorkerTasksCompleted.get())+" maxWorkerConcurrency="+PasteHookStatus.pasteWorkerMaxConcurrency.get());
        send(sender,"lastOperationCommandInterceptMillis="+PasteHookStatus.lastOperationCommandInterceptMillis.get()+" snapshotWallMillis="+PasteHookStatus.lastOperationSnapshotWallMillis.get()+" snapshotActiveMillis="+PasteHookStatus.lastOperationSnapshotActiveMillis.get()+" planWallMillis="+PasteHookStatus.lastOperationPlanWallMillis.get()+" commitWallMillis="+PasteHookStatus.lastOperationCommitWallMillis.get()+" commitActiveMillis="+PasteHookStatus.lastOperationCommitActiveMillis.get()+" wallMillis="+PasteHookStatus.lastOperationWallMillis.get()+" maxServerSliceMillis="+PasteHookStatus.lastOperationMaxServerSliceMillis.get());
        AdaptiveServerBudget budget=DeferredPasteManager.budget();
        send(sender,"serverBudgetMillis="+OverdriveEditSummary.ms(budget.budgetNanos())+" lastOverdriveTickWorkMillis="+OverdriveEditSummary.ms(budget.lastUsedNanos())+" serverHeadroomMillis="+OverdriveEditSummary.ms(budget.headroomNanos())+" maxOverdriveTickWorkMillis="+OverdriveEditSummary.ms(budget.maximumUsedNanos()));
        send(sender,"sourceCaptureServerMillis="+PasteHookStatus.sourceCaptureServerMillis.get()+" destinationCaptureServerMillis="+PasteHookStatus.destinationCaptureServerMillis.get()+" commitServerMillis="+PasteHookStatus.commitServerMillis.get()+" queueDrainServerMillis="+PasteHookStatus.queueDrainServerMillis.get()+" finalizationServerMillis="+PasteHookStatus.finalizationServerMillis.get());
        send(sender,"submittedSinceLastDrain="+PasteHookStatus.submittedSinceLastDrain.get()+" chunksSinceLastDrain="+PasteHookStatus.chunksSinceLastDrain.get()+" flushCount="+PasteHookStatus.flushCount.get()+" lastFlushMillis="+PasteHookStatus.lastFlushMillis.get()+" averageFlushMillis="+(PasteHookStatus.flushCount.get()==0?0:PasteHookStatus.totalFlushNanos.get()/1000000L/PasteHookStatus.flushCount.get())+" maxFlushMillis="+PasteHookStatus.maxFlushMillis.get()+" maxSubmissionSliceMillis="+PasteHookStatus.maxSubmissionSliceMillis.get()+" maxFinalFlushMillis="+PasteHookStatus.maxFinalFlushMillis.get()+" finalFlushQueuedMutations="+PasteHookStatus.finalFlushQueuedMutations.get()+" finalFlushChunks="+PasteHookStatus.finalFlushChunks.get()+" finalFlushMillis="+PasteHookStatus.finalFlushMillis.get()+" uninterruptibleFlushOverBudgetCount="+PasteHookStatus.uninterruptibleFlushOverBudgetCount.get());
        send(sender,"queueImplementationClass="+PasteHookStatus.queueImplementationClass+" queueEnabled="+PasteHookStatus.queueEnabled+" editSessionExtentClass="+PasteHookStatus.editSessionExtentClass);
        send(sender,"reorderEnabled="+PasteHookStatus.queueEnabled+" incrementalCommitSupported="+PasteHookStatus.incrementalCommitSupported+" incrementalCommitSlices="+PasteHookStatus.incrementalCommitSlices.get()+" commitResumeCalls="+PasteHookStatus.commitResumeCalls.get()+" maxCommitResumeMillis="+PasteHookStatus.maxCommitResumeMillis.get()+" commitOperationClass="+PasteHookStatus.commitOperationClass+" commitOperationRemaining="+PasteHookStatus.commitOperationRemaining.get()+" finalSynchronousFlushCount="+PasteHookStatus.finalSynchronousFlushCount.get());
        send(sender,"topLevelCommitReturnedNull="+PasteHookStatus.topLevelCommitReturnedNull+" activeCommitOperationClassBeforeResume="+PasteHookStatus.activeCommitOperationClassBeforeResume+" activeCommitOperationClassAfterResume="+PasteHookStatus.activeCommitOperationClassAfterResume+" commitCompletedNormally="+PasteHookStatus.commitCompletedNormally);
        send(sender,"reorderStage1Remaining="+PasteHookStatus.reorderStage1Remaining.get()+" reorderStage2Remaining="+PasteHookStatus.reorderStage2Remaining.get()+" reorderStage3Remaining="+PasteHookStatus.reorderStage3Remaining.get()+" blockMapPlacementsThisResume="+PasteHookStatus.blockMapPlacementsThisResume.get()+" stage3PlacementsThisResume="+PasteHookStatus.stage3PlacementsThisResume.get()+" stage3ChainsThisResume="+PasteHookStatus.stage3ChainsThisResume.get());
        send(sender,"deadlineYieldCount="+PasteHookStatus.deadlineYieldCount.get()+" blockMapDeadlineYields="+PasteHookStatus.blockMapDeadlineYields.get()+" stage3DeadlineYields="+PasteHookStatus.stage3DeadlineYields.get());
        send(sender,"deadlineBudgetNanos="+PasteHookStatus.deadlineBudgetNanos.get()+" deadlineRemainingNanosAtResumeEntry="+PasteHookStatus.deadlineRemainingNanosAtResumeEntry.get()+" deadlineRemainingNanosAtFirstPlacement="+PasteHookStatus.deadlineRemainingNanosAtFirstPlacement.get()+" resumeElapsedNanos="+PasteHookStatus.resumeElapsedNanos.get()+" placementsThisResume="+PasteHookStatus.placementsThisResume.get()+" deadlineExpiredAtEntry="+PasteHookStatus.deadlineExpiredAtEntry.get()+" deadlineExpiredAfterFirstPlacement="+PasteHookStatus.deadlineExpiredAfterFirstPlacement.get());
        send(sender,"commitStateElapsedWallMillis="+PasteHookStatus.commitStateElapsedWallMillis.get()+" commitStateActiveServerMillis="+PasteHookStatus.commitStateActiveServerMillis.get()+" maxResumeStage="+PasteHookStatus.maxCommitResumeStage+" maxResumePlacements="+PasteHookStatus.maxCommitResumePlacements.get()+" maxResumeChains="+PasteHookStatus.maxCommitResumeChains.get()+" maxResumeBeganExpired="+PasteHookStatus.maxCommitResumeBeganExpired+" maxDownstreamMutationDestinationChunk="+PasteHookStatus.maxDownstreamMutationDestinationChunk+" maxDownstreamMutationMillis="+OverdriveEditSummary.ms(PasteHookStatus.maxDownstreamMutationNanos.get()));
        send(sender,"submissionElapsedWallMillis="+PasteHookStatus.submissionElapsedWallMillis.get()+" submissionActiveServerMillis="+PasteHookStatus.submissionActiveServerMillis.get()+" finalizationElapsedWallMillis="+PasteHookStatus.finalizationElapsedWallMillis.get());
        long resumes=PasteHookStatus.commitResumeCalls.get(),resumeNanos=PasteHookStatus.totalCommitResumeNanos.get(),allowanceNanos=PasteHookStatus.totalResumeAllowanceNanos.get();
        send(sender,"averageCommitResumeMillis="+OverdriveEditSummary.ms(resumes==0?0:resumeNanos/resumes)+" medianCommitResumeLowerMillis="+OverdriveEditSummary.ms(PasteHookStatus.medianCommitResumeLowerNanos.get())+" medianCommitResumeUpperMillis="+(PasteHookStatus.medianCommitResumeUpperNanos.get()==Long.MAX_VALUE?"unbounded":OverdriveEditSummary.ms(PasteHookStatus.medianCommitResumeUpperNanos.get()))+" maxCommitResumeExactMillis="+OverdriveEditSummary.ms(PasteHookStatus.maxCommitResumeNanos.get())+" resumeAllowanceUsedPercent="+(allowanceNanos==0?0D:100D*resumeNanos/allowanceNanos)+" commitResumesOver50Millis="+PasteHookStatus.commitResumesOver50Millis.get());
        send(sender,"pasteCaptureTargetMillis="+OverdriveEditSummary.ms(PasteHookStatus.captureTargetNanos.get())+" pasteSubmissionTargetMillis="+OverdriveEditSummary.ms(PasteHookStatus.submissionTargetNanos.get())+" pasteReorderTargetMillis="+OverdriveEditSummary.ms(PasteHookStatus.commitTargetNanos.get())+" commitPacingStage="+PasteHookStatus.commitPacingStage+" commitBudgetIncreases="+PasteHookStatus.commitBudgetIncreases.get()+" commitBudgetDecreases="+PasteHookStatus.commitBudgetDecreases.get()+" pastePacingState=per-paste");
        send(sender,"maxStage3PreparationMillis="+OverdriveEditSummary.ms(PasteHookStatus.maxStage3PreparationNanos.get())+" maxDependencyChainMillis="+OverdriveEditSummary.ms(PasteHookStatus.maxDependencyChainNanos.get())+" maxDownstreamMutationDetail="+PasteHookStatus.maxDownstreamMutationDetail);
        for(String pacing:PasteHookStatus.pacing.describe())send(sender,pacing);
        send(sender,"lastPasteGraphDiagnostic="+PasteHookStatus.lastPasteGraphDiagnostic);
        send(sender,"pasteRuntimeShape="+PasteHookStatus.runtimeShape()+" forwardExtentCopySeen="+PasteHookStatus.forwardExtentCopySeen()+" pasteRuntimeShapeCompatible="+PasteHookStatus.runtimeShapeCompatible()+" pasteBytecodeModified="+PasteHookStatus.pasteBytecodeModified);
        send(sender,"pasteHookReason="+PasteHookStatus.hookReason);
        send(sender,"pasteEstimatedTotalSourceBytes="+PasteHookStatus.pasteEstimatedTotalSourceBytes.get()+" pasteLiveMemoryBytes="+PasteHookStatus.pasteLiveMemoryBytes.get()+" pastePeakLiveMemoryBytes="+PasteHookStatus.pastePeakLiveMemoryBytes.get()+" pasteMemoryBudgetBytes="+PasteHookStatus.pasteMemoryBudgetBytes.get());
        send(sender,"pasteStateMemoryBytes="+PasteHookStatus.pasteStateMemoryBytes.get()+" pasteCaptureMemoryBytes="+PasteHookStatus.pasteCaptureMemoryBytes.get()+" pastePlanningMemoryBytes="+PasteHookStatus.pastePlanningMemoryBytes.get()+" pasteCommitMemoryBytes="+PasteHookStatus.pasteCommitMemoryBytes.get()+" pasteHistoryMemoryBytes="+PasteHookStatus.pasteHistoryMemoryBytes.get()+" pasteEntityMemoryBytes="+PasteHookStatus.pasteEntityMemoryBytes.get()+" pasteWorkerMemoryBytes="+PasteHookStatus.pasteWorkerMemoryBytes.get());
        send(sender,"pasteGlobalLiveMemoryBytes="+PasteHookStatus.pasteGlobalLiveMemoryBytes.get()+" pasteGlobalPeakLiveMemoryBytes="+PasteHookStatus.pasteGlobalPeakLiveMemoryBytes.get()+" pasteSpillBytes="+PasteHookStatus.pasteSpillBytes.get()+" pasteMemoryBackpressureYields="+PasteHookStatus.pasteMemoryBackpressureYields.get()+" pasteMemoryBackpressureReason="+PasteHookStatus.pasteMemoryBackpressureReason+" workerIoCodecMillis="+PasteHookStatus.pasteWorkerIoNanos.get()/1000000L);
        send(sender,"pasteSourceCellsRemaining="+PasteHookStatus.pasteSourceCellsRemaining.get()+" pasteCapturePagesResident="+PasteHookStatus.pasteCapturePagesResident.get()+" pasteCapturePagesReleased="+PasteHookStatus.pasteCapturePagesReleased.get());
        send(sender,"pasteOperationId="+PasteHookStatus.pasteOperationId+" pastePreparationComplete="+PasteHookStatus.pastePreparationComplete+" pastePlacementStage="+PasteHookStatus.pastePlacementStage);
        send(sender,"historyCommandHookInstalled="+PasteHookStatus.historyCommandHookInstalled+" historySessionHookInstalled="+PasteHookStatus.historySessionHookInstalled+" historyReplayPhase="+PasteHookStatus.historyReplayPhase+" historyReplayProcessed="+PasteHookStatus.historyReplayProcessed.get()+" historyReplayTotal="+PasteHookStatus.historyReplayTotal.get()+" historyReplayLiveMemoryBytes="+PasteHookStatus.historyReplayLiveMemoryBytes.get()+" historyReplayPeakLiveMemoryBytes="+PasteHookStatus.historyReplayPeakLiveMemoryBytes.get());
        send(sender,"coordinator="+(coordinator==null?"stopped":"running")+" workers="+c.preparationWorkers+" globalMemory="+c.maxPreparedBytes+" operationMemory="+c.maxPreparedBytesPerOperation+" coordinatorCommitTick="+OverdriveEditSummary.ms(c.commitBudgetNanos)+"ms (separate from paste pacing)");
    }
    private void stats(ICommandSender sender){OverdriveEditSummary s=OverdriveSummaries.latest();if(s==null){send(sender,"No accelerated operation snapshot");return;}
        send(sender,s.format());send(sender,"packets: sparse="+s.sparsePackets+" chunk="+s.chunkPackets+" tile="+s.tilePackets+" result="+(s.success?"SUCCESS":"FAILURE")+(s.failedPhase==null?"":" phase="+s.failedPhase+" error="+s.failureText));}
    private static void send(ICommandSender sender,String text){sender.addChatMessage(new ChatComponentText("[WorldEditOverdrive] "+text));}
    private static String active(boolean installed){return installed?"ACTIVE":"UNAVAILABLE";}
    private static String hooked(boolean installed){return installed?"HOOKED":"UNAVAILABLE";}
}
