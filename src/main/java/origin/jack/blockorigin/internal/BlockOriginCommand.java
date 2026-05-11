package origin.jack.blockorigin.internal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.block.BlockState;
import net.minecraft.command.DefaultPermissions;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.DebugChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.api.BlockOrigin;

// BackfillAttachment is in the same package, no import needed.

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Debug commands for blockorigin. Currently a single subcommand:
 *
 * <pre>/blockorigin shadow &lt;pos&gt;</pre>
 *
 * Compares the live block at {@code pos} against what shadow worldgen
 * (post-noise + surface, no carvers/features) would produce at the same XZ
 * column, reports per-position and whole-column match stats. Used to sanity
 * check that the live world's chunk generator reproduces saved blocks
 * deterministically — the precondition for any backfill scheme.
 */
public final class BlockOriginCommand {

    private BlockOriginCommand() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("blockorigin")
                .requires(s -> s.getPermissions().hasPermission(DefaultPermissions.GAMEMASTERS))
                .then(literal("shadow")
                        .then(argument("pos", BlockPosArgumentType.blockPos())
                                .executes(BlockOriginCommand::runShadow)))
                .then(literal("lookup")
                        .executes(c -> runLookup(c.getSource(), BlockPos.ofFloored(c.getSource().getPosition())))
                        .then(literal("near")
                                .executes(c -> runLookupNear(c.getSource(), 4))
                                .then(argument("radius", IntegerArgumentType.integer(1, 16))
                                        .executes(c -> runLookupNear(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))
                        .then(argument("pos", BlockPosArgumentType.blockPos())
                                .executes(c -> runLookup(c.getSource(), BlockPosArgumentType.getLoadedBlockPos(c, "pos")))))
                .then(literal("backfill")
                        .executes(c -> enableAndStartBackfill(c.getSource()))
                        .then(literal("disable").executes(c -> setBackfill(c.getSource(), BackfillAttachment.Decision.DISABLED)))
                        .then(literal("status").executes(c -> showBackfillStatus(c.getSource())))
                        .then(literal("cancel").executes(c -> cancelBackfill(c.getSource())))
                        .then(literal("scan")
                                .executes(c -> runScan(c.getSource(), 4))
                                .then(argument("radius", IntegerArgumentType.integer(0, 16))
                                        .executes(c -> runScan(c.getSource(), IntegerArgumentType.getInteger(c, "radius")))))));
    }

    /**
     * {@code /blockorigin backfill} → set the consent flag AND start a
     * tick-driven bulk backfill of every saved chunk for this dimension.
     * Progress is reported in chat every 10%; final result on completion.
     */
    private static int enableAndStartBackfill(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        if (BackfillRunner.isRunning(world)) {
            source.sendError(Text.literal("[blockorigin] backfill is already running for this world."));
            return 0;
        }
        setBackfill(source, BackfillAttachment.Decision.ENABLED);
        java.util.UUID initiator = source.getEntity() instanceof net.minecraft.server.network.ServerPlayerEntity p
                ? p.getUuid() : null;
        int queued = BackfillRunner.start(world, source.getServer(), initiator);
        if (queued < 0) {
            source.sendError(Text.literal("[blockorigin] could not read region directory; backfill not started."));
            return 0;
        }
        final int q = queued;
        Identifier dim = world.getRegistryKey().getValue();
        source.sendFeedback(() -> Text.literal(String.format(
                "[blockorigin] backfill started for %s — %,d saved chunks queued. Progress will report every 10%%.",
                dim, q)).formatted(Formatting.GOLD), true);
        return queued;
    }

    private static int cancelBackfill(ServerCommandSource source) {
        boolean cancelled = BackfillRunner.cancel(source.getWorld());
        if (cancelled) {
            source.sendFeedback(() -> Text.literal("[blockorigin] backfill cancelled.")
                    .formatted(Formatting.YELLOW), true);
            return 1;
        }
        source.sendError(Text.literal("[blockorigin] no backfill is running for this world."));
        return 0;
    }

    private static int setBackfill(ServerCommandSource source, BackfillAttachment.Decision decision) {
        ServerWorld world = source.getWorld();
        BackfillAttachment.setDecision(world, decision);
        Identifier dim = world.getRegistryKey().getValue();
        source.sendFeedback(() -> Text.literal("[blockorigin] backfill ")
                .append(Text.literal(decision.asString()).formatted(decision == BackfillAttachment.Decision.ENABLED ? Formatting.GREEN : Formatting.YELLOW))
                .append(Text.literal(" for " + dim)), true);
        return 1;
    }

    /**
     * Synchronous scan of currently-loaded chunks in a square around the executor.
     * Useful for ad-hoc inspection; for a whole-world job, use
     * {@code /blockorigin backfill enable} which queues all saved chunks.
     */
    private static int runScan(ServerCommandSource source, int radius) {
        ServerWorld world = source.getWorld();
        BlockPos origin = BlockPos.ofFloored(source.getPosition());
        int cx = origin.getX() >> 4;
        int cz = origin.getZ() >> 4;

        int chunksProcessed = 0, chunksSkipped = 0;
        long matches = 0, mismatches = 0;
        long startNanos = System.nanoTime();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int chunkX = cx + dx;
                int chunkZ = cz + dz;
                WorldChunk chunk = world.getChunkManager().getWorldChunk(chunkX, chunkZ);
                if (chunk == null) { chunksSkipped++; continue; }
                ShadowGen.ChunkScanResult res = ShadowGen.diffAndStamp(world, chunk);
                matches += res.matches();
                mismatches += res.mismatches();
                chunksProcessed++;
            }
        }

        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
        final int cp = chunksProcessed, cs = chunksSkipped;
        final long m = matches, mm = mismatches, t = elapsedMs;
        source.sendFeedback(() -> Text.literal(String.format(
                "[blockorigin] scan: %d chunks, %d unloaded skipped — %,d worldgen stamps, %,d mismatches left as Unknown — %d ms",
                cp, cs, m, mm, t
        )).formatted(Formatting.GRAY), true);
        return chunksProcessed;
    }

    private static int showBackfillStatus(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        BackfillAttachment.State state = BackfillAttachment.getOrCreate(world);
        Identifier dim = world.getRegistryKey().getValue();
        source.sendFeedback(() -> Text.literal("[blockorigin] backfill status for " + dim + ": ")
                .append(Text.literal(state.decision().asString())
                        .formatted(switch (state.decision()) {
                            case ENABLED -> Formatting.GREEN;
                            case DISABLED -> Formatting.GRAY;
                            case PENDING -> Formatting.YELLOW;
                        }))
                .append(Text.literal(" (mc " + state.mcVersion() + ")")), false);
        BackfillRunner.Task running = BackfillRunner.currentTask(world);
        if (running != null) {
            int pct = running.total() == 0 ? 100 : (running.processed() * 100 / running.total());
            source.sendFeedback(() -> Text.literal(String.format(
                    "  running: %d%% (%,d/%,d chunks)",
                    pct, running.processed(), running.total())).formatted(Formatting.GRAY), false);
        }
        return 1;
    }

    /**
     * Scan a cube of {@code (2r+1)³} blocks around the executor and report
     * counts of each non-{@code UNKNOWN} cause, with a sample position per
     * cause. Useful for seeing what got stamped in an area without typing
     * coordinates. Prints to chat and the server log.
     */
    private static int runLookupNear(ServerCommandSource source, int radius) {
        ServerWorld world = source.getWorld();
        BlockPos center = BlockPos.ofFloored(source.getPosition());
        int minY = world.getBottomY();
        int maxY = world.getTopYInclusive();

        java.util.EnumMap<BlockCause, Counter> counts = new java.util.EnumMap<>(BlockCause.class);
        int total = 0, unknown = 0;
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    int x = center.getX() + dx;
                    int y = center.getY() + dy;
                    int z = center.getZ() + dz;
                    if (y < minY || y > maxY) continue;
                    BlockCause c = BlockOrigin.get(world, mut.set(x, y, z));
                    total++;
                    if (c == BlockCause.UNKNOWN) { unknown++; continue; }
                    Counter cnt = counts.computeIfAbsent(c, k -> new Counter());
                    cnt.count++;
                    if (cnt.first == null) cnt.first = mut.toImmutable();
                }
            }
        }

        java.util.List<java.util.Map.Entry<BlockCause, Counter>> sorted = new java.util.ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> Integer.compare(b.getValue().count, a.getValue().count));

        String header = String.format("[blockorigin] lookup near @ %s, radius %d (%d blocks, %d Unknown)",
                center.toShortString(), radius, total, unknown);
        source.sendFeedback(() -> Text.literal(header).formatted(Formatting.GRAY), false);
        origin.jack.blockorigin.BlockOriginMod.LOGGER.info(header);

        if (sorted.isEmpty()) {
            source.sendFeedback(() -> Text.literal("  (no stamped causes in this area)").formatted(Formatting.DARK_GRAY), false);
            return total;
        }
        for (java.util.Map.Entry<BlockCause, Counter> e : sorted) {
            BlockCause cause = e.getKey();
            Counter cnt = e.getValue();
            String line = String.format("  %s: %,d  (e.g. %s)",
                    cause.displayName(), cnt.count, cnt.first.toShortString());
            source.sendFeedback(() -> Text.literal(line).formatted(Formatting.WHITE), false);
            origin.jack.blockorigin.BlockOriginMod.LOGGER.info(line);
        }
        return total;
    }

    private static final class Counter {
        int count = 0;
        BlockPos first = null;
    }

    /**
     * Quick "what's the cause here?" lookup. Works for air positions too, which
     * Jade can't tooltip. Prints to chat AND to the server log so the answer is
     * permanently captured in {@code latest.log}.
     */
    private static int runLookup(ServerCommandSource source, BlockPos pos) {
        ServerWorld world = source.getWorld();
        BlockState live = world.getBlockState(pos);
        BlockCause cause = BlockOrigin.get(world, pos);
        Identifier blockId = Registries.BLOCK.getId(live.getBlock());
        String line = String.format("[blockorigin] lookup @ %s — block=%s, cause=%s (%s)",
                pos.toShortString(), blockId, cause.displayName(), cause.id());
        source.sendFeedback(() -> Text.literal("[blockorigin] lookup @ " + pos.toShortString()).formatted(Formatting.GRAY)
                .append(Text.literal("\n  block: ").formatted(Formatting.GRAY))
                .append(Text.literal(blockId.toString()).formatted(Formatting.WHITE))
                .append(Text.literal("\n  cause: ").formatted(Formatting.GRAY))
                .append(Text.literal(cause.displayName()).formatted(Formatting.WHITE))
                .append(Text.literal(" (" + cause.id() + ")").formatted(Formatting.DARK_GRAY)), false);
        origin.jack.blockorigin.BlockOriginMod.LOGGER.info(line);
        return 1;
    }

    private static int runShadow(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx)
            throws CommandSyntaxException {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();
        BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(ctx, "pos");

        BlockState live = world.getBlockState(pos);
        VerticalBlockSample column = ShadowGen.sampleColumn(world, pos.getX(), pos.getZ());
        BlockState shadow = column.getState(pos.getY());
        BlockCause liveCause = BlockOrigin.get(world, pos);
        boolean match = live.getBlock() == shadow.getBlock();

        ColumnStats stats = analyzeColumn(world, column, pos.getX(), pos.getZ());

        Identifier liveId = Registries.BLOCK.getId(live.getBlock());
        Identifier shadowId = Registries.BLOCK.getId(shadow.getBlock());
        Formatting matchColor = match ? Formatting.GREEN : Formatting.RED;

        Identifier dim = world.getRegistryKey().getValue();
        ChunkGenerator generator = world.getChunkManager().getChunkGenerator();
        String generatorClass = friendlyGeneratorName(generator);
        RegistryEntry<Biome> biome = world.getBiome(pos);
        String biomeId = biome.getKey().map(k -> k.getValue().toString()).orElse("(unknown)");

        source.sendFeedback(() -> Text.literal("blockorigin shadow @ " + pos.toShortString())
                .formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal("  dim/gen: ").formatted(Formatting.GRAY)
                .append(Text.literal(dim + " (" + generatorClass + ")").formatted(Formatting.WHITE)), false);
        source.sendFeedback(() -> Text.literal("  biome:  ").formatted(Formatting.GRAY)
                .append(Text.literal(biomeId).formatted(Formatting.WHITE)), false);
        source.sendFeedback(() -> Text.literal("  live:   ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(liveId)).formatted(Formatting.WHITE)), false);
        source.sendFeedback(() -> Text.literal("  shadow: ").formatted(Formatting.GRAY)
                .append(Text.literal(String.valueOf(shadowId)).formatted(Formatting.WHITE)), false);
        source.sendFeedback(() -> Text.literal("  match:  ").formatted(Formatting.GRAY)
                .append(Text.literal(match ? "yes" : "no").formatted(matchColor)), false);
        source.sendFeedback(() -> Text.literal("  cause:  ").formatted(Formatting.GRAY)
                .append(Text.literal(liveCause.displayName()).formatted(Formatting.WHITE))
                .append(Text.literal(" (" + liveCause.id() + ")").formatted(Formatting.DARK_GRAY)), false);
        source.sendFeedback(() -> Text.literal(String.format(
                "  column: %d / %d match (%.1f%%) — %d air-vs-solid, %d solid-vs-air, %d other",
                stats.matches, stats.total,
                stats.total == 0 ? 0.0 : 100.0 * stats.matches / stats.total,
                stats.airVsSolid, stats.solidVsAir, stats.otherMismatch
        )).formatted(Formatting.GRAY), false);
        source.sendFeedback(() -> Text.literal(String.format(
                "  shadow first-solid-Y: %s, live first-solid-Y: %s",
                stats.shadowFirstSolidY == Integer.MIN_VALUE ? "(none)" : String.valueOf(stats.shadowFirstSolidY),
                stats.liveFirstSolidY == Integer.MIN_VALUE ? "(none)" : String.valueOf(stats.liveFirstSolidY)
        )).formatted(Formatting.GRAY), false);

        return match ? 1 : 0;
    }

    /**
     * Map known generator classes to readable names. {@code instanceof} against Yarn
     * names compiles to intermediary checks at runtime, so this works post-remap.
     */
    private static String friendlyGeneratorName(ChunkGenerator generator) {
        if (generator instanceof FlatChunkGenerator) return "Flat";
        if (generator instanceof NoiseChunkGenerator) return "Noise";
        if (generator instanceof DebugChunkGenerator) return "Debug";
        return generator.getClass().getSimpleName();
    }

    private record ColumnStats(int total, int matches, int airVsSolid, int solidVsAir, int otherMismatch,
                               int shadowFirstSolidY, int liveFirstSolidY) {}

    private static ColumnStats analyzeColumn(ServerWorld world, VerticalBlockSample column, int x, int z) {
        int min = world.getBottomY();
        int max = world.getTopYInclusive();
        int total = 0, matches = 0, airVsSolid = 0, solidVsAir = 0, otherMismatch = 0;
        int shadowFirstSolidY = Integer.MIN_VALUE;
        int liveFirstSolidY = Integer.MIN_VALUE;
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int y = min; y <= max; y++) {
            BlockState s;
            try {
                s = column.getState(y);
            } catch (ArrayIndexOutOfBoundsException e) {
                continue;
            }
            BlockState l = world.getBlockState(mut.set(x, y, z));
            if (shadowFirstSolidY == Integer.MIN_VALUE && !s.isAir()) shadowFirstSolidY = y;
            if (liveFirstSolidY == Integer.MIN_VALUE && !l.isAir()) liveFirstSolidY = y;
            total++;
            if (l.getBlock() == s.getBlock()) {
                matches++;
            } else {
                boolean liveAir = l.isAir();
                boolean shadowAir = s.isAir();
                if (liveAir && !shadowAir) airVsSolid++;
                else if (!liveAir && shadowAir) solidVsAir++;
                else otherMismatch++;
            }
        }
        return new ColumnStats(total, matches, airVsSolid, solidVsAir, otherMismatch,
                shadowFirstSolidY, liveFirstSolidY);
    }
}
