package origin.jack.blockorigin.internal;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.SharedConstants;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import origin.jack.blockorigin.BlockOriginMod;

/**
 * Per-world attachment that records the user's decision about whether
 * blockorigin should attempt a one-time shadow-worldgen backfill of
 * pre-mod-install blocks for this world. The decision is persisted, so
 * the prompt is shown only once per world.
 */
public final class BackfillAttachment {

    private BackfillAttachment() {}

    public enum Decision implements StringIdentifiable {
        PENDING("pending"),
        ENABLED("enabled"),
        DISABLED("disabled");

        public static final Codec<Decision> CODEC = StringIdentifiable.createCodec(Decision::values);

        private final String name;

        Decision(String name) { this.name = name; }

        @Override public String asString() { return name; }
    }

    public record State(Decision decision, String mcVersion) {
        public static final Codec<State> CODEC = RecordCodecBuilder.create(i -> i.group(
                Decision.CODEC.fieldOf("decision").forGetter(State::decision),
                Codec.STRING.fieldOf("mc_version").forGetter(State::mcVersion)
        ).apply(i, State::new));

        public static State pending() {
            return new State(Decision.PENDING, SharedConstants.getGameVersion().name());
        }

        public State withDecision(Decision newDecision) {
            return new State(newDecision, mcVersion);
        }
    }

    public static final AttachmentType<State> BACKFILL = AttachmentRegistry.createPersistent(
            Identifier.of(BlockOriginMod.MOD_ID, "backfill"),
            State.CODEC
    );

    /** Force class init so the AttachmentType registers during mod init. */
    public static void init() {}

    /** Get the per-world state, lazily creating a {@link Decision#PENDING} entry on first access. */
    public static State getOrCreate(ServerWorld world) {
        return world.getAttachedOrCreate(BACKFILL, State::pending);
    }

    /** Update the world's decision and mark it dirty so the change persists. */
    public static void setDecision(ServerWorld world, Decision newDecision) {
        State current = getOrCreate(world);
        world.setAttached(BACKFILL, current.withDecision(newDecision));
    }
}
