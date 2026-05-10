package origin.jack.blockorigin.compat;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import origin.jack.blockorigin.BlockOriginMod;
import origin.jack.blockorigin.api.BlockCause;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

/**
 * Client side: reads the cause Identifier that {@link BlockOriginServerData}
 * stuffed into the per-tooltip NBT and appends a "Origin: ..." line to the
 * Jade hover. Toggleable in Jade's config under the
 * {@code blockorigin:cause_tooltip} key.
 */
public enum BlockOriginTooltip implements IBlockComponentProvider {
    INSTANCE;

    public static final Identifier UID = Identifier.of(BlockOriginMod.MOD_ID, "cause_tooltip");

    @Override
    public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
        if (!config.get(UID)) return;
        String causeId = accessor.getServerData().getString(BlockOriginServerData.NBT_KEY).orElse(null);
        if (causeId == null || causeId.isEmpty()) return;
        Identifier id = Identifier.tryParse(causeId);
        BlockCause cause = id == null ? null : BlockCause.fromId(id);
        String display = cause != null ? cause.displayName() : causeId;
        tooltip.add(Text.literal("Origin: ").formatted(Formatting.GRAY)
                .append(Text.literal(display).formatted(Formatting.WHITE)));
    }

    @Override
    public Identifier getUid() {
        return UID;
    }
}
