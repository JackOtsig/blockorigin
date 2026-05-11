package origin.jack.blockorigin.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.entity.Entity;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.projectile.WitherSkullEntity;
import net.minecraft.world.explosion.ExplosionImpl;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.api.BlockOrigin;

/**
 * Pushes the matching {@code EXPLOSION_*} cause for the duration of an
 * explosion's {@link ExplosionImpl#explode} call. Dispatches by source entity:
 * TNT, creeper, wither (direct or via skull), end crystal, falls through to
 * {@link BlockCause#EXPLOSION_OTHER} for everything else.
 *
 * <p>Bed and respawn-anchor explosions pass {@code null} as source, so they
 * land in {@code OTHER} today. {@code EXPLOSION_BED} / {@code EXPLOSION_ANCHOR}
 * will need their own mixins on {@code BedBlock} / {@code RespawnAnchorBlock}
 * to push the cause before calling {@code createExplosion}.
 */
@Mixin(ExplosionImpl.class)
public abstract class ExplosionImplMixin {

    @Shadow public abstract @Nullable Entity getEntity();

    @WrapMethod(method = "explode")
    private int blockorigin$pushExplodeCause(Operation<Integer> original) {
        BlockCause cause = blockorigin$classify(getEntity());
        try (BlockOrigin.CauseFrame ignored = BlockOrigin.pushCause(cause)) {
            return original.call();
        }
    }

    @Unique
    private static BlockCause blockorigin$classify(@Nullable Entity entity) {
        if (entity instanceof TntEntity) return BlockCause.EXPLOSION_TNT;
        if (entity instanceof CreeperEntity) return BlockCause.EXPLOSION_CREEPER;
        if (entity instanceof WitherEntity || entity instanceof WitherSkullEntity) return BlockCause.EXPLOSION_WITHER;
        if (entity instanceof EndCrystalEntity) return BlockCause.EXPLOSION_END_CRYSTAL;
        return BlockCause.EXPLOSION_OTHER;
    }
}
