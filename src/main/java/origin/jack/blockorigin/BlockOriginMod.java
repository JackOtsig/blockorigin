package origin.jack.blockorigin;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.internal.CauseAttachment;
import origin.jack.blockorigin.internal.CauseStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlockOriginMod implements ModInitializer {
    public static final String MOD_ID = "blockorigin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CauseAttachment.init();
        registerPlayerBreakHooks();
        LOGGER.info("Block Origin initialized");
    }

    /**
     * Attribute air left behind by player breaks to {@link BlockCause#PLAYER_BREAK}.
     *
     * <p>BEFORE pushes the frame on the per-thread cause stack. Either AFTER
     * or CANCELED fires next (Fabric guarantees exactly one of them runs after
     * BEFORE), and both pop the frame, keeping the stack balanced.
     */
    private static void registerPlayerBreakHooks() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            CauseStack.get().push(BlockCause.PLAYER_BREAK);
            return true;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) ->
                CauseStack.get().pop());
        PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, blockEntity) ->
                CauseStack.get().pop());
    }
}
