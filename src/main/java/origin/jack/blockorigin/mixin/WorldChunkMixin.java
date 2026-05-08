package origin.jack.blockorigin.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.internal.CauseAttachment;
import origin.jack.blockorigin.internal.CauseStack;

/**
 * Stamps the active cause from {@link CauseStack} onto every {@code setBlockState}
 * that actually changes the block (not just its state properties). Cause storage
 * itself lives on a Fabric data attachment registered by {@link CauseAttachment}.
 */
@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Lnet/minecraft/block/BlockState;",
            at = @At("RETURN"))
    private void blockorigin$stampCause(BlockPos pos, BlockState state, int flags,
                                        CallbackInfoReturnable<BlockState> cir) {
        BlockState old = cir.getReturnValue();
        // Vanilla returns null when the new state matched the old (no-op write).
        if (old == null) return;
        // State-only change on the same block: preserve the original cause.
        if (old.getBlock() == state.getBlock()) return;
        BlockCause cause = CauseStack.get().peek();
        // No active cause context — don't pollute existing data with UNKNOWN.
        if (cause == BlockCause.UNKNOWN) return;
        WorldChunk self = (WorldChunk) (Object) this;
        CauseAttachment.getOrCreate(self).set(pos, cause);
        self.markNeedsSaving();
    }
}
