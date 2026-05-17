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
import origin.jack.blockorigin.internal.ChunkLoadWatcher;
import origin.jack.blockorigin.internal.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BlockOriginMod implements ModInitializer {
    public static final String MOD_ID = "blockorigin";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        Config.load();
        CauseAttachment.init();
        BackfillAttachment.init();
        registerPlayerBreakHooks();
        BackfillRunner.register();
        ChunkLoadWatcher.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BlockOriginCommand.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                maybeAutoStartBackfill(handler.getPlayer()));
        LOGGER.info("Block Origin initialized");
    }

    /**
     * The first time any player joins a world that still has
     * {@link BackfillAttachment.Decision#PENDING} as its decision, flip the
     * decision to {@link BackfillAttachment.Decision#ENABLED} and post a chat
     * notice. From that point on the {@code ChunkLoadWatcher} scans chunks
     * lazily as they load — no bulk lockup at startup. For an explicit
     * whole-world bulk pass, the player can run {@code /blockorigin backfill}.
     *
     * <p>Worlds set to {@link BackfillAttachment.Decision#DISABLED} are
     * skipped. Worlds already {@code ENABLED} are skipped (the notice has
     * been seen).
     *
     * <p>Gated by {@link Config.Settings#autoPromptOnFirstLoad()} — set to
     * {@code false} to never notify or auto-enable.
     */
    private static void maybeAutoStartBackfill(ServerPlayerEntity player) {
        if (!Config.get().autoPromptOnFirstLoad()) return;
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        BackfillAttachment.State state = BackfillAttachment.getOrCreate(world);
        if (state.decision() != BackfillAttachment.Decision.PENDING) return;

        BackfillAttachment.setDecision(world, BackfillAttachment.Decision.ENABLED);
        player.sendMessage(Text.literal("[blockorigin] ").formatted(Formatting.GOLD)
                .append(Text.literal("Enabled for this world. Chunks will be scanned as you explore.")
                        .formatted(Formatting.WHITE)), false);
        player.sendMessage(Text.literal("  ").formatted(Formatting.GRAY)
                .append(Text.literal("/blockorigin backfill").formatted(Formatting.GREEN))
                .append(Text.literal(" for an explicit whole-world pass, or "))
                .append(Text.literal("/blockorigin backfill disable").formatted(Formatting.YELLOW))
                .append(Text.literal(" to opt out.")), false);
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
