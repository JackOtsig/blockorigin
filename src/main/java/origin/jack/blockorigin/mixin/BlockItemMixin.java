package origin.jack.blockorigin.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.util.ActionResult;
import org.spongepowered.asm.mixin.Mixin;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.api.BlockOrigin;

/**
 * Pushes {@link BlockCause#PLAYER_PLACE} for the duration of any
 * {@link BlockItem#place} call, so block changes triggered by a player
 * right-clicking with a block-in-hand get attributed correctly. Covers the
 * vast majority of player block placements (regular blocks, decorative
 * blocks, slabs/stairs, fluids placed as ice via bucket are handled
 * elsewhere by the bucket-specific cause).
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @WrapMethod(method = "place(Lnet/minecraft/item/ItemPlacementContext;)Lnet/minecraft/util/ActionResult;")
    private ActionResult blockorigin$pushPlaceCause(ItemPlacementContext ctx,
                                                    Operation<ActionResult> original) {
        try (BlockOrigin.CauseFrame ignored = BlockOrigin.pushCause(BlockCause.PLAYER_PLACE)) {
            return original.call(ctx);
        }
    }
}
