package origin.jack.blockorigin.compat;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import origin.jack.blockorigin.BlockOriginMod;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.api.BlockOrigin;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IServerDataProvider;

/**
 * Server side: writes the cause Identifier into Jade's per-tooltip NBT so the
 * client can read it back without us having to sync the full chunk attachment.
 * Skips writing when there's nothing to show (default {@code WORLDGEN_TERRAIN}
 * on a chunk with no data) so untouched terrain doesn't show a redundant line.
 */
public enum BlockOriginServerData implements IServerDataProvider<BlockAccessor> {
    INSTANCE;

    public static final Identifier UID = Identifier.of(BlockOriginMod.MOD_ID, "cause");
    static final String NBT_KEY = "cause";

    @Override
    public void appendServerData(NbtCompound data, BlockAccessor accessor) {
        World world = accessor.getLevel();
        BlockCause cause = BlockOrigin.get(world, accessor.getPosition());
        data.putString(NBT_KEY, cause.id().toString());
    }

    @Override
    public Identifier getUid() {
        return UID;
    }
}
