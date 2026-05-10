# Block Origin

A Fabric library mod for Minecraft 1.21.11 that **tags every block (including air) with the cause of its last change** — worldgen, player action, mob action, explosion, piston, fluid spread, random tick, command, etc. — so consumer mods can distinguish e.g. a stem-grown pumpkin from a player-placed one, or a creeper hole from a player-dug tunnel.

The mod is built primarily as a library: stable Java API, allocation-free hot path, on-disk persistence via Fabric's Data Attachment API, and an Identifier-keyed save format that survives enum reordering.

## Requirements

- Minecraft 1.21.11
- Fabric Loader >= 0.19.2
- Fabric API
- Java 21
- Optional: [Jade](https://modrinth.com/mod/jade) for `Origin: ...` hover tooltips

## Install

Download the jar from [Releases](https://github.com/JackOtsig/blockorigin/releases) and drop it into your `mods/` folder alongside Fabric API.

## Public API

```java
import origin.jack.blockorigin.api.BlockCause;
import origin.jack.blockorigin.api.BlockOrigin;

// Read — O(1), allocation-free, never null
BlockCause cause = BlockOrigin.get(world, pos);
if (cause == BlockCause.RANDOM_TICK_GROW) {
    // grown by a stem / crop tick, not placed by a player
}

// Write — attribute a block change to a cause via try-with-resources
try (BlockOrigin.CauseFrame f = BlockOrigin.pushCause(BlockCause.PLAYER_PLACE)) {
    world.setBlockState(pos, state);  // automatically stamped with PLAYER_PLACE
}

// Or, equivalent lambda form
BlockOrigin.withCause(BlockCause.MOB_HARVEST, () -> {
    world.setBlockState(pos, Blocks.AIR.getDefaultState());
});

// Peek at the current top-of-stack cause
BlockCause current = BlockOrigin.currentCause();
```

Unstamped positions read as `BlockCause.UNKNOWN` — the mod doesn't claim worldgen for blocks it has no evidence about. Specific worldgen sub-causes (`WORLDGEN_TERRAIN`, etc.) are only assigned after a positive match against shadow worldgen during the backfill scan (see below).

## In-game

### First-load prompt

The first time a player joins a world that has no recorded decision, they get a one-time chat prompt asking whether to run a backfill scan. The decision is persisted per-world and the prompt won't fire again.

### Commands

All commands require permission level 2 (op / cheats):

| Command | What it does |
|---|---|
| `/blockorigin backfill` | Scans every saved chunk in the dimension, stamps matches as `WORLDGEN_TERRAIN`, leaves mismatches as `UNKNOWN`. Throttled at 4 chunks/server-tick with progress every 10%. Persisted decision and stamps survive restart. |
| `/blockorigin backfill disable` | Opt out of backfill for this world; the prompt won't show again. |
| `/blockorigin backfill status` | Show recorded decision and progress of any running task. |
| `/blockorigin backfill cancel` | Abort an in-progress backfill. |
| `/blockorigin backfill scan [radius]` | Synchronous in-memory scan of currently-loaded chunks around the executor. Useful for spot-checks. |
| `/blockorigin shadow <pos>` | Debug: compare the live block at `pos` against shadow worldgen, print per-position and whole-column match stats plus dim, biome, generator type. |

### Jade hover

If Jade is installed, every block tooltip gets a `Origin: <Readable Cause Name>` line. Toggle in Jade's config under `blockorigin:cause_tooltip`.

## How it works

- Cause storage is a per-chunk Fabric data attachment. Each chunk holds a `CauseChunkData` with a sparse `CauseSection[]`; sections use a uniform-or-dense scheme (one byte until a position diverges, then promoted to `byte[4096]`, with compaction back to uniform).
- Bytes hold `BlockCause.ordinal()` for fast access; the on-disk codec maps ordinals through a per-section palette of stable `Identifier`s so the enum can be reordered or pruned without corrupting saves.
- A thread-local `CauseStack` carries the active cause during a block change. `WorldChunkMixin` injects on `setBlockState` and stamps the resulting block.
- Backfill uses `ChunkGenerator.getColumnSample` (the live world's, with its `NoiseConfig` from an accessor mixin) to produce a deterministic shadow column post-noise + surface, then diffs against the saved chunk.

## Caveats

- **Shadow worldgen runs only the noise + surface stages.** No carvers, features, or structures. On default-overworld worlds this means caves (live air, shadow stone) and trees/ores (live block, shadow air) mismatch and tag as `UNKNOWN` even though they really are worldgen. Flat, void, and single-biome presets work cleanly. Carver and feature attribution is the next major pass.
- **Most causes aren't wired yet.** Only `PLAYER_BREAK`, `RANDOM_TICK_GROW` (stem-driven), and `WORLDGEN_TERRAIN` (via backfill) are currently set. The enum defines ~50 values; the rest need event/mixin wiring as the mod matures.
- **Version drift not detected yet.** Backfill doesn't currently check whether the world was last saved under the running MC version. Mismatched DataVersion can produce false "modified" stamps.

## Building from source

```sh
./gradlew build
# build/libs/blockorigin-<version>.jar
```

## License

[MIT](LICENSE)
