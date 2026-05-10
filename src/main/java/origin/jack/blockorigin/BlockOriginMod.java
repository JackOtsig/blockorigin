package origin.jack.blockorigin;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.internal.BackfillAttachment;
import origin.jack.blockorigin.internal.BackfillRunner;
import origin.jack.blockorigin.internal.BlockOriginCommand;
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
        BackfillAttachment.init();
        registerPlayerBreakHooks();
        BackfillRunner.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BlockOriginCommand.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                maybePromptBackfill(handler.getPlayer()));
        LOGGER.info("Block Origin initialized");
    }

    /**
     * Post a one-time chat prompt the first time any player joins a world that
     * still has {@link BackfillAttachment.Decision#PENDING} as its decision.
     * Once the user runs {@code /blockorigin backfill enable|disable}, the
     * decision is persisted and this prompt won't fire for that world again.
     */
    private static void maybePromptBackfill(ServerPlayerEntity player) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        BackfillAttachment.State state = BackfillAttachment.getOrCreate(world);
        if (state.decision() != BackfillAttachment.Decision.PENDING) return;
        player.sendMessage(Text.literal("[blockorigin] ").formatted(Formatting.GOLD)
                .append(Text.literal("This world has no origin data yet.").formatted(Formatting.WHITE)), false);
        player.sendMessage(Text.literal("  Run ").formatted(Formatting.GRAY)
                .append(Text.literal("/blockorigin backfill").formatted(Formatting.GREEN))
                .append(Text.literal(" to scan all saved chunks against shadow worldgen and tag matches, or "))
                .append(Text.literal("/blockorigin backfill disable").formatted(Formatting.YELLOW))
                .append(Text.literal(" to skip.")), false);
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
