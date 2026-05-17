package origin.jack.blockorigin.internal;

import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import net.minecraft.world.gen.noise.NoiseConfig;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.mixin.ServerChunkLoadingManagerAccessor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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
     * Returns the post-noise+surface block column at world XZ via the
     * single-column path. Used by the {@code /blockorigin shadow} debug
     * command — for chunk-level diff, use {@link #createShadowChunk} which
     * reuses gen state across all 256 columns and is ~20-50× faster overall.
     */
    public static VerticalBlockSample sampleColumn(ServerWorld world, int x, int z) {
        ServerChunkManager mgr = world.getChunkManager();
        NoiseConfig noiseConfig = ((ServerChunkLoadingManagerAccessor) mgr.chunkLoadingManager).blockorigin$getNoiseConfig();
        ChunkGenerator generator = mgr.getChunkGenerator();
        return generator.getColumnSample(x, z, world, noiseConfig);
    }

    /**
     * Off-thread shadow-gen diff. Builds an empty {@link ProtoChunk}, runs
     * {@code populateBiomes} and {@code populateNoise} on
     * {@link Util#getMainWorkerExecutor()} via composed futures (never
     * {@code .join()}-ing on the server thread, which deadlocks — see the
     * worldgen-gotchas memory). The diff and stamp pass runs back on the
     * server thread via {@code thenApplyAsync(_, server)} so
     * {@link CauseChunkData} mutation stays on the main thread.
     *
     * <p>Per-chunk wall time on the server thread drops to just the diff loop
     * (~5-10 ms); the noise work runs in parallel on the worker pool.
     */
    public static CompletableFuture<ChunkScanResult> diffAndStampAsync(ServerWorld world, WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        ServerChunkManager mgr = world.getChunkManager();
        ChunkGenerator generator = mgr.getChunkGenerator();
        NoiseConfig noiseConfig = ((ServerChunkLoadingManagerAccessor) mgr.chunkLoadingManager).blockorigin$getNoiseConfig();
        StructureAccessor structures = world.getStructureAccessor();
        Blender blender = Blender.getNoBlending();
        PalettesFactory palettes = PalettesFactory.fromRegistryManager(world.getRegistryManager());
        Executor workerExec = Util.getMainWorkerExecutor();
        Executor serverExec = world.getServer();

        return CompletableFuture
                .supplyAsync(() -> new ProtoChunk(pos, UpgradeData.NO_UPGRADE_DATA, world, palettes, null), workerExec)
                .thenComposeAsync(shadow -> generator.populateBiomes(noiseConfig, blender, structures, shadow), workerExec)
                .thenComposeAsync(c -> generator.populateNoise(blender, noiseConfig, structures, c), workerExec)
                .thenApplyAsync(c -> applyDiffWithChunk(world, chunk, (ProtoChunk) c), serverExec);
    }

    /** Diff loop using a fully-populated shadow {@link ProtoChunk}. Must run on the server thread. */
    private static ChunkScanResult applyDiffWithChunk(ServerWorld world, WorldChunk chunk, ProtoChunk shadow) {
        ChunkPos pos = chunk.getPos();
        int minY = world.getBottomY();
        int maxY = world.getTopYInclusive();
        long matches = 0, mismatches = 0;
        BlockPos.Mutable mut = new BlockPos.Mutable();
        BlockPos.Mutable shadowMut = new BlockPos.Mutable();
        CauseChunkData data = CauseAttachment.getOrCreate(chunk);
        Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES);

        for (int xx = 0; xx < 16; xx++) {
            for (int zz = 0; zz < 16; zz++) {
                int absX = (pos.x << 4) + xx;
                int absZ = (pos.z << 4) + zz;
                int colTop = Math.min(maxY, heightmap.get(xx, zz) + HEIGHTMAP_BUFFER);
                for (int y = minY; y <= colTop; y++) {
                    if (data.get(absX, y, absZ) != BlockCause.UNKNOWN) continue;
                    BlockState shadowState = shadow.getBlockState(shadowMut.set(absX, y, absZ));
                    BlockState live = world.getBlockState(mut.set(absX, y, absZ));
                    if (live.getBlock() == shadowState.getBlock()) {
                        if (!live.isAir()) {
                            data.set(absX, y, absZ, BlockCause.WORLDGEN_TERRAIN);
                        }
                        matches++;
                    } else {
                        mismatches++;
                    }
                }
            }
        }
        data.setBackfilled(true);
        chunk.markNeedsSaving();
        return new ChunkScanResult(matches, mismatches);
    }

    /**
     * Height above the live heightmap that the diff walks before bailing out.
     * Captures most realistic player structures and trees while skipping the
     * vast empty-air region near the world ceiling.
     */
    private static final int HEIGHTMAP_BUFFER = 24;

    /**
     * Diff every position in {@code chunk} against shadow worldgen and stamp
     * solid matches as {@link BlockCause#WORLDGEN_TERRAIN}. Mismatches are left
     * at the implicit default ({@link BlockCause#UNKNOWN}). Marks the chunk
     * dirty.
     *
     * <p>Three optimizations on the inner loop:
     * <ul>
     *   <li><b>Heightmap-bounded</b> — iteration stops at {@code heightmap+24}
     *       per column instead of walking the full world height. The vast empty
     *       air above terrain contributes nothing useful and dominated the cost
     *       on default worlds.</li>
     *   <li><b>Skip already-stamped</b> — positions whose cause is anything
     *       other than {@code UNKNOWN} are left alone, so backfill never
     *       overwrites a gen-time stamp (Worldgen Surface / Feature / Carver /
     *       Player Place / Explosion / etc.) with the coarser {@code TERRAIN}.</li>
     *   <li><b>Don't stamp air-air matches</b> — both sides air means the
     *       chunk attachment stays at its uniform default for that position,
     *       which compacts to a single byte instead of an explicit per-block
     *       record. Air-air matches are still counted in the returned stats.</li>
     * </ul>
     */
    public static ChunkScanResult diffAndStamp(ServerWorld world, WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int minY = world.getBottomY();
        int maxY = world.getTopYInclusive();
        long matches = 0, mismatches = 0;
        BlockPos.Mutable mut = new BlockPos.Mutable();
        CauseChunkData data = CauseAttachment.getOrCreate(chunk);
        Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES);

        for (int xx = 0; xx < 16; xx++) {
            for (int zz = 0; zz < 16; zz++) {
                int absX = (pos.x << 4) + xx;
                int absZ = (pos.z << 4) + zz;
                int colTop = Math.min(maxY, heightmap.get(xx, zz) + HEIGHTMAP_BUFFER);

                VerticalBlockSample column = sampleColumn(world, absX, absZ);
                for (int y = minY; y <= colTop; y++) {
                    if (data.get(absX, y, absZ) != BlockCause.UNKNOWN) continue;
                    BlockState shadow;
                    try {
                        shadow = column.getState(y);
                    } catch (ArrayIndexOutOfBoundsException e) {
                        continue;
                    }
                    BlockState live = world.getBlockState(mut.set(absX, y, absZ));
                    if (live.getBlock() == shadow.getBlock()) {
                        if (!live.isAir()) {
                            data.set(absX, y, absZ, BlockCause.WORLDGEN_TERRAIN);
                        }
                        matches++;
                    } else {
                        mismatches++;
                    }
                }
            }
        }
        data.setBackfilled(true);
        chunk.markNeedsSaving();
        return new ChunkScanResult(matches, mismatches);
    }

    public record ChunkScanResult(long matches, long mismatches) {
        public ChunkScanResult plus(ChunkScanResult other) {
            return new ChunkScanResult(matches + other.matches, mismatches + other.mismatches);
        }
    }
}
