package origin.jack.blockorigin.internal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Identifier;
import origin.jack.blockorigin.api.BlockCause;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * On-disk codecs for cause data.
 *
 * <p>The byte arrays in {@link CauseSection} hold {@link BlockCause#ordinal()},
 * which is unstable across versions. The wire format instead stores each
 * section's distinct causes as a palette of stable {@link Identifier}s plus a
 * {@code byte[}{@value CauseSection#VOLUME}{@code ]} of palette indices, so
 * reordering or removing enum values can never corrupt saves. Causes that no
 * longer exist on load decode to {@link BlockCause#UNKNOWN}.
 *
 * <p>Uniform sections elide the index array; only their single-entry palette
 * is written.
 */
public final class CauseCodecs {

    private CauseCodecs() {}

    /** Stable id-based codec; ids that no longer resolve decode to {@link BlockCause#UNKNOWN}. */
    public static final Codec<BlockCause> CAUSE = Identifier.CODEC.xmap(
            id -> {
                BlockCause c = BlockCause.fromId(id);
                return c == null ? BlockCause.UNKNOWN : c;
            },
            BlockCause::id
    );

    private static final Codec<byte[]> BYTES = Codec.BYTE_BUFFER.xmap(
            buf -> {
                byte[] arr = new byte[buf.remaining()];
                buf.get(arr);
                return arr;
            },
            ByteBuffer::wrap
    );

    private record SerializedSection(int relIndex, List<BlockCause> palette, Optional<byte[]> indices) {
        static final Codec<SerializedSection> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("y").forGetter(SerializedSection::relIndex),
                CAUSE.listOf().fieldOf("palette").forGetter(SerializedSection::palette),
                BYTES.optionalFieldOf("data").forGetter(SerializedSection::indices)
        ).apply(i, SerializedSection::new));
    }

    private record SerializedChunkData(int bottom, int count, boolean backfilled, List<SerializedSection> sections) {
        static final Codec<SerializedChunkData> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("bottom").forGetter(SerializedChunkData::bottom),
                Codec.INT.fieldOf("count").forGetter(SerializedChunkData::count),
                Codec.BOOL.optionalFieldOf("backfilled", false).forGetter(SerializedChunkData::backfilled),
                SerializedSection.CODEC.listOf().fieldOf("sections").forGetter(SerializedChunkData::sections)
        ).apply(i, SerializedChunkData::new));
    }

    public static final Codec<CauseChunkData> CHUNK_DATA = SerializedChunkData.CODEC.xmap(
            CauseCodecs::deserialize,
            CauseCodecs::serialize
    );

    private static CauseChunkData deserialize(SerializedChunkData s) {
        CauseChunkData data = new CauseChunkData(s.bottom(), s.count());
        data.setBackfilled(s.backfilled());
        for (SerializedSection ss : s.sections()) {
            CauseSection section = decodeSection(ss);
            if (section != null) data.installSection(ss.relIndex(), section);
        }
        return data;
    }

    private static CauseSection decodeSection(SerializedSection ss) {
        List<BlockCause> palette = ss.palette();
        if (palette.isEmpty()) return null;
        if (ss.indices().isEmpty()) {
            return new CauseSection(palette.get(0));
        }
        byte[] paletteIdx = ss.indices().get();
        if (paletteIdx.length != CauseSection.VOLUME) return null;
        byte[] ordinals = new byte[CauseSection.VOLUME];
        int paletteSize = palette.size();
        for (int j = 0; j < CauseSection.VOLUME; j++) {
            int idx = paletteIdx[j] & 0xFF;
            BlockCause c = idx < paletteSize ? palette.get(idx) : BlockCause.UNKNOWN;
            ordinals[j] = (byte) c.ordinal();
        }
        return CauseSection.dense(ordinals);
    }

    private static SerializedChunkData serialize(CauseChunkData data) {
        List<SerializedSection> out = new ArrayList<>();
        boolean backfilled = data.isBackfilled();
        for (int i = 0; i < data.sectionCount(); i++) {
            CauseSection s = data.sectionAt(i);
            if (s == null) continue;
            s.tryCompact();
            out.add(encodeSection(i, s));
        }
        return new SerializedChunkData(data.bottomSectionCoord(), data.sectionCount(), backfilled, out);
    }

    private static SerializedSection encodeSection(int relIndex, CauseSection s) {
        if (s.isUniform()) {
            return new SerializedSection(relIndex, List.of(s.uniformCause()), Optional.empty());
        }
        byte[] dense = s.denseOrNull();
        BlockCause[] all = BlockCause.values();
        List<BlockCause> palette = new ArrayList<>();
        Map<BlockCause, Integer> reverse = new HashMap<>();
        byte[] indices = new byte[CauseSection.VOLUME];
        for (int j = 0; j < CauseSection.VOLUME; j++) {
            BlockCause c = all[dense[j] & 0xFF];
            Integer idx = reverse.get(c);
            if (idx == null) {
                idx = palette.size();
                palette.add(c);
                reverse.put(c, idx);
            }
            indices[j] = (byte) (int) idx;
        }
        return new SerializedSection(relIndex, palette, Optional.of(indices));
    }
}
