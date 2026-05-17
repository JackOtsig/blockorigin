package origin.jack.blockorigin.internal;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Lazy-on-load chunk scanner. When a chunk loads in a world whose decision
 * isn't {@code DISABLED}, and the chunk hasn't already been backfilled, queue
 * it for processing. A per-tick worker drains the queue at
 * {@code Config.chunksPerTick} chunks per server tick, running
 * {@link ShadowGen#diffAndStamp} which marks the chunk backfilled.
 *
 * <p>One queue per world. Queues are in-memory only — if the server stops with
 * pending entries, they re-queue naturally next time the chunks load.
 */
public final class ChunkLoadWatcher {

    private static final Map<RegistryKey<World>, Queue> QUEUES = new HashMap<>();

    private ChunkLoadWatcher() {}

    public static void register() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            BackfillAttachment.State state = BackfillAttachment.getOrCreate(world);
            if (state.decision() == BackfillAttachment.Decision.DISABLED) return;
            CauseChunkData data = chunk.getAttached(CauseAttachment.CAUSES);
            if (data != null && data.isBackfilled()) return;
            queue(world, chunk.getPos());
        });
        ServerTickEvents.END_WORLD_TICK.register(ChunkLoadWatcher::tickWorld);
    }

    private static void queue(ServerWorld world, ChunkPos pos) {
        QUEUES.computeIfAbsent(world.getRegistryKey(), k -> new Queue()).add(pos);
    }

    private static void tickWorld(ServerWorld world) {
        Queue q = QUEUES.get(world.getRegistryKey());
        if (q == null || q.isEmpty()) return;
        int budget = Config.get().chunksPerTick();
        for (int i = 0; i < budget && !q.isEmpty(); i++) {
            ChunkPos pos = q.poll();
            WorldChunk c = world.getChunkManager().getWorldChunk(pos.x, pos.z);
            if (c == null) continue;  // unloaded since queueing
            CauseChunkData data = CauseAttachment.getOrCreate(c);
            if (data.isBackfilled()) continue;
            // Async: shadow noise runs off-thread, diff + stamp comes back on
            // server thread. Server tick never blocks waiting for noise gen.
            ShadowGen.diffAndStampAsync(world, c).exceptionally(ex -> {
                origin.jack.blockorigin.BlockOriginMod.LOGGER.warn(
                        "lazy backfill: chunk {} failed: {}", pos, ex.toString());
                return null;
            });
        }
    }

    /** In-memory FIFO of chunk positions with a side-set for O(1) dedup. */
    private static final class Queue {
        private final Deque<ChunkPos> order = new ArrayDeque<>();
        private final Set<ChunkPos> seen = new HashSet<>();

        void add(ChunkPos pos) {
            if (seen.add(pos)) order.addLast(pos);
        }

        ChunkPos poll() {
            ChunkPos pos = order.pollFirst();
            if (pos != null) seen.remove(pos);
            return pos;
        }

        boolean isEmpty() { return order.isEmpty(); }
    }
}
