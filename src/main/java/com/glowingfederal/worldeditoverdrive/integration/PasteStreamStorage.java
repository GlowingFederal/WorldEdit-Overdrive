package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.PlayerDirection;
import com.sk89q.worldedit.blocks.BlockID;
import com.sk89q.worldedit.blocks.BlockType;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/** One serial worker owns all disk I/O. The server only appends bounded records
 * and consumes already decoded batches; it never waits on a worker or a file. */
final class PasteStreamStorage implements Closeable {
    static final long STATE_BYTES = 256L << 10;
    final PasteMemoryBudget.Account memory;
    File directory;
    PasteDiskJournal stage1, stage2, stage3, historyBlocks, historyEntities, entities;
    PasteDiskIndex index;
    PasteMemoryBudget.Ticket submission;
    long beforeBytes, afterBytes, directChanged, directTiles;
    long stage1Cursor, stage2Cursor, entityCursor, scan, head = -1, current = -1, walk;
    long stage3Decoded;
    boolean popping;
    volatile boolean cancelled;
    volatile long diskBytes;
    PasteStreamStorage(PasteMemoryBudget.Account memory) { this.memory = memory; }
    void initialize() throws IOException {
        directory = Files.createTempDirectory("worldedit-overdrive-paste-").toFile();
        stage1 = new PasteDiskJournal(directory, "stage1"); stage2 = new PasteDiskJournal(directory, "stage2"); stage3 = new PasteDiskJournal(directory, "stage3");
        historyBlocks = new PasteDiskJournal(directory, "history-blocks"); historyEntities = new PasteDiskJournal(directory, "history-entities"); entities = new PasteDiskJournal(directory, "entities");
        index = new PasteDiskIndex(directory);
    }
    boolean beginSubmission(long before, long after) {
        if (submission != null) throw new IllegalStateException("nested paste submission");
        beforeBytes = before; afterBytes = after;
        long required=4096L+before+after*3;
        // A single source payload must coexist with the fixed state and its
        // downstream copies. Draining cannot make that indivisible unit fit.
        if(required+after+STATE_BYTES+(16L<<10)>memory.limit())throw new IllegalArgumentException("indivisible paste payload exceeds live-memory budget");
        submission = memory.acquire(PasteMemoryBudget.Kind.WORKER, required);
        return submission != null;
    }
    void endSubmission() { if (submission != null) { submission.close(); submission = null; } }
    void append(PasteDiskJournal journal, PasteDiskJournal.Record record, PasteMemoryBudget.Kind kind) {
        if (submission == null) throw new IllegalStateException("paste write without preflight reservation");
        record.ticket = submission.split(kind, record.memory); journal.append(record);
    }
    void flush() throws IOException {
        try {
            stage1.flush(); stage2.flush();
            for (PasteDiskJournal.Record r : stage3.pending) {
                if (cancelled) return;
                long offset = stage3.appendDisk(r); index.put(r.x, r.y, r.z, offset); r.release();
            }
            stage3.releasePending();stage3.flushBuffers();historyBlocks.flush();historyEntities.flush();entities.flush();
            diskBytes = stage1.bytes + stage2.bytes + stage3.bytes + historyBlocks.bytes + historyEntities.bytes + entities.bytes + index.bytes();
        } finally { stage3.releasePending(); }
    }
    static final class Batch implements AutoCloseable {
        final List<PasteDiskJournal.Record> records = new ArrayList<PasteDiskJournal.Record>(PreparedClipboardView.PAGE_SIZE);
        PasteMemoryBudget.Ticket container;
        boolean done, pressure;
        long chains;
        public void close() { for (PasteDiskJournal.Record r : records) r.release(); records.clear(); if (container != null) { container.close(); container = null; } }
    }
    Batch readBatch(int stage) throws IOException {
        Batch batch = new Batch();
        batch.container = memory.acquire(PasteMemoryBudget.Kind.WORKER, 16L << 10);
        if (batch.container == null) { batch.pressure = true; return batch; }
        try {
            long bytes = 0;
            for (int steps = 0; steps < 4096 && !cancelled && batch.records.size() < PreparedClipboardView.PAGE_SIZE && bytes < (1L << 20); steps++) {
                PasteDiskJournal journal = stage == 1 ? stage1 : stage == 2 ? stage2 : stage == 3 ? stage3 : entities;
                long offset;
                PasteDiskIndex.Node node = null;
                if (stage == 3) {
                    if (!popping) {
                        if (current < 0) {
                            if (scan == index.size()) { batch.done = true; break; }
                            node = index.node(scan++); if (!node.remaining) continue;
                            current = node.slot; head = -1; walk++; batch.chains++;
                        } else node = index.node(current);
                        push(node);
                        int[] type = stage3.typeDataAt(node.record);
                        if ((type[0] == BlockID.WOODEN_DOOR || type[0] == BlockID.IRON_DOOR) && (type[1] & 8) == 0) pushExtra(node.x, node.y + 1, node.z);
                        if (type[0] == BlockID.MINECART_TRACKS || type[0] == BlockID.POWERED_RAIL || type[0] == BlockID.DETECTOR_RAIL || type[0] == BlockID.ACTIVATOR_RAIL) pushExtra(node.x, node.y - 1, node.z);
                        PlayerDirection attachment = BlockType.getAttachment(type[0], type[1]);
                        PasteDiskIndex.Node next = attachment == null ? null : index.find(node.x + attachment.vector().getBlockX(), node.y + attachment.vector().getBlockY(), node.z + attachment.vector().getBlockZ());
                        if (next == null || !next.remaining || next.walk == walk) { popping = true; current = -1; }
                        else current = next.slot;
                        continue;
                    }
                    node = index.node(head); offset = node.record;
                } else {
                    long cursor = stage == 1 ? stage1Cursor : stage == 2 ? stage2Cursor : entityCursor;
                    if (cursor == journal.count) { batch.done = true; break; } offset = journal.offset(cursor);
                }
                // Charge before decoding NBT, including transient decoder/string storage.
                long required = 512L + journal.memoryAt(offset) * 2L;
                PasteMemoryBudget.Ticket ticket = memory.acquire(stage == 4 ? PasteMemoryBudget.Kind.ENTITY : PasteMemoryBudget.Kind.COMMIT, required);
                if (ticket == null) { batch.pressure = true; break; }
                PasteDiskJournal.Record record;
                try { record = journal.readOffset(offset); record.ticket = ticket; }
                catch (Throwable failure) { ticket.close(); throw failure; }
                batch.records.add(record); bytes += required;
                if (stage == 3) {
                    if(node.next>=0&&(record.block.getId()==BlockID.WOODEN_DOOR||record.block.getId()==BlockID.IRON_DOOR)){
                        PasteDiskIndex.Node next=index.node(node.next);int[] type=stage3.typeDataAt(next.record);
                        record.pairWithNext=next.x==node.x&&next.z==node.z&&Math.abs((long)next.y-node.y)==1&&type[0]==record.block.getId()&&((type[1]^record.block.getData())&8)!=0;
                    }
                    node.remaining = false; index.write(node);head=node.next;stage3Decoded++;
                    if (head < 0) popping = false;
                } else if (stage == 1) stage1Cursor++; else if (stage == 2) stage2Cursor++; else entityCursor++;
            }
            return batch;
        } catch (Throwable failure) { batch.close(); throw failure; }
    }
    private void push(PasteDiskIndex.Node node) throws IOException { node.walk = walk; node.next = head; index.write(node); head = node.slot; }
    private void pushExtra(int x, int y, int z) throws IOException { PasteDiskIndex.Node extra = index.find(x, y, z); if (extra != null && extra.remaining && extra.walk != walk) push(extra); }
    void releasePending() {
        endSubmission();
        for (PasteDiskJournal journal : journals()) if (journal != null) journal.releasePending();
    }
    private PasteDiskJournal[] journals() { return new PasteDiskJournal[]{stage1, stage2, stage3, historyBlocks, historyEntities, entities}; }
    public void close() throws IOException {
        IOException failure = null;
        for (PasteDiskJournal journal : journals()) if (journal != null) try { journal.close(); } catch (IOException e) { failure = e; }
        if (index != null) try { index.close(); } catch (IOException e) { failure = e; }
        if (directory != null) { File[] files = directory.listFiles(); if (files != null) for (File f : files) if (!f.delete()) f.deleteOnExit(); if (!directory.delete()) directory.deleteOnExit(); }
        if (failure != null) throw failure;
    }
    void closeWorkFiles() throws IOException {
        // The native LocalSession retains only the two history journals after completion.
        IOException failure=null;
        for(PasteDiskJournal journal:new PasteDiskJournal[]{stage1,stage2,stage3,entities})if(journal!=null)try{journal.close();}catch(IOException e){failure=e;}
        if(index!=null)try{index.close();}catch(IOException e){failure=e;}
        File[] files=directory.listFiles();if(files!=null)for(File f:files)if(!f.getName().startsWith("history-"))if(!f.delete())f.deleteOnExit();
        if(failure!=null)throw failure;
    }
}
