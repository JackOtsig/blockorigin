package origin.jack.blockorigin.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.block.BlockState;
import net.minecraft.block.StemBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.api.BlockOrigin;

/**
 * Stamps {@link BlockCause#RANDOM_TICK_GROW} on the gourd block produced when a
 * pumpkin/melon stem ticks. The stem itself isn't replaced; the stamp lands on
 * the new fruit position via {@link origin.jack.blockorigin.mixin.WorldChunkMixin}.
 */
@Mixin(StemBlock.class)
public abstract class StemBlockMixin {

    @WrapMethod(method = "randomTick")
    private void blockorigin$pushGrowCause(BlockState state, ServerWorld world, BlockPos pos, Random random,
                                           Operation<Void> original) {
        try (BlockOrigin.CauseFrame ignored = BlockOrigin.pushCause(BlockCause.RANDOM_TICK_GROW)) {
            original.call(state, world, pos, random);
        }
    }
}
