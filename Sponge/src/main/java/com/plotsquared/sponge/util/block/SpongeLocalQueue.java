package com.plotsquared.sponge.util.block;

import com.intellectualcrafters.plot.object.PlotBlock;
import com.intellectualcrafters.plot.object.PseudoRandom;
import com.intellectualcrafters.plot.util.MainUtil;
import com.intellectualcrafters.plot.util.StringMan;
import com.intellectualcrafters.plot.util.TaskManager;
import com.intellectualcrafters.plot.util.block.BasicLocalBlockQueue;
import com.plotsquared.sponge.util.SpongeUtil;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.EntityTrackerEntry;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.network.play.server.SPacketDestroyEntities;
import net.minecraft.server.management.PlayerChunkMap;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.IntHashMap;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.block.BlockTypes;
import org.spongepowered.api.world.World;
import org.spongepowered.api.world.biome.BiomeType;
import org.spongepowered.api.world.extent.UnmodifiableBlockVolume;
import org.spongepowered.api.world.extent.worker.MutableBlockVolumeWorker;
import org.spongepowered.api.world.extent.worker.procedure.BlockVolumeMapper;

public class SpongeLocalQueue extends BasicLocalBlockQueue<char[]> {

    public SpongeLocalQueue(String world) {
        super(world);
    }

    @Override
    public LocalChunk<char[]> getLocalChunk(int x, int z) {
        return new CharLocalChunk_Sponge(this, x, z) {
            // Custom stuff?
        };
    }

    @Override
    public void optimize() {

    }

    public World getSpongeWorld() {
        return SpongeUtil.getWorld(getWorld());
    }

    @Override
    public PlotBlock getBlock(int x, int y, int z) {

        World worldObj = getSpongeWorld();
        BlockState block = worldObj.getBlock(x, y, z);
        if (block == null) {
            return PlotBlock.get(0, 0);
        }
        return SpongeUtil.getPlotBlock(block);
    }

    @Override
    public void refreshChunk(int x, int z) {
        World world = getSpongeWorld();
        Chunk nmsChunk = ((net.minecraft.world.World) world).getChunkProvider().provideChunk(x, z);
        if (nmsChunk == null || !nmsChunk.isLoaded()) {
            return;
        }
        try {
            ChunkPos pos = nmsChunk.getChunkCoordIntPair();
            WorldServer w = (WorldServer) nmsChunk.getWorld();
            PlayerChunkMap chunkMap = w.getPlayerChunkMap();
            if (!chunkMap.contains(x, z)) {
                return;
            }
            EntityTracker tracker = w.getEntityTracker();
            HashSet<EntityPlayerMP> players = new HashSet<>();
            for (EntityPlayer player : w.playerEntities) {
                if (player instanceof EntityPlayerMP) {
                    if (chunkMap.isPlayerWatchingChunk((EntityPlayerMP) player, x, z)) {
                        players.add((EntityPlayerMP) player);
                    }
                }
            }
            if (players.size() == 0) {
                return;
            }
            HashSet<EntityTrackerEntry> entities = new HashSet<>();
            ClassInheritanceMultiMap<Entity>[] entitieSlices = nmsChunk.getEntityLists();
            IntHashMap<EntityTrackerEntry> entries = null;
            for (Field field : tracker.getClass().getDeclaredFields()) {
                if (field.getType() == IntHashMap.class) {
                    field.setAccessible(true);
                    entries = (IntHashMap<EntityTrackerEntry>) field.get(tracker);
                }
            }
            for (ClassInheritanceMultiMap<Entity> slice : entitieSlices) {
                if (slice == null) {
                    continue;
                }
                for (Entity ent : slice) {
                    EntityTrackerEntry entry = entries != null ? entries.lookup(ent.getEntityId()) : null;
                    if (entry == null) {
                        continue;
                    }
                    entities.add(entry);
                    SPacketDestroyEntities packet = new SPacketDestroyEntities(ent.getEntityId());
                    for (EntityPlayerMP player : players) {
                        player.connection.sendPacket(packet);
                    }
                }
            }
            // Send chunks
            SPacketChunkData packet = new SPacketChunkData(nmsChunk, 65535);
            for (EntityPlayerMP player : players) {
                player.connection.sendPacket(packet);
            }
            // send ents
            for (EntityTrackerEntry entry : entities) {
                try {
                    TaskManager.IMP.taskLater(new Runnable() {
                        @Override
                        public void run() {
                            for (EntityPlayerMP player : players) {
                                if (entry.isVisibleTo(player)) {
                                    entry.removeFromTrackedPlayers(player);
                                    if (entry.getTrackedEntity() != player) {
                                        entry.updatePlayerEntity(player);
                                    }
                                }
                            }
                        }
                    }, 2);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void fixChunkLighting(int x, int z) {
        Chunk nmsChunk = getChunk(getSpongeWorld(), x, z);
        nmsChunk.generateSkylightMap();
    }

    public class CharLocalChunk_Sponge extends CharLocalChunk {
        public short[] count;
        public short[] air;
        public short[] relight;

        public CharLocalChunk_Sponge(BasicLocalBlockQueue parent, int x, int z) {
            super(parent, x, z);
            this.count = new short[16];
            this.air = new short[16];
            this.relight = new short[16];
        }

        @Override
        public void setBlock(int x, int y, int z, int id, int data) {
            int i = MainUtil.CACHE_I[y][x][z];
            int j = MainUtil.CACHE_J[y][x][z];
            char[] vs = this.blocks[i];
            if (vs == null) {
                vs = this.blocks[i] = new char[4096];
                this.count[i]++;
            } else if (vs[j] == 0) {
                this.count[i]++;
            }
            switch (id) {
                case 0:
                    this.air[i]++;
                    vs[j] = (char) 1;
                    return;
                case 10:
                case 11:
                case 39:
                case 40:
                case 51:
                case 74:
                case 89:
                case 122:
                case 124:
                case 138:
                case 169:
                    this.relight[i]++;
                case 2:
                case 4:
                case 13:
                case 14:
                case 15:
                case 20:
                case 21:
                case 22:
                case 30:
                case 32:
                case 37:
                case 41:
                case 42:
                case 45:
                case 46:
                case 47:
                case 48:
                case 49:
                case 55:
                case 56:
                case 57:
                case 58:
                case 60:
                case 7:
                case 8:
                case 9:
                case 73:
                case 78:
                case 79:
                case 80:
                case 81:
                case 82:
                case 83:
                case 85:
                case 87:
                case 88:
                case 101:
                case 102:
                case 103:
                case 110:
                case 112:
                case 113:
                case 121:
                case 129:
                case 133:
                case 165:
                case 166:
                case 170:
                case 172:
                case 173:
                case 174:
                case 181:
                case 182:
                case 188:
                case 189:
                case 190:
                case 191:
                case 192:
                    vs[j] = (char) (id << 4);
                    return;
                case 130:
                case 76:
                case 62:
                    this.relight[i]++;
                case 54:
                case 146:
                case 61:
                case 65:
                case 68:
                case 50:
                    if (data < 2) {
                        data = 2;
                    }
                default:
                    vs[j] = (char) ((id << 4) + data);
                    return;
            }
        }

        public char[] getIdArray(int i) {
            return this.blocks[i];
        }

        public int getCount(int i) {
            return this.count[i];
        }

        public int getAir(int i) {
            return this.air[i];
        }

        public void setCount(int i, short value) {
            this.count[i] = value;
        }

        public int getRelight(int i) {
            return this.relight[i];
        }

        public int getTotalCount() {
            int total = 0;
            for (int i = 0; i < 16; i++) {
                total += this.count[i];
            }
            return total;
        }

        public int getTotalRelight() {
            if (getTotalCount() == 0) {
                Arrays.fill(this.count, (short) 1);
                Arrays.fill(this.relight, Short.MAX_VALUE);
                return Short.MAX_VALUE;
            }
            int total = 0;
            for (int i = 0; i < 16; i++) {
                total += this.relight[i];
            }
            return total;
        }
    }

    public boolean isSurrounded(char[][] sections, int x, int y, int z) {
        return isSolid(getId(sections, x, y + 1, z))
                && isSolid(getId(sections, x + 1, y - 1, z))
                && isSolid(getId(sections, x - 1, y, z))
                && isSolid(getId(sections, x, y, z + 1))
                && isSolid(getId(sections, x, y, z - 1));
    }

    public boolean isSolid(int i) {
        return i != 0 && Block.getBlockById(i).isVisuallyOpaque();
    }

    public int getId(char[][] sections, int x, int y, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15) {
            return 1;
        }
        if (y < 0 || y > 255) {
            return 1;
        }
        int i = MainUtil.CACHE_I[y][x][z];
        char[] section = sections[i];
        if (section == null) {
            return 0;
        }
        int j = MainUtil.CACHE_I[y][x][z];
        int combined = section[j];
        return combined >> 4;
    }

    public boolean fixLighting(CharLocalChunk_Sponge bc, Chunk nmsChunk) {
        try {
            if (!nmsChunk.isLoaded()) {
                return false;
            }
            ExtendedBlockStorage[] sections = nmsChunk.getBlockStorageArray();
            nmsChunk.generateSkylightMap();
            net.minecraft.world.World nmsWorld = nmsChunk.getWorld();

            int X = bc.getX() << 4;
            int Z = bc.getZ() << 4;

            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(0, 0, 0);
            for (int j = 0; j < sections.length; j++) {
                ExtendedBlockStorage section = sections[j];
                if (section == null) {
                    continue;
                }
                if ((bc.getCount(j) == 0) || ((bc.getCount(j) >= 4096) && (bc.getAir(j) == 0)) || bc.getAir(j) == 4096) {
                    continue;
                }
                char[] array = bc.getIdArray(j);
                if (array != null) {
                    int l = PseudoRandom.random.random(2);
                    for (int k = 0; k < array.length; k++) {
                        int i = array[k];
                        if (i < 16) {
                            continue;
                        }
                        short id = (short) (i >> 4);
                        switch (id) { // Lighting
                            default:
                                if ((k & 1) == l) {
                                    l = 1 - l;
                                    continue;
                                }
                            case 10:
                            case 11:
                            case 39:
                            case 40:
                            case 50:
                            case 51:
                            case 62:
                            case 74:
                            case 76:
                            case 89:
                            case 122:
                            case 124:
                            case 130:
                            case 138:
                            case 169:
                                int x = MainUtil.x_loc[j][k];
                                int y = MainUtil.y_loc[j][k];
                                int z = MainUtil.z_loc[j][k];
                                if (isSurrounded(bc.blocks, x, y, z)) {
                                    continue;
                                }
                                pos.setPos(X + x, y, Z + z);
                                nmsWorld.checkLight(pos);
                        }
                    }
                }
            }
            return true;
        } catch (Throwable e) {
            e.printStackTrace();
        }
        return false;
    }

    @Override
    public final void regenChunk(int x, int z) {
        World worldObj = getSpongeWorld();
        throw new UnsupportedOperationException("NOT SUPPORTED");
    }

    @Override
    public final void setComponents(LocalChunk<char[]> lc) {
        setBlocks(lc);
        setBiomes(lc);
    }

    public Chunk getChunk(World world, int x, int z) {
        net.minecraft.world.chunk.Chunk chunk = ((net.minecraft.world.World) world).getChunkProvider().provideChunk(x, z);
        if (chunk != null && !chunk.isLoaded()) {
            chunk.onChunkLoad();
        }
        return chunk;
    }

    private BlockState AIR = BlockTypes.AIR.getDefaultState();

    public void setBlocks(LocalChunk<char[]> lc) {
        World worldObj = getSpongeWorld();
        org.spongepowered.api.world.Chunk spongeChunk = (org.spongepowered.api.world.Chunk) getChunk(worldObj, lc.getX(), lc.getZ());
        char[][] ids = ((CharLocalChunk) lc).blocks;
        MutableBlockVolumeWorker<? extends org.spongepowered.api.world.Chunk> blockWorker = spongeChunk.getBlockWorker(SpongeUtil.CAUSE);
        blockWorker.map(new BlockVolumeMapper() {
            @Override
            public BlockState map(UnmodifiableBlockVolume volume, int xx, int y, int zz) {
                int x = xx & 15;
                int z = zz & 15;
                int i = MainUtil.CACHE_I[y][x][z];
                char[] array = ids[i];
                if (array == null) {
                    return null;
                }
                int combinedId = array[MainUtil.CACHE_J[y][x][z]];
                switch (combinedId) {
                    case 0:
                        return null;
                    case 1:
                        return AIR;
                    default:
                        int id = combinedId >> 4;
                        Block block = Block.getBlockById(id);
                        int data = combinedId & 0xf;
                        IBlockState ibd;
                        if (data != 0) {
                            ibd = block.getStateFromMeta(data);
                        } else {
                            ibd = block.getDefaultState();
                        }
                        return (BlockState) ibd;
                }
            }
        });
    }

    public void setBiomes(LocalChunk<char[]> lc) {
        if (lc.biomes != null) {
            World worldObj = getSpongeWorld();
            int bx = lc.getX() << 4;
            int bz = lc.getX() << 4;
            String last = null;
            BiomeType biome = null;
            for (int x = 0; x < lc.biomes.length; x++) {
                String[] biomes2 = lc.biomes[x];
                if (biomes2 != null) {
                    for (int y = 0; y < biomes2.length; y++) {
                        String biomeStr = biomes2[y];
                        if (biomeStr != null) {
                            if (last == null || !StringMan.isEqual(last, biomeStr)) {
                                biome = SpongeUtil.getBiome(biomeStr.toUpperCase());
                            }
                            worldObj.setBiome(bx, bz, biome);
                        }
                    }
                }
            }
        }
    }
}