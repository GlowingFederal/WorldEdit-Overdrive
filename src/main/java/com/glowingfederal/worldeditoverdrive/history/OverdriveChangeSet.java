package com.glowingfederal.worldeditoverdrive.history;

import com.sk89q.jnbt.ByteArrayTag;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.IntArrayTag;
import com.sk89q.jnbt.ListTag;
import com.sk89q.jnbt.Tag;
import com.sk89q.worldedit.BlockVector;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.history.UndoContext;
import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.Change;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Operation-owned primitive history. Change objects are allocated only as an iterator advances. */
public final class OverdriveChangeSet implements ChangeSet {
    public enum Encoding { PACKED_RAW }

    private static final long BASE_BYTES = 64;
    private static final long BYTES_PER_CAPACITY = 21; // x + z + y + two states + two tag references
    private int[] x = new int[0], z = new int[0];
    private byte[] y = new byte[0];
    private char[] before = new char[0], after = new char[0];
    private CompoundTag[] beforeTile = new CompoundTag[0], afterTile = new CompoundTag[0];
    private final List<Change> additional = new ArrayList<Change>();
    private int prepared, committed;
    private final long limit;
    private long bytes = BASE_BYTES;
    private boolean sealed;

    public OverdriveChangeSet(long memoryLimit) {
        if (memoryLimit < BASE_BYTES) throw new IllegalArgumentException("memoryLimit must cover history header");
        limit = memoryLimit;
    }

    /** Compatibility constructor; Enhanced's ChangeSet contract does not own a world. */
    public OverdriveChangeSet(Object ignoredWorld, long memoryLimit) { this(memoryLimit); }

    /** Adds an uncommitted record; unchanged states without tile transitions are omitted. */
    public void prepare(int xx, int yy, int zz, int from, int to, CompoundTag oldTile, CompoundTag newTile) {
        if (sealed) throw new IllegalStateException("sealed");
        validate(xx, yy, zz, from, to);
        if (from == to && equal(oldTile, newTile)) return;
        CompoundTag oldCopy = copy(oldTile), newCopy = copy(newTile);
        long tagBytes = estimate(oldCopy) + estimate(newCopy);
        ensure(prepared + 1, tagBytes);
        x[prepared] = xx;
        z[prepared] = zz;
        y[prepared] = (byte) yy;
        before[prepared] = (char) from;
        after[prepared] = (char) to;
        beforeTile[prepared] = oldCopy;
        afterTile[prepared] = newCopy;
        prepared++;
        bytes += tagBytes;
    }

    public void commitPrepared(int count) {
        if (count < 0 || committed + count > prepared) throw new IllegalArgumentException("commit prefix");
        committed += count;
    }

    public void discardUncommitted() {
        for (int i = committed; i < prepared; i++) {
            bytes -= estimate(beforeTile[i]) + estimate(afterTile[i]);
            beforeTile[i] = afterTile[i] = null;
        }
        prepared = committed;
    }

    public void seal() { discardUncommitted(); sealed = true; }
    public int preparedSize() { return prepared; }
    public int size() { return committed + additional.size(); }
    public long estimatedBytes() { return bytes; }
    public Encoding encoding() { return Encoding.PACKED_RAW; }
    public long spillBytes() { return 0; }

    @Override public void add(Change change) {
        if (change == null) throw new NullPointerException("change");
        if (sealed) throw new IllegalStateException("sealed");
        additional.add(change);
    }

    @Override public Iterator<Change> forwardIterator() {
        return concat(primitiveIterator(true), additional.iterator());
    }

    @Override public Iterator<Change> backwardIterator() {
        List<Change> reversed = new ArrayList<Change>(additional);
        Collections.reverse(reversed);
        return concat(reversed.iterator(), primitiveIterator(false));
    }

    /** A single native history entry that keeps LocalSession/EditSession as the undo owner. */
    public Change asChange() {
        if (!sealed) throw new IllegalStateException("history must be sealed before attachment");
        return new Change() {
            public void undo(UndoContext context) throws WorldEditException { apply(backwardIterator(), context, false); }
            public void redo(UndoContext context) throws WorldEditException { apply(forwardIterator(), context, true); }
        };
    }

    private Iterator<Change> primitiveIterator(final boolean forward) {
        if (committed == 0) return Collections.<Change>emptyList().iterator();
        return new Iterator<Change>() {
            private int cursor = forward ? 0 : committed - 1;
            public boolean hasNext() { return forward ? cursor < committed : cursor >= 0; }
            public Change next() {
                if (!hasNext()) throw new NoSuchElementException();
                int i = cursor;
                cursor += forward ? 1 : -1;
                return new BlockChange(new BlockVector(x[i], y[i] & 255, z[i]),
                        block(before[i] & 0xffff, beforeTile[i]), block(after[i] & 0xffff, afterTile[i]));
            }
            public void remove() { throw new UnsupportedOperationException(); }
        };
    }

    private static void apply(Iterator<Change> changes, UndoContext context, boolean redo) throws WorldEditException {
        while (changes.hasNext()) {
            Change change = changes.next();
            if (redo) change.redo(context); else change.undo(context);
        }
    }

    private static BaseBlock block(int state, CompoundTag tile) {
        return new BaseBlock(state >>> 4, state & 15, copy(tile));
    }

    private void ensure(int wanted, long tagBytes) {
        if (wanted <= x.length) {
            checkLimit(bytes + tagBytes);
            return;
        }
        int capacity = Math.max(wanted, Math.max(1, x.length << 1));
        long growth = (capacity - x.length) * BYTES_PER_CAPACITY;
        checkLimit(bytes + growth + tagBytes);
        x = copy(x, capacity); z = copy(z, capacity); y = copy(y, capacity);
        before = copy(before, capacity); after = copy(after, capacity);
        beforeTile = copy(beforeTile, capacity); afterTile = copy(afterTile, capacity);
        bytes += growth;
    }

    private void checkLimit(long projected) {
        if (projected > limit) throw new HistoryLimitExceededException(limit);
    }

    private static void validate(int x, int y, int z, int from, int to) {
        if (y < 0 || y > 255) throw new IllegalArgumentException("y outside 0..255");
        if ((from & ~0xffff) != 0 || (to & ~0xffff) != 0) throw new IllegalArgumentException("state outside unsigned 16-bit encoding");
    }

    private static CompoundTag copy(CompoundTag tag) {
        if (tag == null) return null;
        Map<String, Tag> values = new HashMap<String, Tag>();
        for (Map.Entry<String, Tag> entry : tag.getValue().entrySet()) values.put(entry.getKey(), copyTag(entry.getValue()));
        return new CompoundTag(values);
    }

    private static Tag copyTag(Tag tag) {
        if (tag instanceof CompoundTag) return copy((CompoundTag) tag);
        if (tag instanceof ByteArrayTag) return new ByteArrayTag(((ByteArrayTag) tag).getValue().clone());
        if (tag instanceof IntArrayTag) return new IntArrayTag(((IntArrayTag) tag).getValue().clone());
        if (tag instanceof ListTag) {
            ListTag list = (ListTag) tag;
            List<Tag> values = new ArrayList<Tag>();
            for (Tag value : list.getValue()) values.add(copyTag(value));
            return new ListTag(list.getType(), values);
        }
        return tag; // Scalar tags are immutable.
    }

    private static Iterator<Change> concat(final Iterator<Change> first, final Iterator<Change> second) {
        return new Iterator<Change>() {
            public boolean hasNext() { return first.hasNext() || second.hasNext(); }
            public Change next() { return first.hasNext() ? first.next() : second.next(); }
            public void remove() { throw new UnsupportedOperationException(); }
        };
    }

    private static int[] copy(int[] a, int n) { int[] b = new int[n]; System.arraycopy(a, 0, b, 0, a.length); return b; }
    private static byte[] copy(byte[] a, int n) { byte[] b = new byte[n]; System.arraycopy(a, 0, b, 0, a.length); return b; }
    private static char[] copy(char[] a, int n) { char[] b = new char[n]; System.arraycopy(a, 0, b, 0, a.length); return b; }
    private static CompoundTag[] copy(CompoundTag[] a, int n) { CompoundTag[] b = new CompoundTag[n]; System.arraycopy(a, 0, b, 0, a.length); return b; }
    private static long estimate(CompoundTag tag) { return tag == null ? 0 : 48 + tag.toString().length() * 2L; }
    private static boolean equal(Object a, Object b) { return a == b || (a != null && a.equals(b)); }
}
