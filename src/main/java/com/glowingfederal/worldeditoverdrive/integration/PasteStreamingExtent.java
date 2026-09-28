package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.blocks.BlockType;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.reorder.MultiStageReorder;
import com.sk89q.worldedit.function.operation.Operation;

/** Same Enhanced stage classification and torch removal, with disk queues.
 * All upstream masks/history/limits and downstream validators/quirks stay native. */
final class PasteStreamingExtent extends MultiStageReorder {
    final PasteStreamStorage storage;
    MultiStageReorder original;
    PasteStreamingExtent(Extent extent, boolean enabled, PasteStreamStorage storage) { super(extent, enabled); this.storage = storage; }
    public boolean setBlock(Vector location, BaseBlock block) throws WorldEditException {
        BaseBlock existing = getLazyBlock(location);
        if (!isEnabled()) {
            boolean changed = placeDirect(location, block); recordDirect(changed, block); return changed;
        }
        PasteDiskJournal stage;
        if (BlockType.shouldPlaceLast(block.getType())) stage = storage.stage2;
        else if (BlockType.shouldPlaceFinal(block.getType())) stage = storage.stage3;
        else if (BlockType.shouldPlaceLast(existing.getType())) {
            placeDirect(location, new BaseBlock(BlockID.AIR)); boolean changed = placeDirect(location, block); recordDirect(changed, block); return changed;
        } else stage = storage.stage1;
        storage.append(stage, PasteDiskJournal.Record.block(location, null, block, 512L + storage.afterBytes), PasteMemoryBudget.Kind.COMMIT);
        return !(existing.getType() == block.getType() && existing.getData() == block.getData());
    }
    private void recordDirect(boolean changed, BaseBlock block) { if (changed) { storage.directChanged++; if (block.getNbtData() != null) storage.directTiles++; } }
    private boolean placeDirect(Vector location,BaseBlock block)throws WorldEditException {
        PlacementMutationBridge.begin(storage.placement,location.getBlockX(),location.getBlockY(),location.getBlockZ(),block.getId(),block.getNbtData()!=null);
        try{return getExtent().setBlock(location,block);}finally{PlacementMutationBridge.end();}
    }
    // The deferred owner drives this queue. Once exhausted, downstream commits
    // are still obtained through EditSession.commit(), never completeBlindly().
    public Operation commitBefore() { return null; }
}
