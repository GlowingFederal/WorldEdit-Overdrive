package com.glowingfederal.worldeditoverdrive.integration;

import java.io.*;

/** Disk treap keyed by all three signed coordinates. Nodes and the dependency
 * walk's linked stack live on disk, including cycle membership. Worker-only. */
final class PasteDiskIndex implements Closeable {
    private static final int STRIDE = 72;
    static final class Node {
        long slot, left = -1, right = -1, record, walk, next = -1;
        int x, y, z, priority;
        boolean remaining = true;
    }
    private final RandomAccessFile file;
    private long root = -1, nodes;
    private int random = 0x457db193;
    PasteDiskIndex(File directory) throws IOException { file = new RandomAccessFile(new File(directory, "dependencies.index"), "rw"); }
    private Node read(long slot) throws IOException {
        file.seek(slot * STRIDE); Node n = new Node(); n.slot = slot; n.x = file.readInt(); n.y = file.readInt(); n.z = file.readInt(); n.priority = file.readInt();
        n.left = file.readLong(); n.right = file.readLong(); n.record = file.readLong(); n.walk = file.readLong(); n.next = file.readLong(); n.remaining = file.readBoolean(); return n;
    }
    void write(Node n) throws IOException {
        file.seek(n.slot * STRIDE); file.writeInt(n.x); file.writeInt(n.y); file.writeInt(n.z); file.writeInt(n.priority);
        file.writeLong(n.left); file.writeLong(n.right); file.writeLong(n.record); file.writeLong(n.walk); file.writeLong(n.next); file.writeBoolean(n.remaining);
    }
    Node find(int x, int y, int z) throws IOException {
        long slot = root; while (slot >= 0) { Node n = read(slot); int c = compare(x, y, z, n); if (c == 0) return n; slot = c < 0 ? n.left : n.right; } return null;
    }
    void put(int x, int y, int z, long record) throws IOException {
        Node n = new Node(); n.x = x; n.y = y; n.z = z; n.record = record;
        random ^= random << 13; random ^= random >>> 17; random ^= random << 5; n.priority = random & Integer.MAX_VALUE;
        root = insert(root, n);
    }
    private long insert(long slot, Node value) throws IOException {
        if (slot < 0) { value.slot = nodes++; write(value); return value.slot; }
        Node n = read(slot); int c = compare(value.x, value.y, value.z, n);
        if (c == 0) { n.record = value.record; write(n); return slot; }
        if (c < 0) {
            n.left = insert(n.left, value); Node child = read(n.left);
            if (child.priority < n.priority) { n.left = child.right; write(n); child.right = slot; write(child); return child.slot; }
        } else {
            n.right = insert(n.right, value); Node child = read(n.right);
            if (child.priority < n.priority) { n.right = child.left; write(n); child.left = slot; write(child); return child.slot; }
        }
        write(n); return slot;
    }
    private static int compare(int x, int y, int z, Node n) { int c = Integer.compare(x, n.x); if (c == 0) c = Integer.compare(y, n.y); return c == 0 ? Integer.compare(z, n.z) : c; }
    Node node(long slot) throws IOException { return read(slot); }
    long size() { return nodes; }
    long bytes() { return nodes * STRIDE; }
    public void close() throws IOException { file.close(); }
}
