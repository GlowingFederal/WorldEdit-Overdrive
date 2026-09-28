package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.history.UndoContext;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.Change;
import com.sk89q.worldedit.history.change.EntityCreate;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.util.Location;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Native EditSession/LocalSession still own this ChangeSet. Blocks and entity
 * state live on disk; entity identity does not require one heap object per entity.
 * Commands/API undo are intercepted before their synchronous complete loop. */
final class PasteDiskHistory implements ChangeSet,Closeable {
    private volatile PasteStreamStorage writing;
    final PasteDiskJournal blocks, entities;
    final File directory;
    private int size;
    interface EntityIdentity { void capture(PasteDiskJournal.Record r,Entity entity)throws Exception;void remove(PasteDiskJournal.Record r)throws Exception; }
    private final EntityIdentity identity;
    private boolean detached;
    private PasteMemoryBudget.Ticket descriptor;
    void retainDescriptor(PasteMemoryBudget.Ticket descriptor){if(this.descriptor==null)this.descriptor=descriptor;else descriptor.close();}
    PasteDiskHistory(PasteStreamStorage storage){this(storage,new ForgeIdentity());}
    PasteDiskHistory(PasteStreamStorage storage,EntityIdentity identity){writing=storage;blocks=storage.historyBlocks;entities=storage.historyEntities;directory=storage.directory;this.identity=identity;}
    public void add(Change change) {
        if (writing == null) throw new IllegalStateException("sealed streamed history");
        PasteDiskJournal.Record r;
        if (change instanceof BlockChange) {
            BlockChange b = (BlockChange) change;
            r = PasteDiskJournal.Record.block(b.getPosition(), b.getPrevious(), b.getCurrent(), 512L + writing.beforeBytes + writing.afterBytes);
            writing.append(blocks, r, PasteMemoryBudget.Kind.HISTORY);
        } else if (change instanceof EntityCreate) {
            try {
                Location location = (Location) field(change, "location");
                r = PasteDiskJournal.Record.entity(location, (com.sk89q.worldedit.entity.BaseEntity) field(change, "state"), 1024L + writing.afterBytes);
                Entity entity = (Entity) field(change, "entity"); captureIdentity(r, entity);
                writing.append(entities, r, PasteMemoryBudget.Kind.HISTORY);
            } catch (Exception e) { throw new IllegalStateException("streamed entity history unavailable", e); }
        } else throw new IllegalArgumentException("unsupported streamed history change: " + change.getClass().getName());
        size++;
    }
    void captureIdentity(PasteDiskJournal.Record r, Entity entity) throws Exception {
        identity.capture(r,entity);
    }
    private static final class ForgeIdentity implements EntityIdentity {
        net.minecraft.world.World entityWorld;
        public void capture(PasteDiskJournal.Record r,Entity entity)throws Exception{
        if (entity == null) throw new IllegalArgumentException("missing created entity");
        Object ref = field(entity, "entityRef");
        Object nativeEntity = ((WeakReference<?>) ref).get();
        if (!(nativeEntity instanceof net.minecraft.entity.Entity)) throw new IllegalArgumentException("expected Enhanced ForgeEntity identity");
        net.minecraft.entity.Entity e = (net.minecraft.entity.Entity) nativeEntity;
        if (entityWorld != null && entityWorld != e.worldObj) throw new IllegalArgumentException("entity world changed");
        entityWorld = e.worldObj; r.entityId = e.getEntityId(); r.uuid = e.getUniqueID();
        }
        public void remove(PasteDiskJournal.Record r){
            net.minecraft.entity.Entity entity=entityWorld==null?null:entityWorld.getEntityByID(r.entityId);
            if(entity!=null&&entity.getUniqueID().equals(r.uuid))entity.setDead();
        }
    }
    void applyEntity(PasteDiskJournal.Record r, long index, boolean redo, Extent extent) throws Exception {
        if (redo) {
            Entity created = extent.createEntity(new Location(extent, r.ex, r.ey, r.ez, r.yaw, r.pitch), r.entity);
            if (created != null) captureIdentity(r, created);
        } else {
            identity.remove(r);
            r.entityId = -1;
        }
    }
    void seal() { writing = null; }
    boolean ready() { return writing == null; }
    void detach() { detached = true; seal(); }
    public void close()throws IOException{
        if(detached)return;detached=true;
        try{blocks.close();}finally{try{entities.close();}finally{if(descriptor!=null){descriptor.close();descriptor=null;}File[] files=directory.listFiles();if(files!=null)for(File f:files)if(!f.delete())f.deleteOnExit();if(!directory.delete())directory.deleteOnExit();}}
    }
    public int size() { return size; }
    public Iterator<Change> forwardIterator() { return iterator(true); }
    public Iterator<Change> backwardIterator() { return iterator(false); }
    private Iterator<Change> iterator(final boolean forward) {
        if (writing != null) throw new IllegalStateException("history is still being prepared");
        return new Iterator<Change>() {
            long entityCursor = forward ? 0 : entities.count - 1, blockCursor = forward ? 0 : blocks.count - 1;
            public boolean hasNext() { return forward ? entityCursor < entities.count || blockCursor < blocks.count : entityCursor >= 0 || blockCursor >= 0; }
            public Change next() {
                if (!hasNext()) throw new NoSuchElementException();
                try {
                    boolean isEntity = forward ? entityCursor < entities.count : entityCursor >= 0;
                    final long index = isEntity ? entityCursor : blockCursor;
                    final PasteDiskJournal.Record r = (isEntity ? entities : blocks).read(index);
                    if (isEntity) entityCursor += forward ? 1 : -1; else blockCursor += forward ? 1 : -1;
                    if (!isEntity) return new BlockChange(new BlockVector(r.x, r.y, r.z), r.before, r.block);
                    return new Change() {
                        public void undo(UndoContext context) throws WorldEditException { apply(context, false); }
                        public void redo(UndoContext context) throws WorldEditException { apply(context, true); }
                        private void apply(UndoContext context, boolean redo) { try { applyEntity(r, index, redo, context.getExtent()); entities.updateEntityIdentity(index, r.entityId, r.uuid); } catch (Exception e) { throw new IllegalStateException(e); } }
                    };
                } catch (IOException e) { throw new IllegalStateException("streamed history read failed", e); }
            }
            public void remove() { throw new UnsupportedOperationException(); }
        };
    }
    static Object field(Object value, String name) throws Exception { Field f = value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value); }
    // Java 8 has no Cleaner. The two open history handles live exactly as long as
    // this ChangeSet; no static registry holds a strong reference to the history.
    @Override protected void finalize() throws Throwable {
        try { close(); }
        finally { super.finalize(); }
    }
}
