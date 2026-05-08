package origin.jack.blockorigin.api;

import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The mechanism that put a block in its current state.
 *
 * <p>Each value has a stable {@link Identifier} used for on-disk storage; the
 * runtime ordinal can change between versions without corrupting saved data.
 *
 * <p>Causes group naturally into <i>buckets</i> (worldgen, player, mob,
 * explosion, mechanical, fluid, tick, command, etc.) so consumer mods can
 * filter at whichever granularity they want.
 */
public enum BlockCause {

    /** Default for blocks whose origin we never observed. */
    UNKNOWN("unknown", Bucket.UNKNOWN),

    // ---- World generation ---------------------------------------------------
    /** Base terrain noise: stone, deepslate, sea water, bedrock. */
    WORLDGEN_TERRAIN("worldgen_terrain", Bucket.WORLDGEN),
    /** Surface builder layer: grass, sand, podzol, snow caps. */
    WORLDGEN_SURFACE("worldgen_surface", Bucket.WORLDGEN),
    /** Caves and ravines (creates cave_air). */
    WORLDGEN_CARVER("worldgen_carver", Bucket.WORLDGEN),
    /** Configured features: trees, ores, flowers, lakes, dungeons, geodes, fossils. */
    WORLDGEN_FEATURE("worldgen_feature", Bucket.WORLDGEN),
    /** Structures: villages, strongholds, monuments, trial chambers, ancient cities. */
    WORLDGEN_STRUCTURE("worldgen_structure", Bucket.WORLDGEN),
    /** Block existed before this mod was installed in the world. */
    WORLDGEN_PRE_INSTALL("worldgen_pre_install", Bucket.WORLDGEN),
    /** Rewritten by Mojang's DataFixerUpper during world upgrade. */
    WORLDGEN_DATAFIX("worldgen_datafix", Bucket.WORLDGEN),

    // ---- Player actions -----------------------------------------------------
    PLAYER_PLACE("player_place", Bucket.PLAYER),
    PLAYER_BREAK("player_break", Bucket.PLAYER),
    PLAYER_BUCKET_PLACE("player_bucket_place", Bucket.PLAYER),
    PLAYER_BUCKET_PICK("player_bucket_pick", Bucket.PLAYER),
    /** Hoe till, shovel path, axe strip / scrape, honeycomb wax. */
    PLAYER_TOOL_INTERACT("player_tool_interact", Bucket.PLAYER),
    /** Flint &amp; steel, fire charge, light candle. */
    PLAYER_IGNITE("player_ignite", Bucket.PLAYER),
    PLAYER_BONEMEAL("player_bonemeal", Bucket.PLAYER),
    /** Misc state changes from right-clicking (lever, button, repeater config, sign edit, cake eat). */
    PLAYER_INTERACT("player_interact", Bucket.PLAYER),

    // ---- Mob actions (excluding mob-driven explosions) ---------------------
    /** Wither direct break loop, ravager break, zombie door break, generic mob break. */
    MOB_BREAK("mob_break", Bucket.MOB),
    /** Snow golem trail, generic mob place. */
    MOB_PLACE("mob_place", Bucket.MOB),
    /** Fox berries, rabbit carrots, sheep grass, fox sweet/glow berries. */
    MOB_EAT("mob_eat", Bucket.MOB),
    /** Villager farmer harvest+replant — the replant takes this cause too. */
    MOB_HARVEST("mob_harvest", Bucket.MOB),
    /** Silverfish stone &harr; infested. */
    MOB_INFEST("mob_infest", Bucket.MOB),
    ENDERMAN_PICKUP("enderman_pickup", Bucket.MOB),
    ENDERMAN_PLACE("enderman_place", Bucket.MOB),

    // ---- Explosions ---------------------------------------------------------
    EXPLOSION_TNT("explosion_tnt", Bucket.EXPLOSION),
    EXPLOSION_CREEPER("explosion_creeper", Bucket.EXPLOSION),
    EXPLOSION_WITHER("explosion_wither", Bucket.EXPLOSION),
    EXPLOSION_END_CRYSTAL("explosion_end_crystal", Bucket.EXPLOSION),
    EXPLOSION_BED("explosion_bed", Bucket.EXPLOSION),
    EXPLOSION_ANCHOR("explosion_anchor", Bucket.EXPLOSION),
    /** Other / modded / unspecified explosion source. */
    EXPLOSION_OTHER("explosion_other", Bucket.EXPLOSION),

    // ---- Mechanical ---------------------------------------------------------
    PISTON_PUSH("piston_push", Bucket.MECHANICAL),
    PISTON_PULL("piston_pull", Bucket.MECHANICAL),
    /** Block dropped because piston push couldn't move it (e.g. honey/slime conflict). */
    PISTON_DESTROY("piston_destroy", Bucket.MECHANICAL),
    DISPENSER("dispenser", Bucket.MECHANICAL),
    /** Source pos becoming air as a falling block entity is spawned. */
    FALLING_BLOCK_START("falling_block_start", Bucket.MECHANICAL),
    /** Falling block entity landing. Origin is inherited from pre-fall block. */
    FALLING_BLOCK_LAND("falling_block_land", Bucket.MECHANICAL),

    // ---- Fluid --------------------------------------------------------------
    /** Water/lava spreading via FlowableFluid. */
    FLUID_FLOW("fluid_flow", Bucket.FLUID),
    /** Cobble/obsidian/basalt formation, sponge absorb. */
    FLUID_INTERACT("fluid_interact", Bucket.FLUID),
    /** Water evaporation in nether. */
    FLUID_EVAPORATE("fluid_evaporate", Bucket.FLUID),

    // ---- Tick-based natural processes --------------------------------------
    /**
     * Crop / sapling / sugar cane / kelp / bamboo growth. Used as a fallback
     * cause when origin can't be inherited from the parent block — typically
     * the parent's cause is propagated instead.
     */
    RANDOM_TICK_GROW("random_tick_grow", Bucket.TICK),
    /** Mushroom spread, fire spread, vine spread. */
    RANDOM_TICK_SPREAD("random_tick_spread", Bucket.TICK),
    /** Leaf decay, fire burnout. */
    RANDOM_TICK_DECAY("random_tick_decay", Bucket.TICK),
    /** Copper oxidation. */
    RANDOM_TICK_OXIDIZE("random_tick_oxidize", Bucket.TICK),
    ICE_MELT("ice_melt", Bucket.TICK),
    SNOW_ACCUMULATE("snow_accumulate", Bucket.TICK),
    SCULK_SPREAD("sculk_spread", Bucket.TICK),
    /** Bamboo / sugar cane / cactus / chorus / scaffolding cascading break. */
    SUPPORT_BREAK("support_break", Bucket.TICK),

    // ---- Projectile / lightning --------------------------------------------
    /** Wind charge breaks decorated pot, projectile breaks turtle egg/candle. */
    PROJECTILE_BREAK("projectile_break", Bucket.PROJECTILE),
    /** Splash potion extinguishes fire/campfire. */
    PROJECTILE_INTERACT("projectile_interact", Bucket.PROJECTILE),
    LIGHTNING("lightning", Bucket.LIGHTNING),

    // ---- Commands / structure blocks ---------------------------------------
    COMMAND_SETBLOCK("command_setblock", Bucket.COMMAND),
    COMMAND_FILL_CLONE("command_fill_clone", Bucket.COMMAND),
    /** /place feature/structure/template/jigsaw. */
    COMMAND_PLACE("command_place", Bucket.COMMAND),
    STRUCTURE_BLOCK("structure_block", Bucket.COMMAND);

    /** High-level grouping for coarse filtering by consumer mods. */
    public enum Bucket {
        UNKNOWN, WORLDGEN, PLAYER, MOB, EXPLOSION, MECHANICAL,
        FLUID, TICK, PROJECTILE, LIGHTNING, COMMAND
    }

    private static final String NAMESPACE = "blockorigin";
    private static final Map<Identifier, BlockCause> BY_ID = new HashMap<>();

    static {
        for (BlockCause c : values()) BY_ID.put(c.id, c);
    }

    private final Identifier id;
    private final Bucket bucket;

    BlockCause(String path, Bucket bucket) {
        this.id = Identifier.of(NAMESPACE, path);
        this.bucket = bucket;
    }

    public Identifier id() { return id; }
    public Bucket bucket() { return bucket; }

    /** Resolve a stable id back to a value. Returns {@code null} for unknown ids. */
    @Nullable
    public static BlockCause fromId(Identifier id) {
        return BY_ID.get(id);
    }
}
