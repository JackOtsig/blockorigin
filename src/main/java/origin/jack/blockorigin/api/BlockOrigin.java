package origin.jack.blockorigin.api;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import origin.jack.blockorigin.internal.CauseAttachment;
import origin.jack.blockorigin.internal.CauseChunkData;
import origin.jack.blockorigin.internal.CauseStack;

/**
 * Public API for reading and writing block origin data.
 *
 * <p>All methods are O(1) and allocation-free on the hot path. Reads return
 * {@link BlockCause#UNKNOWN} for any position that has not been stamped — this
 * includes the entire world before the mod was first installed, and any
 * unstamped chunk section after. Specific worldgen sub-causes are only set
 * after a positive match against shadow worldgen, so {@code UNKNOWN} is the
 * honest default rather than a false claim of worldgen.
 *
 * <p>To attribute a block change to a particular cause, push a frame onto the
 * per-thread context stack — any {@code setBlockState} that fires inside is
 * stamped with that cause. Prefer try-with-resources:
 * <pre>{@code
 * try (BlockOrigin.CauseFrame f = BlockOrigin.pushCause(BlockCause.PLAYER_PLACE)) {
 *     world.setBlockState(pos, state);
 * }
 * }</pre>
 * For the simple "run this lambda under a cause" case, {@link #withCause} is
 * equivalent and slightly less verbose.
 *
 * <p>Cause data is persisted across save/load via Fabric's Data Attachment API.
 */
public final class BlockOrigin {

    private BlockOrigin() {}

    /** O(1) lookup. Never returns null. */
    public static BlockCause get(World world, BlockPos pos) {
        Chunk c = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        CauseChunkData data = c.getAttached(CauseAttachment.CAUSES);
        if (data == null) return CauseChunkData.IMPLICIT_DEFAULT;
        return data.get(pos);
    }

    /** O(1) lookup when the caller already has the chunk. */
    public static BlockCause get(WorldChunk chunk, int x, int y, int z) {
        CauseChunkData data = chunk.getAttached(CauseAttachment.CAUSES);
        if (data == null) return CauseChunkData.IMPLICIT_DEFAULT;
        return data.get(x, y, z);
    }

    /** Manually stamp a cause on a single position. */
    public static void set(World world, BlockPos pos, BlockCause cause) {
        Chunk c = world.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        CauseAttachment.getOrCreate(c).set(pos, cause);
        c.markNeedsSaving();
    }

    /**
     * Push {@code cause} onto the thread-local context stack and return a
     * frame whose {@link CauseFrame#close()} pops it. Designed for
     * try-with-resources; the JVM guarantees the pop runs on every exit path,
     * including exceptions and early returns.
     */
    public static CauseFrame pushCause(BlockCause cause) {
        return CauseStack.get().push(cause);
    }

    /**
     * Convenience equivalent of {@link #pushCause} for callers that don't need
     * to throw checked exceptions or return a value. Pops the frame in a
     * {@code finally} block.
     */
    public static void withCause(BlockCause cause, Runnable action) {
        try (CauseFrame ignored = pushCause(cause)) {
            action.run();
        }
    }

    /** Peek at the currently active cause, or {@link BlockCause#UNKNOWN}. */
    public static BlockCause currentCause() {
        return CauseStack.get().peek();
    }

    /** Token returned by {@link #pushCause}; closing pops the frame. */
    public interface CauseFrame extends AutoCloseable {
        @Override void close();
    }
}
