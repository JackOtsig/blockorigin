package origin.jack.blockorigin.internal;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.util.Identifier;
import net.minecraft.world.chunk.Chunk;
import origin.jack.blockorigin.BlockOriginMod;

/**
 * Registers the {@link CauseChunkData} attachment on every {@link Chunk} (both
 * {@code WorldChunk} and {@code ProtoChunk}) and persists it via Fabric's
 * Data Attachment API. Save/load and the proto→world transition are handled
 * by Fabric — we only own creation and lookup.
 */
public final class CauseAttachment {

    public static final AttachmentType<CauseChunkData> CAUSES = AttachmentRegistry.createPersistent(
            Identifier.of(BlockOriginMod.MOD_ID, "causes"),
            CauseCodecs.CHUNK_DATA
    );

    private CauseAttachment() {}

    /** Force class initialization so the AttachmentType registers during mod init. */
    public static void init() {}

    /**
     * Returns the cause data attached to {@code chunk}, lazily creating and
     * attaching it on first access. {@link AttachmentTarget#setAttached} marks
     * the chunk dirty automatically; in-place mutations of the returned object
     * still need a follow-up {@link Chunk#markNeedsSaving()}.
     */
    public static CauseChunkData getOrCreate(Chunk chunk) {
        CauseChunkData existing = chunk.getAttached(CAUSES);
        if (existing != null) return existing;
        CauseChunkData fresh = new CauseChunkData(chunk.getHeightLimitView());
        chunk.setAttached(CAUSES, fresh);
        return fresh;
    }
}
