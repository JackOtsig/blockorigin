package origin.jack.blockorigin.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.api.BlockOrigin;

/**
 * Pushes {@link BlockCause#WORLDGEN_FEATURE} during {@code generateFeatures} so
 * blocks placed by configured features — trees, ores, flowers, lakes,
 * dungeons, geodes, fossils — get attributed correctly via the ProtoChunk
 * stamper. {@code generateFeatures} is concrete on {@link ChunkGenerator},
 * shared by every subclass, so this single mixin covers all of them.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorMixin {

    @WrapMethod(method = "generateFeatures")
    private void blockorigin$pushFeatureCause(StructureWorldAccess world, Chunk chunk,
                                              StructureAccessor accessor,
                                              Operation<Void> original) {
        try (BlockOrigin.CauseFrame ignored = BlockOrigin.pushCause(BlockCause.WORLDGEN_FEATURE)) {
            original.call(world, chunk, accessor);
        }
    }
}
