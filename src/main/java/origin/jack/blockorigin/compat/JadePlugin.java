package origin.jack.blockorigin.compat;

import net.minecraft.block.Block;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;

/**
 * Jade compatibility entrypoint. Wired via {@code fabric.mod.json}'s
 * {@code entrypoints.jade}, so this class is only loaded when Jade is
 * actually present at runtime — the rest of the mod compiles and runs
 * without Jade installed.
 */
public final class JadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(BlockOriginServerData.INSTANCE, Block.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        // registerBlockComponent already creates the config toggle from the provider's UID,
        // so we don't (and must not) call addConfig separately — Jade rejects duplicate keys.
        registration.registerBlockComponent(BlockOriginTooltip.INSTANCE, Block.class);
    }
}
