package origin.jack.blockorigin.mixin;

import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the per-world {@link NoiseConfig} so we can run shadow worldgen on demand. */
@Mixin(ServerChunkLoadingManager.class)
public interface ServerChunkLoadingManagerAccessor {
    @Invoker("getNoiseConfig")
    NoiseConfig blockorigin$getNoiseConfig();
}
