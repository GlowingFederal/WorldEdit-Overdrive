package com.glowingfederal.worldeditoverdrive.backend;

import java.util.Arrays;

/**
 * Operation-owned hybrid section. Sparse writes are O(changed positions), then
 * promote once at 512/4096 positions so broad edits gain contiguous iteration.
 */
public final class SectionChange {
    public static final int SIZE = 4096;
    public static final int DENSE_THRESHOLD = 512;
    private static final int EMPTY = -1;

    public interface Visitor { void visit(int index, int packedState); }

    private short[] sparseIndices = new short[16];
    private int[] sparseStates = new int[16];
    private int[] denseStates;
    private int changedCount;

    public void set(int localX, int localY, int localZ, BlockChange state) {
        checkLocal(localX, localY, localZ);
        int index = index(localX, localY, localZ);
        int packed = state.packed();
        if (denseStates != null) {
            if (denseStates[index] == EMPTY) changedCount++;
            denseStates[index] = packed;
            return;
        }
        for (int i = 0; i < changedCount; i++) {
            if ((sparseIndices[i] & 0xffff) == index) {
                sparseStates[i] = packed;
                return;
            }
        }
        ensureSparseCapacity(changedCount + 1);
        sparseIndices[changedCount] = (short) index;
        sparseStates[changedCount++] = packed;
        if (changedCount >= DENSE_THRESHOLD) promote();
    }

    public int getChangedCount() { return changedCount; }
    public boolean isDense() { return denseStates != null; }

    public void forEach(Visitor visitor) {
        if (denseStates == null) {
            for (int i = 0; i < changedCount; i++) visitor.visit(sparseIndices[i] & 0xffff, sparseStates[i]);
        } else {
            for (int i = 0; i < SIZE; i++) if (denseStates[i] != EMPTY) visitor.visit(i, denseStates[i]);
        }
    }

    public long estimatedBytes() {
        return 48L + (denseStates == null
                ? sparseIndices.length * 2L + sparseStates.length * 4L
                : denseStates.length * 4L);
    }

    public static int index(int x, int y, int z) { return y << 8 | z << 4 | x; }
    public static int localX(int index) { return index & 15; }
    public static int localZ(int index) { return index >>> 4 & 15; }
    public static int localY(int index) { return index >>> 8 & 15; }

    private void promote() {
        denseStates = new int[SIZE];
        Arrays.fill(denseStates, EMPTY);
        for (int i = 0; i < changedCount; i++) denseStates[sparseIndices[i] & 0xffff] = sparseStates[i];
        sparseIndices = null;
        sparseStates = null;
    }

    private void ensureSparseCapacity(int required) {
        if (required <= sparseIndices.length) return;
        int capacity = Math.min(DENSE_THRESHOLD, sparseIndices.length << 1);
        sparseIndices = Arrays.copyOf(sparseIndices, capacity);
        sparseStates = Arrays.copyOf(sparseStates, capacity);
    }

    private static void checkLocal(int x, int y, int z) {
        if ((x | y | z) < 0 || x > 15 || y > 15 || z > 15) {
            throw new IllegalArgumentException("section-local coordinates must be 0..15");
        }
    }
}
