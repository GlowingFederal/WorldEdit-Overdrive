package com.glowingfederal.worldeditoverdrive;

import com.glowingfederal.worldeditoverdrive.execution.OverdriveConfiguration;
import com.glowingfederal.worldeditoverdrive.execution.OverdriveCoordinator;
import com.glowingfederal.worldeditoverdrive.integration.OverdriveEditSummary;
import com.glowingfederal.worldeditoverdrive.integration.OverdriveSummaries;
import com.glowingfederal.worldeditoverdrive.integration.Stage4HookStatus;
import com.glowingfederal.worldeditoverdrive.integration.PasteHookStatus;
import com.glowingfederal.worldeditoverdrive.integration.DeferredPasteManager;
import com.glowingfederal.worldeditoverdrive.integration.CommandHookStatus;
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
    public String getCommandUsage(ICommandSender sender){return "/overdrive <status|stats>";}
    public int getRequiredPermissionLevel(){return 2;}
    public List getCommandAliases(){return Arrays.asList("worldeditoverdrive");}
    public void processCommand(ICommandSender sender,String[] args){
        if(args.length!=1){send(sender,getCommandUsage(sender));return;}
        if("status".equalsIgnoreCase(args[0]))status(sender);
        else if("stats".equalsIgnoreCase(args[0]))stats(sender);
        else send(sender,getCommandUsage(sender));
    }
    private void status(ICommandSender sender){
        ModContainer we=Loader.instance().getIndexedModList().get("worldedit");OverdriveCoordinator coordinator=mod.getCoordinator();
        OverdriveConfiguration c=mod.getConfiguration();
        send(sender,"WorldEdit="+(we==null?"not present":we.getVersion())+" Overdrive="+WorldEditOverdrive.VERSION+" hook="+(Stage4HookStatus.activeSetCommandHookInstalled?"ACTIVE":"INACTIVE"));
        send(sender,"corePlugin="+Stage4HookStatus.corePluginLoaded+" transformer="+Stage4HookStatus.transformerRegistered+" selectionCommandSeen="+Stage4HookStatus.selectionCommandSeen+" activeDescriptorMatched="+Stage4HookStatus.selectionCommandDescriptorMatched);
        send(sender,"legacySetBlocksHookInstalled="+Stage4HookStatus.legacySetBlocksHookInstalled+" activeSetCommandHookInstalled="+Stage4HookStatus.activeSetCommandHookInstalled);
        send(sender,"hookReason="+Stage4HookStatus.hookReason);
        send(sender,"operationSupport set="+(Stage4HookStatus.activeSetCommandHookInstalled?"ACTIVE":"UNAVAILABLE")+" paste="+(PasteHookStatus.pasteHookInstalled?"ACTIVE":"UNAVAILABLE")+" replace="+active(CommandHookStatus.replaceHookInstalled)+" walls="+active(CommandHookStatus.geometryHookInstalled)+" faces="+active(CommandHookStatus.geometryHookInstalled)+" outline="+active(CommandHookStatus.geometryHookInstalled)+" center="+active(CommandHookStatus.geometryHookInstalled)+" overlay="+active(CommandHookStatus.overlayHookInstalled)+" naturalize="+active(CommandHookStatus.overlayHookInstalled)+" stack="+active(CommandHookStatus.copyMoveHookInstalled)+" move="+active(CommandHookStatus.copyMoveHookInstalled)+" line=VANILLA curve=VANILLA smooth=VANILLA deform=VANILLA hollow=VANILLA regen=VANILLA forest=VANILLA");
        send(sender,"commandHooks replace="+CommandHookStatus.replaceHookInstalled+" geometry="+CommandHookStatus.geometryHookInstalled+" copyMove="+CommandHookStatus.copyMoveHookInstalled+" overlay="+CommandHookStatus.overlayHookInstalled);
        send(sender,"commandBridges replace="+CommandHookStatus.replaceBridgeInvoked.get()+" geometry="+CommandHookStatus.geometryBridgeInvoked.get()+" copyMove="+CommandHookStatus.copyMoveBridgeInvoked.get()+" overlay="+CommandHookStatus.overlayBridgeInvoked.get());
        send(sender,"commandAccelerated replace="+CommandHookStatus.replaceAccelerated.get()+" geometry="+CommandHookStatus.geometryAccelerated.get()+" stack="+CommandHookStatus.stackAccelerated.get()+" move="+CommandHookStatus.moveAccelerated.get()+" overlay="+CommandHookStatus.overlayAccelerated.get()+" naturalize="+CommandHookStatus.naturalizeAccelerated.get());
        send(sender,"lastOperationType="+CommandHookStatus.lastOperationType+" lastOperationFallbackReason="+CommandHookStatus.lastOperationFallbackReason+" snapshotMillis="+CommandHookStatus.lastOperationSnapshotMillis.get()+" planMillis="+CommandHookStatus.lastOperationPlanMillis.get()+" commitMillis="+CommandHookStatus.lastOperationCommitMillis.get()+" wallMillis="+CommandHookStatus.lastOperationWallMillis.get());
        send(sender,"bridge="+Stage4HookStatus.bridgeInvocations.get()+" accelerated="+Stage4HookStatus.acceleratedInvocations.get()+" fallbacks="+Stage4HookStatus.fallbackInvocations.get()+" lastFallback="+Stage4HookStatus.lastFallbackReason);
        send(sender,"pasteHookInstalled="+PasteHookStatus.pasteHookInstalled+" pasteBridgeInvocations="+PasteHookStatus.pasteBridgeInvocations.get()+" pasteAccelerated="+PasteHookStatus.pasteAccelerated.get()+" pasteFallbacks="+PasteHookStatus.pasteFallbacks.get()+" lastPasteFallbackReason="+PasteHookStatus.lastPasteFallbackReason);
        send(sender,"pasteDeferredActive="+PasteHookStatus.pasteDeferredActive.get()+" pasteDeferredCompleted="+PasteHookStatus.pasteDeferredCompleted.get()+" pasteDeferredFailed="+PasteHookStatus.pasteDeferredFailed.get()+" lastPasteDeferredReason="+PasteHookStatus.lastPasteDeferredReason);
        send(sender,"pasteAccelerationFallbacks="+PasteHookStatus.pasteAccelerationFallbacks.get()+" lastPasteAccelerationFallbackReason="+PasteHookStatus.lastPasteAccelerationFallbackReason);
        send(sender,"pastePlanningActive="+PasteHookStatus.pastePlanningActive.get()+" pasteCommitActive="+PasteHookStatus.pasteCommitActive.get()+" pastePreparedBlocks="+PasteHookStatus.pastePreparedBlocks.get()+" pastePlannedBlocks="+PasteHookStatus.pastePlannedBlocks.get()+" pasteSubmittedBlocks="+PasteHookStatus.pasteSubmittedBlocks.get()+" pasteCommittedBlocks="+PasteHookStatus.pasteCommittedBlocks.get());
        send(sender,"pastePreparedTiles="+PasteHookStatus.pastePreparedTiles.get()+" pasteCommittedTiles="+PasteHookStatus.pasteCommittedTiles.get()+" pastePreparedEntities="+PasteHookStatus.pastePreparedEntities.get()+" pasteCommittedEntities="+PasteHookStatus.pasteCommittedEntities.get()+" pasteTransformedBlocks="+PasteHookStatus.pasteTransformedBlocks.get()+" pasteTransform="+PasteHookStatus.lastPasteTransform+" pasteIgnoreAir="+PasteHookStatus.lastPasteIgnoreAir);
        send(sender,"lastPastePrepareMillis="+PasteHookStatus.lastPastePrepareMillis.get()+" lastPastePlanMillis="+PasteHookStatus.lastPastePlanMillis.get()+" lastPasteCommitMillis="+PasteHookStatus.lastPasteCommitMillis.get());
        AdaptiveServerBudget budget=DeferredPasteManager.budget();
        send(sender,"commitBudgetMillis="+OverdriveEditSummary.ms(budget.budgetNanos())+" commitUsedMillis="+OverdriveEditSummary.ms(budget.lastUsedNanos())+" serverHeadroomMillis="+OverdriveEditSummary.ms(budget.headroomNanos())+" maxCommitTickMillis="+OverdriveEditSummary.ms(budget.maximumUsedNanos()));
        send(sender,"lastPasteGraphDiagnostic="+PasteHookStatus.lastPasteGraphDiagnostic);
        send(sender,"pasteRuntimeShape="+PasteHookStatus.runtimeShape()+" forwardExtentCopySeen="+PasteHookStatus.forwardExtentCopySeen()+" pasteRuntimeShapeCompatible="+PasteHookStatus.runtimeShapeCompatible()+" pasteBytecodeModified="+PasteHookStatus.pasteBytecodeModified);
        send(sender,"pasteHookReason="+PasteHookStatus.hookReason);
        send(sender,"coordinator="+(coordinator==null?"stopped":"running")+" workers="+c.preparationWorkers+" globalMemory="+c.maxPreparedBytes+" operationMemory="+c.maxPreparedBytesPerOperation+" commitTick="+OverdriveEditSummary.ms(c.commitBudgetNanos)+"ms");
    }
    private void stats(ICommandSender sender){OverdriveEditSummary s=OverdriveSummaries.latest();if(s==null){send(sender,"No accelerated operation snapshot");return;}
        send(sender,s.format());send(sender,"packets: sparse="+s.sparsePackets+" chunk="+s.chunkPackets+" tile="+s.tilePackets+" result="+(s.success?"SUCCESS":"FAILURE")+(s.failedPhase==null?"":" phase="+s.failedPhase+" error="+s.failureText));}
    private static void send(ICommandSender sender,String text){sender.addChatMessage(new ChatComponentText("[WorldEditOverdrive] "+text));}
    private static String active(boolean installed){return installed?"ACTIVE":"UNAVAILABLE";}
}
