package origin.jack.blockorigin.internal;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.Chunk;
import org.jetbrains.annotations.Nullable;
import origin.jack.blockorigin.BlockOriginMod;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runs at most one bulk backfill per world. The worker is a tick-driven queue:
 * each server tick, it pops up to {@link #CHUNKS_PER_TICK} chunks, force-loads
 * them, runs {@link ShadowGen#diffAndStamp}, and reports progress every 10%.
 *
 * <p>The queue is built up-front from {@link RegionScanner} so we only touch
 * chunks that already exist on disk — no accidental generation of never-touched
 * chunks. After processing, MC's normal chunk-unload sweep reclaims them.
 */
public final class BackfillRunner {

    private static final Map<RegistryKey<World>, Task> TASKS = new HashMap<>();

    private BackfillRunner() {}

    /** Hook the server tick event. Call once during mod init. */
    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(BackfillRunner::tickWorld);
    }

    /** True if a backfill is currently running for {@code world}. */
    public static boolean isRunning(ServerWorld world) {
        return TASKS.containsKey(world.getRegistryKey());
    }

    /**
     * Discover saved chunks for the world's dimension and start a task. Returns
     * the number of chunks queued, or -1 if discovery failed (region dir missing
     * or unreadable).
     */
    public static int start(ServerWorld world, MinecraftServer server, @Nullable UUID initiator) {
        Path regionDir = regionDirFor(world, server);
        if (regionDir == null) return -1;
        List<ChunkPos> chunks;
        try {
            chunks = RegionScanner.scan(regionDir);
        } catch (IOException e) {
            BlockOriginMod.LOGGER.warn("backfill: failed to scan {}: {}", regionDir, e.getMessage());
            return -1;
        }
        TASKS.put(world.getRegistryKey(), new Task(world, server, initiator, chunks));
        return chunks.size();
    }

    public static boolean cancel(ServerWorld world) {
        return TASKS.remove(world.getRegistryKey()) != null;
    }

    @Nullable
    public static Task currentTask(ServerWorld world) {
        return TASKS.get(world.getRegistryKey());
    }

    private static void tickWorld(ServerWorld world) {
        Task task = TASKS.get(world.getRegistryKey());
        if (task == null) return;
        task.tick();
        if (task.isDone()) {
            TASKS.remove(world.getRegistryKey());
        }
    }

    /** Resolves the on-disk region/ folder for a vanilla dimension. */
    @Nullable
    private static Path regionDirFor(ServerWorld world, MinecraftServer server) {
        Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
        RegistryKey<World> key = world.getRegistryKey();
        if (key.equals(World.OVERWORLD)) return worldRoot.resolve("region");
        if (key.equals(World.NETHER))   return worldRoot.resolve("DIM-1").resolve("region");
        if (key.equals(World.END))      return worldRoot.resolve("DIM1").resolve("region");
        // Custom dim: dimensions/<namespace>/<path>/region
        return worldRoot.resolve("dimensions")
                .resolve(key.getValue().getNamespace())
                .resolve(key.getValue().getPath())
                .resolve("region");
    }

    public static final class Task {
        private final ServerWorld world;
        private final MinecraftServer server;
        @Nullable private final UUID initiatorUuid;
        private final Deque<ChunkPos> queue;
        private final int total;
        private final long startNanos = System.nanoTime();
        private int processed = 0;
        private long matches = 0;
        private long mismatches = 0;
        private int lastReportedTenth = -1;

        private final int chunksPerTick;

        Task(ServerWorld world, MinecraftServer server, @Nullable UUID initiator, List<ChunkPos> chunks) {
            this.world = world;
            this.server = server;
            this.initiatorUuid = initiator;
            this.queue = new ArrayDeque<>(chunks);
            this.total = chunks.size();
            this.chunksPerTick = Config.get().chunksPerTick();
        }

        public int total() { return total; }
        public int processed() { return processed; }
        public boolean isDone() { return queue.isEmpty(); }

        void tick() {
            for (int i = 0; i < chunksPerTick && !queue.isEmpty(); i++) {
                ChunkPos pos = queue.poll();
                processOne(pos);
                processed++;
            }
            maybeReportProgress();
            if (queue.isEmpty()) reportFinal();
        }

        private void processOne(ChunkPos pos) {
            Chunk c = world.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
            if (!(c instanceof WorldChunk wc)) return;
            ShadowGen.ChunkScanResult res = ShadowGen.diffAndStamp(world, wc);
            matches += res.matches();
            mismatches += res.mismatches();
        }

        private void maybeReportProgress() {
            int tenth = total == 0 ? 10 : (processed * 10 / total);
            if (tenth > lastReportedTenth && tenth < 10) {
                lastReportedTenth = tenth;
                broadcast(progressText(tenth * 10));
            }
        }

        private void reportFinal() {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            broadcast(Text.literal(String.format(
                    "[blockorigin] backfill done: %,d chunks, %,d worldgen stamps, %,d mismatches — %,d ms",
                    processed, matches, mismatches, elapsedMs)).formatted(Formatting.GREEN));
        }

        private Text progressText(int percent) {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            return Text.literal(String.format(
                    "[blockorigin] backfill: %d%% (%,d/%,d) — %,d match, %,d mismatch, %,d ms",
                    percent, processed, total, matches, mismatches, elapsedMs))
                    .formatted(Formatting.GRAY);
        }

        private void broadcast(Text text) {
            server.sendMessage(text);
            if (initiatorUuid != null) {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(initiatorUuid);
                if (p != null) p.sendMessage(text, false);
            }
        }
    }
}
