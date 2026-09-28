package com.glowingfederal.worldeditoverdrive.integration;

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.NBTInputStream;
import com.sk89q.jnbt.NBTOutputStream;
import com.sk89q.worldedit.Vector;
import com.sk89q.worldedit.blocks.BaseBlock;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.util.Location;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Append on the owner thread, flush/decode on one worker. No source-sized heap index.
 * The offset file permits forward/reverse history traversal with constant memory. */
final class PasteDiskJournal implements Closeable {
    static final class Record {
        int x, y, z, entityId;
        double ex, ey, ez;
        float yaw, pitch;
        UUID uuid;
        BaseBlock before, block;
        BaseEntity entity;
        long memory;
        boolean pairWithNext;
        PasteMemoryBudget.Ticket ticket;
        static Record block(Vector position, BaseBlock before, BaseBlock after, long memory) {
            Record r = new Record(); r.x = position.getBlockX(); r.y = position.getBlockY(); r.z = position.getBlockZ();
            r.before = before == null ? null : new BaseBlock(before); r.block = new BaseBlock(after); r.memory = memory; return r;
        }
        static Record entity(Location location, BaseEntity state, long memory) {
            Record r = new Record(); r.ex = location.getX(); r.ey = location.getY(); r.ez = location.getZ();
            r.yaw = location.getYaw(); r.pitch = location.getPitch(); r.entity = new BaseEntity(state); r.memory = memory; return r;
        }
        Vector position() { return new Vector(x, y, z); }
        void release() { before = null; block = null; entity = null; if (ticket != null) { ticket.close(); ticket = null; } }
    }
    final ArrayList<Record> pending = new ArrayList<Record>(PreparedClipboardView.PAGE_SIZE);
    private final RandomAccessFile data, offsets;
    private DataOutputStream out, offsetOut;
    private final DataInputStream in;
    private long writePosition;
    private long readPosition,readStart=-1;
    private final byte[] readBuffer=new byte[8192];
    private int readLength;
    private final byte[] offsetBuffer=new byte[8];
    private boolean closed;
    long count, bytes;
    PasteDiskJournal(File directory, String name) throws IOException {
        data = new RandomAccessFile(new File(directory, name + ".data"), "rw");
        offsets = new RandomAccessFile(new File(directory, name + ".offsets"), "rw");
        final BufferedOutputStream buffered=new BufferedOutputStream(appendStream(data),8192);
        out=new DataOutputStream(new OutputStream(){
            public void write(int value)throws IOException{buffered.write(value);writePosition++;}
            public void write(byte[] value,int start,int length)throws IOException{buffered.write(value,start,length);writePosition+=length;}
            public void flush()throws IOException{buffered.flush();}
        });
        offsetOut=new DataOutputStream(new BufferedOutputStream(appendStream(offsets),8192));
        in = new DataInputStream(new InputStream() {
            public int read()throws IOException{if(!ensureRead())return -1;return readBuffer[(int)(readPosition++-readStart)]&255;}
            public int read(byte[] value,int start,int length)throws IOException{
                if(length==0)return 0;if(!ensureRead())return -1;int n=Math.min(length,readLength-(int)(readPosition-readStart));System.arraycopy(readBuffer,(int)(readPosition-readStart),value,start,n);readPosition+=n;return n;
            }
        });
    }
    private boolean ensureRead()throws IOException{
        if(readPosition<readStart||readPosition>=readStart+readLength){readStart=readPosition&~8191L;data.seek(readStart);readLength=data.read(readBuffer);}
        return readLength>0&&readPosition<readStart+readLength;
    }
    private static OutputStream appendStream(final RandomAccessFile file){return new OutputStream(){
        public void write(int value)throws IOException{file.seek(file.length());file.write(value);}
        public void write(byte[] value,int start,int length)throws IOException{file.seek(file.length());file.write(value,start,length);}
    };}
    void flushBuffers()throws IOException{if(out!=null){out.flush();offsetOut.flush();}}
    void append(Record record) { if (pending.size() >= PreparedClipboardView.PAGE_SIZE) throw new IllegalStateException("paste journal page full"); pending.add(record); }
    void flush() throws IOException {
        try { for (Record record : pending) { appendDisk(record); record.release(); } }
        finally { releasePending();flushBuffers(); }
    }
    long appendDisk(Record r) throws IOException {
        long offset = writePosition;out.writeLong(r.memory);
        out.writeBoolean(r.entity != null);
        if (r.entity != null) {
            out.writeDouble(r.ex); out.writeDouble(r.ey); out.writeDouble(r.ez); out.writeFloat(r.yaw); out.writeFloat(r.pitch);
            out.writeInt(r.entityId); out.writeLong(r.uuid == null ? 0 : r.uuid.getMostSignificantBits()); out.writeLong(r.uuid == null ? 0 : r.uuid.getLeastSignificantBits());
            out.writeUTF(r.entity.getTypeId()); writeNbt(r.entity.getNbtData());
        } else {
            out.writeInt(r.x); out.writeInt(r.y); out.writeInt(r.z); out.writeBoolean(r.before != null);
            if (r.before != null) writeBlock(r.before); writeBlock(r.block);
        }
        offsetOut.writeLong(offset); count++; bytes=writePosition+count*8;return offset;
    }
    long offset(long index) throws IOException {if(index<0||index>=count)throw new IndexOutOfBoundsException();flushBuffers();offsets.seek(index*8);offsets.readFully(offsetBuffer);long value=0;for(byte b:offsetBuffer)value=(value<<8)|(b&255);return value;}
    long memoryAt(long offset) throws IOException {flushBuffers();readPosition=offset;return in.readLong();}
    int[] typeDataAt(long offset) throws IOException {flushBuffers();readPosition=offset+8+1+12+1;return new int[]{in.readInt(),in.readInt()};}
    Record read(long index) throws IOException { return readOffset(offset(index)); }
    Record readOffset(long offset) throws IOException {
        flushBuffers();readPosition=offset;Record r=new Record();r.memory=in.readLong();
        if (in.readBoolean()) {
            r.ex = in.readDouble(); r.ey = in.readDouble(); r.ez = in.readDouble(); r.yaw = in.readFloat(); r.pitch = in.readFloat();
            r.entityId = in.readInt(); r.uuid = new UUID(in.readLong(), in.readLong()); r.entity = new BaseEntity(in.readUTF(), readNbt());
        } else {
            r.x = in.readInt(); r.y = in.readInt(); r.z = in.readInt(); if (in.readBoolean()) r.before = readBlock(); r.block = readBlock();
        }
        return r;
    }
    void updateEntityIdentity(long index, int id, UUID uuid) throws IOException {
        // header + entity flag + xyz doubles + yaw/pitch floats
        data.seek(offset(index) + 8 + 1 + 24 + 8); data.writeInt(id); data.writeLong(uuid.getMostSignificantBits()); data.writeLong(uuid.getLeastSignificantBits());
        readStart=-1;readLength=0;
    }
    private void writeBlock(BaseBlock block) throws IOException { out.writeInt(block.getId()); out.writeInt(block.getData()); writeNbt(block.getNbtData()); }
    private BaseBlock readBlock() throws IOException { return new BaseBlock(in.readInt(), in.readInt(), readNbt()); }
    private void writeNbt(CompoundTag tag) throws IOException { out.writeBoolean(tag != null); if (tag != null) new NBTOutputStream(out).writeNamedTag("", tag); }
    private CompoundTag readNbt() throws IOException { return in.readBoolean() ? (CompoundTag) new NBTInputStream(in).readNamedTag().getTag() : null; }
    void releasePending() { for (Record r : pending) r.release(); pending.clear(); }
    void sealReadOnly()throws IOException{flushBuffers();out=null;offsetOut=null;pending.trimToSize();}
    public void close()throws IOException{if(closed)return;closed=true;releasePending();try{flushBuffers();}finally{try{data.close();}finally{offsets.close();}}}
}
