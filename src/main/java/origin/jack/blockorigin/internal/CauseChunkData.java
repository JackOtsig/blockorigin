package origin.jack.blockorigin.internal;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.HeightLimitView;
import origin.jack.blockorigin.api.BlockCause;

/**
 * Cause storage for one chunk: an array of {@link CauseSection} mirroring
 * vanilla's section layout. Sections are lazily allocated; positions in
 * unallocated sections read as the implicit default
 * ({@link BlockCause#WORLDGEN_TERRAIN}).
 *
 * <p>All access is by absolute block coordinates; the chunk's X/Z are not
 * stored here because the {@link net.minecraft.world.chunk.Chunk} attachment
 * already locates this object.
 */
public final class CauseChunkData {

    /**
     * Cause returned for any position that has no recorded stamp. {@link BlockCause#UNKNOWN}
     * is honest: the mod has no evidence of how that block came to be — it could have been
     * worldgen, a mob action before the mod was installed, or anything else. Specific
     * worldgen sub-causes only get stamped after a positive match against shadow worldgen.
     */
    public static final BlockCause IMPLICIT_DEFAULT = BlockCause.UNKNOWN;

    private final int bottomSectionCoord;
    private final CauseSection[] sections;

    /**
     * Whether the chunk has had a full shadow-worldgen diff applied. Lazy
     * on-load scanning checks this flag and skips chunks that have already
     * been processed. Set to {@code true} at the end of
     * {@code ShadowGen.diffAndStamp}; persisted via the codec.
     */
    private boolean backfilled = false;

    public CauseChunkData(HeightLimitView world) {
        this(world.getBottomSectionCoord(), world.countVerticalSections());
    }

    /** Codec-friendly constructor; restores the storage shape without needing the chunk. */
    public CauseChunkData(int bottomSectionCoord, int sectionCount) {
        this.bottomSectionCoord = bottomSectionCoord;
        this.sections = new CauseSection[sectionCount];
    }

    /** Install a previously deserialized section at {@code relIndex}; out-of-range indices are dropped. */
    public void installSection(int relIndex, CauseSection section) {
        if (relIndex < 0 || relIndex >= sections.length) return;
        sections[relIndex] = section;
    }

    public BlockCause get(BlockPos pos) {
        return get(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockCause get(int x, int y, int z) {
        int sectionIndex = (y >> 4) - bottomSectionCoord;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return IMPLICIT_DEFAULT;
        CauseSection s = sections[sectionIndex];
        if (s == null) return IMPLICIT_DEFAULT;
        return s.get(x & 15, y & 15, z & 15);
    }

    public void set(BlockPos pos, BlockCause cause) {
        set(pos.getX(), pos.getY(), pos.getZ(), cause);
    }

    public void set(int x, int y, int z, BlockCause cause) {
        int sectionIndex = (y >> 4) - bottomSectionCoord;
        if (sectionIndex < 0 || sectionIndex >= sections.length) return;
        CauseSection s = sections[sectionIndex];
        if (s == null) {
            if (cause == IMPLICIT_DEFAULT) return;
            s = new CauseSection(IMPLICIT_DEFAULT);
            sections[sectionIndex] = s;
        }
        s.set(x & 15, y & 15, z & 15, cause);
    }

    public CauseSection sectionAt(int sectionIndex) {
        if (sectionIndex < 0 || sectionIndex >= sections.length) return null;
        return sections[sectionIndex];
    }

    public int sectionCount() { return sections.length; }
    public int bottomSectionCoord() { return bottomSectionCoord; }

    public boolean isBackfilled() { return backfilled; }
    public void setBackfilled(boolean backfilled) { this.backfilled = backfilled; }
}
