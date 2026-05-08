package origin.jack.blockorigin.internal;

import origin.jack.blockorigin.api.BlockCause;

import java.util.Arrays;

/**
 * Cause storage for one 16x16x16 chunk section.
 *
 * <p>Two states:
 * <ul>
 *   <li><b>Uniform</b>: all 4096 entries share one cause; we store a single byte.</li>
 *   <li><b>Dense</b>: lazily promoted {@code byte[4096]} when any entry diverges.</li>
 * </ul>
 *
 * <p>Index layout matches vanilla section storage: {@code (y << 8) | (z << 4) | x}
 * for local coords 0-15.
 *
 * <p>Stores {@link BlockCause#ordinal()} as the byte. The on-disk Codec maps
 * ordinals to/from the cause's stable {@link net.minecraft.util.Identifier}, so
 * reordering the enum can never corrupt saves.
 *
 * <p>Not thread-safe; callers must coordinate with the chunk lock used by
 * vanilla block writes.
 */
public final class CauseSection {

    public static final int VOLUME = 16 * 16 * 16;

    private byte uniform;
    private byte[] dense;

    public CauseSection(BlockCause initial) {
        this.uniform = (byte) initial.ordinal();
        this.dense = null;
    }

    private CauseSection(byte uniform, byte[] dense) {
        this.uniform = uniform;
        this.dense = dense;
    }

    /** Construct a dense section taking ownership of the given ordinal array (length {@link #VOLUME}). */
    public static CauseSection dense(byte[] ordinals) {
        if (ordinals.length != VOLUME) {
            throw new IllegalArgumentException("dense section needs " + VOLUME + " entries, got " + ordinals.length);
        }
        return new CauseSection((byte) 0, ordinals);
    }

    public boolean isUniform() {
        return dense == null;
    }

    /** Cause when {@link #isUniform()} is true; meaningless otherwise. */
    public BlockCause uniformCause() {
        return BlockCause.values()[uniform & 0xFF];
    }

    /** Direct backing array, or {@code null} if uniform. Internal use only. */
    public byte[] denseOrNull() {
        return dense;
    }

    public BlockCause get(int localX, int localY, int localZ) {
        if (dense == null) return BlockCause.values()[uniform & 0xFF];
        return BlockCause.values()[dense[index(localX, localY, localZ)] & 0xFF];
    }

    public void set(int localX, int localY, int localZ, BlockCause cause) {
        byte b = (byte) cause.ordinal();
        if (dense == null) {
            if (b == uniform) return;
            promote();
        }
        dense[index(localX, localY, localZ)] = b;
    }

    /** Compact a dense section back to uniform if all entries match. */
    public void tryCompact() {
        if (dense == null) return;
        byte first = dense[0];
        for (int i = 1; i < VOLUME; i++) {
            if (dense[i] != first) return;
        }
        uniform = first;
        dense = null;
    }

    private void promote() {
        dense = new byte[VOLUME];
        Arrays.fill(dense, uniform);
    }

    private static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }
}
