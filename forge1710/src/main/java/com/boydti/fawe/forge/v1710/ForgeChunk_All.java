package com.boydti.fawe.forge.v1710;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.FaweCache;
import com.boydti.fawe.example.CharFaweChunk;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.util.MainUtil;
import com.boydti.fawe.util.MathMan;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.jnbt.ListTag;
import com.sk89q.jnbt.StringTag;
import com.sk89q.jnbt.Tag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

public class ForgeChunk_All extends CharFaweChunk<Chunk, ForgeQueue_All> {

    public final byte[][] byteIds;
    public final NibbleArray[] extended;
    public final NibbleArray[] datas;

    public ForgeChunk_All(FaweQueue parent, int x, int z) {
        super(parent, x, z);
        this.byteIds = new byte[16][];
        this.extended = new NibbleArray[16];
        this.datas = new NibbleArray[16];
    }

    public ForgeChunk_All(FaweQueue parent, int x, int z, char[][] ids, short[] count, short[] air, byte[] heightMap, byte[][] byteIds, NibbleArray[] datas, NibbleArray[] extended) {
        super(parent, x, z, ids, count, air, heightMap);
        this.byteIds = byteIds;
        this.datas = datas;
        this.extended = extended;
    }

    @Override
    public CharFaweChunk copy(boolean shallow) {
        ForgeChunk_All copy;
        if (shallow) {
            copy = new ForgeChunk_All(getParent(), getX(), getZ(), ids, count, air, heightMap, byteIds, datas, extended);
            copy.biomes = biomes;
            copy.chunk = chunk;
        } else {
            copy = new ForgeChunk_All(getParent(), getX(), getZ(), (char[][]) MainUtil.copyNd(ids), count.clone(), air.clone(), heightMap.clone(), (byte[][]) MainUtil.copyNd(byteIds), datas.clone(), extended.clone());
            copy.biomes = biomes;
            copy.chunk = chunk;
            copy.biomes = biomes.clone();
            copy.chunk = chunk;
        }
        return copy;
    }

    @Override
    public Chunk getNewChunk() {
        World world = ((ForgeQueue_All) getParent()).getWorld();
        return world.getChunkProvider().provideChunk(getX(), getZ());
    }

    public byte[] getByteIdArray(int i) {
        return this.byteIds[i];
    }

    public NibbleArray getDataArray(int i) {
        return datas[i];
    }

    public NibbleArray getExtendedIdArray(int i) {
        return extended[i];
    }

    @Override
    public int getBlockCombinedId(int x, int y, int z) {
        int combined = super.getBlockCombinedId(x, y, z);
        return combined == 1 ? 0 : combined;
    }

    @Override
    public void setBlock(int x, int y, int z, int id) {
        setBlock(x, y, z, id, 0);
    }

    @Override
    public void setBlock(int x, int y, int z, int id, int data) {
        int i = FaweCache.getI(y, z, x);
        int j = FaweCache.getJ(y, z, x);
        byte[] vs = this.byteIds[i];
        char[] vs2 = this.ids[i];
        if (vs2 == null) {
            vs2 = this.ids[i] = new char[4096];
        }
        if (vs == null) {
            vs = this.byteIds[i] = new byte[4096];
        }
        this.count[i]++;

        if(id == 0){
            this.air[i]++;
            // Use 1 as a sentinel value for cleared air
            vs2[j] = 1;
            vs[j] = 0;
            NibbleArray dataArray = datas[i];
            if (dataArray != null) {
                dataArray.set(x, y & 15, z, 0);
            }
            NibbleArray nibble = extended[i];
            if (nibble != null) {
                nibble.set(x, y & 15, z, 0);
            }
        }else{
            vs2[j] = (char) ((id << 4) + data);
            vs[j] = (byte) id;
        }

        if (data != 0) {
            NibbleArray dataArray = datas[i];
            if (dataArray == null) {
                datas[i] = dataArray = new NibbleArray(4096, 4);
            }
            dataArray.set(x, y & 15, z, data);
        }
        if (id > 255) {
            NibbleArray nibble = extended[i];
            if (nibble == null) {
                extended[i] = nibble = new NibbleArray(4096, 4);
            }
            nibble.set(x, y & 15, z, id >> 8);
        }

    }

    @Override
    public ForgeChunk_All call() {
        if (!Fawe.isMainThread()) {
            throw new IllegalStateException("Forge chunk commits must run on the server thread");
        }
        net.minecraft.world.chunk.Chunk nmsChunk = this.getChunk();
        nmsChunk.setChunkModified();
        nmsChunk.hasEntities = true;
        nmsChunk.sendUpdates = true;
        net.minecraft.world.World nmsWorld = nmsChunk.worldObj;
        try {
            boolean flag = !nmsWorld.provider.hasNoSky;
            // Sections
            ExtendedBlockStorage[] sections = nmsChunk.getBlockStorageArray();
            Map<ChunkPosition, TileEntity> tiles = nmsChunk.chunkTileEntityMap;
            List<Entity>[] entities = nmsChunk.entityLists;

            // Remove entities
            for (int i = 0; i < 16; i++) {
                int count = this.getCount(i);
                if (count == 0) {
                    continue;
                } else if (isFullySpecified(i)) {
                    entities[i].clear();
                } else if (!getParent().getSettings().EXPERIMENTAL.KEEP_ENTITIES_IN_BLOCKS) {
                    char[] array = this.getIdArray(i);
                    if (array == null || entities[i] == null || entities[i].isEmpty()) continue;
                    Collection<Entity> ents = new ArrayList<>(entities[i]);
                    for (Entity entity : ents) {
                        if (entity instanceof EntityPlayer) {
                            continue;
                        }
                        int x = (MathMan.roundInt(entity.posX) & 15);
                        int z = (MathMan.roundInt(entity.posZ) & 15);
                        int y = MathMan.roundInt(entity.posY);
                        if (y < 0 || y > 255) continue;
                        if (array[FaweCache.getJ(y, z, x)] != 0) {
                            synchronized (ForgeQueue_All.class) {
                                nmsWorld.removeEntity(entity);
                            }
                        }
                    }
                }
            }
            HashSet<UUID> entsToRemove = this.getEntityRemoves();
            if (entsToRemove.size() > 0) {
                for (int i = 0; i < entities.length; i++) {
                    Collection<Entity> ents = new ArrayList<>(entities[i]);
                    for (Entity entity : ents) {
                        if (entsToRemove.contains(entity.getUniqueID())) {
                            synchronized (ForgeQueue_All.class) {
                                nmsWorld.removeEntity(entity);
                            }
                        }
                    }
                }
            }
            // Set entities
            Set<UUID> createdEntities = new HashSet<>();
            Set<CompoundTag> entitiesToSpawn = this.getEntities();
            for (CompoundTag nativeTag : entitiesToSpawn) {
                Map<String, Tag> entityTagMap = nativeTag.getValue();
                StringTag idTag = (StringTag) entityTagMap.get("Id");
                ListTag posTag = (ListTag) entityTagMap.get("Pos");
                ListTag rotTag = (ListTag) entityTagMap.get("Rotation");
                if (idTag == null || posTag == null || rotTag == null) {
                    Fawe.debug("Unknown entity tag: " + nativeTag);
                    continue;
                }
                double x = posTag.getDouble(0);
                double y = posTag.getDouble(1);
                double z = posTag.getDouble(2);
                float yaw = rotTag.getFloat(0);
                float pitch = rotTag.getFloat(1);
                String id = idTag.getValue();
                Entity entity = EntityList.createEntityByName(id, nmsWorld);
                if (entity != null) {
                    NBTTagCompound tag = (NBTTagCompound) ForgeQueue_All.methodFromNative.invoke(null, nativeTag);
                    tag.removeTag("UUIDMost");
                    tag.removeTag("UUIDLeast");
                    entity.readFromNBT(tag);
                    entity.setPositionAndRotation(x, y, z, yaw, pitch);
                    synchronized (ForgeQueue_All.class) {
                        nmsWorld.spawnEntityInWorld(entity);
                    }
                }
            }
            // Run change task if applicable
            if (getParent().getChangeTask() != null) {
                CharFaweChunk previous = getParent().getPrevious(this, sections, tiles, entities, createdEntities, false);
                getParent().getChangeTask().run(previous, this);
            }
            // Trim tiles
            Map<Short, NBTTagCompound> preservedTiles = new java.util.HashMap<>();
            Collection<ChunkPosition> tilesToRemove = new ArrayList<>();
            for (Map.Entry<ChunkPosition, TileEntity> tile : new ArrayList<>(tiles.entrySet())) {
                ChunkPosition pos = tile.getKey();
                int lx = pos.chunkPosX & 15;
                int ly = pos.chunkPosY;
                int lz = pos.chunkPosZ & 15;
                int j = FaweCache.getI(ly, lz, lx);
                char[] array = this.getIdArray(j);
                if (array == null) {
                    continue;
                }
                int k = FaweCache.getJ(ly, lz, lx);
                if (array[k] != 0) {
                    NBTTagCompound oldTag = new NBTTagCompound();
                    tile.getValue().writeToNBT(oldTag);
                    preservedTiles.put((short) (lx << 12 | lz << 8 | ly), oldTag);
                    tilesToRemove.add(pos);
                }
            }
            for (ChunkPosition pos : tilesToRemove) {
                nmsWorld.removeTileEntity((getX() << 4) + (pos.chunkPosX & 15), pos.chunkPosY,
                        (getZ() << 4) + (pos.chunkPosZ & 15));
            }
            // Efficiently merge sections
            for (int j = 0; j < sections.length; j++) {
                int count = this.getCount(j);
                if (count == 0) {
                    continue;
                }
                byte[] newIdArray = this.getByteIdArray(j);
                if (newIdArray == null) {
                    continue;
                }
                int countAir = this.getAir(j);
                NibbleArray newDataArray = this.getDataArray(j);
                NibbleArray extendedArray = this.getExtendedIdArray(j);
                ExtendedBlockStorage section = sections[j];
                if ((section == null)) {
                    if (count == countAir) {
                        continue;
                    }
                    sections[j] = section = new ExtendedBlockStorage(j << 4, !getParent().getWorld().provider.hasNoSky);
                    section.setBlockLSBArray(newIdArray);
                    if (newDataArray != null) {
                        section.setBlockMetadataArray(newDataArray);
                    }
                    if (extendedArray != null) {
                        section.setBlockMSBArray(extendedArray);
                    }
                    getParent().updateSectionCounts(section);
                    continue;
                } else if (isFullySpecified(j)) {
                    if (count == countAir) {
                        sections[j] = null;
                        continue;
                    }
                    section.setBlockLSBArray(newIdArray);
                    if (newDataArray != null) {
                        section.setBlockMetadataArray(newDataArray);
                    } else if (section.getMetadataArray() != null) {
                        Arrays.fill(section.getMetadataArray().data, (byte) 0);
                    }
                    if (extendedArray != null) {
                        section.setBlockMSBArray(extendedArray);
                    } else if (section.getBlockMSBArray() != null) {
                        Arrays.fill(section.getBlockMSBArray().data, (byte) 0);
                    }
                    getParent().updateSectionCounts(section);
                    continue;
                }
                byte[] currentIdArray = section.getBlockLSBArray();
                NibbleArray currentDataArray = section.getMetadataArray();
                NibbleArray currentExtraArray = section.getBlockMSBArray();
                boolean data = currentDataArray != null && newDataArray != null;
                if (currentDataArray == null && newDataArray != null) {
                    section.setBlockMetadataArray(newDataArray);
                }
                boolean extra = currentExtraArray != null && extendedArray != null;
                if (currentExtraArray == null && extendedArray != null) {
                    section.setBlockMSBArray(extendedArray);
                }
                char[] charArray = this.getIdArray(j);
                for (int k = 0; k < newIdArray.length; k++) {
                    char combined = charArray[k];
                    switch (combined) {
                        case 0:
                            continue;
                        case 1: { // sentinel for cleared air
                            currentIdArray[k] = 0;
                            int x = FaweCache.getX(0, k);
                            int y = FaweCache.getY(0, k);
                            int z = FaweCache.getZ(0, k);
                            if (currentExtraArray != null) {
                                currentExtraArray.set(x, y, z, 0);
                            }
                            if (currentDataArray != null) {
                                currentDataArray.set(x, y, z, 0);
                            }
                            continue;
                        }
                        default: {
                            currentIdArray[k] = newIdArray[k];
                            if (data) {
                                if (FaweCache.hasData(combined >> 4)) {
                                    int dataByte = FaweCache.getData(combined);
                                    int x = FaweCache.getX(0, k);
                                    int y = FaweCache.getY(0, k);
                                    int z = FaweCache.getZ(0, k);
                                    int newData = newDataArray.get(x, y, z);
                                    currentDataArray.set(x, y, z, newData);
                                }
                            } else if (currentDataArray != null) {
                                int x = FaweCache.getX(0, k);
                                int y = FaweCache.getY(0, k);
                                int z = FaweCache.getZ(0, k);
                                currentDataArray.set(x, y, z, 0);
                            }
                            int extraId = FaweCache.getId(combined) >> 8;
                            if (extra && extraId != 0) {
                                int x = FaweCache.getX(0, k);
                                int y = FaweCache.getY(0, k);
                                int z = FaweCache.getZ(0, k);
                                int newExtra = extendedArray.get(x, y, z);
                                currentExtraArray.set(x, y, z, newExtra);
                            } else if (currentExtraArray != null) {
                                int x = FaweCache.getX(0, k);
                                int y = FaweCache.getY(0, k);
                                int z = FaweCache.getZ(0, k);
                                currentExtraArray.set(x, y, z, 0);
                            }
                            continue;
                        }
                    }
                }
                getParent().updateSectionCounts(section);
                if (section.isEmpty()) {
                    sections[j] = null;
                }
            }

            getParent().recomputeHeightMap(nmsChunk, this);

            // Set biomes
            if (this.biomes != null) {
                byte[] currentBiomes = nmsChunk.getBiomeArray();
                for (int i = 0 ; i < this.biomes.length; i++) {
                    byte biome = this.biomes[i];
                    if (biome != 0) {
                        if (biome == -1) biome = 0;
                        currentBiomes[i] = biome;
                    }
                }
            }
            // Set tiles
            Map<Short, CompoundTag> tilesToSpawn = this.getTiles();
            int bx = this.getX() << 4;
            int bz = this.getZ() << 4;

            for (int layer = 0; layer < ids.length; layer++) {
                char[] changed = ids[layer];
                if (changed == null) continue;
                for (int index = 0; index < changed.length; index++) {
                    if (changed[index] == 0) continue;
                    int lx = FaweCache.getX(0, index);
                    int ly = (layer << 4) + FaweCache.getY(0, index);
                    int lz = FaweCache.getZ(0, index);
                    int x = bx + lx;
                    int z = bz + lz;
                    net.minecraft.block.Block block = nmsWorld.getBlock(x, ly, z);
                    int metadata = nmsWorld.getBlockMetadata(x, ly, z);
                    short hash = (short) (lx << 12 | lz << 8 | ly);
                    CompoundTag queuedTag = tilesToSpawn.get(hash);
                    if (!block.hasTileEntity(metadata)) {
                        if (queuedTag != null) {
                            throw new IllegalStateException("Tile NBT supplied for non-tile block " + block + " at " + x + "," + ly + "," + z);
                        }
                        continue;
                    }
                    TileEntity expected = block.createTileEntity(nmsWorld, metadata);
                    TileEntity tileEntity;
                    if (queuedTag != null) {
                        NBTTagCompound tag = (NBTTagCompound) ForgeQueue_All.methodFromNative.invoke(null, queuedTag);
                        tag.setInteger("x", x);
                        tag.setInteger("y", ly);
                        tag.setInteger("z", z);
                        tileEntity = getParent().createTileEntity(nmsWorld, tag);
                    } else if (preservedTiles.containsKey(hash)) {
                        NBTTagCompound tag = preservedTiles.get(hash);
                        tag.setInteger("x", x);
                        tag.setInteger("y", ly);
                        tag.setInteger("z", z);
                        tileEntity = getParent().createTileEntity(nmsWorld, tag);
                        if (expected != null && !expected.getClass().isInstance(tileEntity)) {
                            tileEntity = expected;
                        }
                    } else {
                        tileEntity = expected;
                    }
                    if (tileEntity == null) {
                        throw new IllegalStateException("Unable to create tile entity for " + block + " at " + x + "," + ly + "," + z);
                    }
                    nmsWorld.setTileEntity(x, ly, z, tileEntity);
                    TileEntity installed = nmsWorld.getTileEntity(x, ly, z);
                    if (installed != tileEntity || (expected != null && !expected.getClass().isInstance(tileEntity))) {
                        throw new IllegalStateException("Incompatible tile entity for " + block + " at " + x + "," + ly + "," + z);
                    }
                    tileEntity.markDirty();
                    getParent().sendMultipartDescription(nmsWorld, tileEntity);
                }
            }
        } catch (Throwable e) {
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException("Failed to commit Forge chunk " + getX() + "," + getZ(), e);
        }
        return this;
    }

    public boolean hasEntities(Chunk nmsChunk) {
        for (int i = 0; i < nmsChunk.entityLists.length; i++) {
            List slice = nmsChunk.entityLists[i];
            if (slice != null && !slice.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isFullySpecified(int layer) {
        char[] changed = getIdArray(layer);
        if (changed == null) return false;
        for (char value : changed) {
            if (value == 0) return false;
        }
        return true;
    }
}
