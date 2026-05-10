package origin.jack.blockorigin.internal;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.mixin.ServerChunkLoadingManagerAccessor;

/**
 * Shadow worldgen utilities — runs the live world's chunk generator off to the
 * side without committing to the live chunk cache, so we can compare the
 * regenerated state against the saved one.
 *
 * <p>This first iteration uses {@link ChunkGenerator#getColumnSample}, which is
 * fully synchronous and only runs the noise + surface stages (no carvers, no
 * features, no structures). That's enough to confirm whether the generator
 * reproduces the same blocks deterministically at runtime; carvers and features
 * are a follow-up once the deterministic-reproduction question is answered.
 */
public final class ShadowGen {

    private ShadowGen() {}

    /**
     * Returns the post-noise+surface block column at world XZ. The returned
     * {@link VerticalBlockSample} indexes by absolute Y over the world's full
     * vertical extent.
     */
    public static VerticalBlockSample sampleColumn(ServerWorld world, int x, int z) {
        ServerChunkManager mgr = world.getChunkManager();
        NoiseConfig noiseConfig = ((ServerChunkLoadingManagerAccessor) mgr.chunkLoadingManager).blockorigin$getNoiseConfig();
        ChunkGenerator generator = mgr.getChunkGenerator();
        return generator.getColumnSample(x, z, world, noiseConfig);
    }

    /**
     * Diff every position in {@code chunk} against shadow worldgen and stamp
     * matches as {@link BlockCause#WORLDGEN_TERRAIN}. Mismatches are left at
     * the implicit default ({@link BlockCause#UNKNOWN}). Marks the chunk dirty.
     */
    public static ChunkScanResult diffAndStamp(ServerWorld world, WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = world.getBottomY();
        int maxY = world.getTopYInclusive();
        long matches = 0, mismatches = 0;
        BlockPos.Mutable mut = new BlockPos.Mutable();
        CauseChunkData data = CauseAttachment.getOrCreate(chunk);
        for (int xx = 0; xx < 16; xx++) {
            for (int zz = 0; zz < 16; zz++) {
                int absX = (pos.x << 4) + xx;
                int absZ = (pos.z << 4) + zz;
                VerticalBlockSample column = sampleColumn(world, absX, absZ);
                for (int y = minY; y <= maxY; y++) {
                    BlockState shadow;
                    try {
                        shadow = column.getState(y);
                    } catch (ArrayIndexOutOfBoundsException e) {
                        continue;
                    }
                    BlockState live = world.getBlockState(mut.set(absX, y, absZ));
                    if (live.getBlock() == shadow.getBlock()) {
                        data.set(absX, y, absZ, BlockCause.WORLDGEN_TERRAIN);
                        matches++;
                    } else {
                        mismatches++;
                    }
                }
            }
        }
        chunk.markNeedsSaving();
        return new ChunkScanResult(matches, mismatches);
    }

    public record ChunkScanResult(long matches, long mismatches) {
        public ChunkScanResult plus(ChunkScanResult other) {
            return new ChunkScanResult(matches + other.matches, mismatches + other.mismatches);
        }
    }
}
