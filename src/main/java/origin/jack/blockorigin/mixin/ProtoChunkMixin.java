package origin.jack.blockorigin.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.internal.CauseAttachment;
import origin.jack.blockorigin.internal.CauseStack;

/**
 * Mirror of {@code WorldChunkMixin} for ProtoChunks. During chunk generation
 * blocks are placed on a {@link ProtoChunk} (not a {@code WorldChunk}), so a
 * separate mixin is needed to catch worldgen-stage block changes and stamp
 * them with whatever cause is active on the thread-local {@code CauseStack}.
 *
 * <p>ProtoChunks aren't directly saved — they're upgraded to WorldChunks when
 * generation completes — so {@code markNeedsSaving} isn't called here.
 * Attachments carry through the proto→world transition.
 */
@Mixin(ProtoChunk.class)
public abstract class ProtoChunkMixin {

    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Lnet/minecraft/block/BlockState;",
            at = @At("RETURN"))
    private void blockorigin$stampGenCause(BlockPos pos, BlockState state, int flags,
                                           CallbackInfoReturnable<BlockState> cir) {
        BlockState old = cir.getReturnValue();
        if (old == null) return;
        if (old.getBlock() == state.getBlock()) return;
        BlockCause cause = CauseStack.get().peek();
        if (cause == BlockCause.UNKNOWN) return;
        ProtoChunk self = (ProtoChunk) (Object) this;
        CauseAttachment.getOrCreate(self).set(pos, cause);
    }
}
