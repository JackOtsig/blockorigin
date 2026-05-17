package origin.jack.blockorigin.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.api.BlockOrigin;

/**
 * Pushes {@link BlockCause#WORLDGEN_SURFACE} during {@code buildSurface} and
 * {@link BlockCause#WORLDGEN_CARVER} during {@code carve} on the noise-based
 * chunk generator (the only concrete impl of these abstract stages in vanilla,
 * used by default overworld, nether, and end). Flat and debug generators
 * override these as effective no-ops, so they don't need wiring.
 */
@Mixin(NoiseChunkGenerator.class)
public abstract class NoiseChunkGeneratorMixin {

    @WrapMethod(method = "buildSurface")
    private void blockorigin$pushSurfaceCause(ChunkRegion region, StructureAccessor accessor,
                                              NoiseConfig noiseConfig, Chunk chunk,
                                              Operation<Void> original) {
        try (BlockOrigin.CauseFrame ignored = BlockOrigin.pushCause(BlockCause.WORLDGEN_SURFACE)) {
            original.call(region, accessor, noiseConfig, chunk);
        }
    }

    @WrapMethod(method = "carve")
    private void blockorigin$pushCarverCause(ChunkRegion region, long seed, NoiseConfig noiseConfig,
                                             BiomeAccess biomeAccess, StructureAccessor accessor, Chunk chunk,
                                             Operation<Void> original) {
        try (BlockOrigin.CauseFrame ignored = BlockOrigin.pushCause(BlockCause.WORLDGEN_CARVER)) {
            original.call(region, seed, noiseConfig, biomeAccess, accessor, chunk);
        }
    }
}
