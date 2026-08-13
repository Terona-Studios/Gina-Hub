# Gina-Hub Performance Optimization — Phase 1 Report

Branch: `gina-hub-main`. All numbers below are measured, not estimated, from real boots of the actual built jar
(`-Xms1G -Xmx1G`, JDK 21 Temurin, Windows 11, 32 logical cores dev machine). Where a number is machine-dependent
(thread counts, GC parallelism) that is called out explicitly rather than presented as a Hub-specific result.

## 1. World architecture (delivered and verified)

- Exactly one automatically-managed world: **`Hub-Map`**, created via vanilla's native flat/void generator
  (`level-type=flat`, empty layers, `minecraft:the_void` biome) — no custom `ChunkGenerator`, no new simulation
  work added.
- **No Nether/End** on a clean install. Achieved by flipping two *existing* Paper toggles
  (`GlobalConfiguration.misc.enableNether` → `false`, `bukkit.yml` `settings.allow-end` → `false`) rather than
  writing new dimension-skipping logic — both remain admin-toggleable exactly as before.
- **Deterministic spawn + safety platform**: vanilla's ground-search spawn algorithm was found to break in a true
  void (it selected a position that was never actually loaded). Patched `setInitialSpawn` to detect an empty-layer
  flat world and place spawn directly instead of searching. Verified in-game: bedrock at `(0,64,0)`, air at
  `(0,65,0)` and `(0,63,0)`, void confirmed away from spawn — matches the intended layout exactly.
- **World border**: new `hub.initial-border-size` option in the unified `configs/gina-config.yml`
  (world-defaults section), default 500, applied to freshly-created worlds only and centered on spawn. Verified via
  `/worldborder get` → `500 block(s) wide`. No effect on existing worlds (uses `/worldborder` as normal after that).
- World name, void-generation, and spawn coordinates are controlled by existing `server.properties`
  (`level-name`, `level-type`, `generator-settings`) — deliberately **not** duplicated into `gina-config.yml`.

## 2. Simulation defaults (delivered and verified)

This Minecraft version renamed several boolean gamerules (`doDaylightCycle`→`advance_time`,
`doWeatherCycle`→`advance_weather`, `doMobSpawning`→`spawn_mobs` + subtype rules). Their **defaults** were flipped
off; the gamerule mechanism itself is untouched, so admin/plugin/command changes work exactly as before.

| Gamerule | Old default | New default | Verified via |
|---|---|---|---|
| `advance_time` (daylight cycle) | true | **false** | `/gamerule advance_time` → false |
| `advance_weather` (weather cycle) | true | **false** | `/gamerule advance_weather` → false |
| `random_tick_speed` (crop growth, leaf decay, fire spread, etc.) | 3 | **0** | `/gamerule random_tick_speed` → 0 |
| `spawn_mobs`, `spawn_monsters`, `spawn_patrols`, `spawn_phantoms`, `spawn_wandering_traders`, `spawn_wardens` | true | **false** | `/gamerule spawn_mobs` → false |

No separate "do not remove code" cleanup was needed for fire spread — it is driven by the same random-tick
mechanism, so `random_tick_speed=0` already covers section 16 of the spec without a redundant toggle. Redstone was
not touched (still fully functional; no separate audit performed in this phase — see Remaining Bottlenecks).

## 3. Startup — baseline vs. current

Two distinct "baseline" numbers matter and were kept separate rather than conflated:

| | First-ever boot (includes one-time Paperclip download+patch+reobf) | Warm restart (steady state) |
|---|---|---|
| **Original defaults** (3 worlds) | 14.68s | *(not separately measured — not the shipped config)* |
| **Hub-Map** (this build) | ~11.6–12.7s | **9.579s** |

The ~3s difference between cold and warm Hub-Map boots is a one-time Paperclip bootstrap cost (downloads and
patches vanilla Minecraft into `versions/`), not a per-restart cost — production restarts should track the 9.58s
warm number.

### Measured phase breakdown (warm restart, millisecond-precision logging)

| Phase | Duration | Share |
|---|---|---|
| JVM start → first log line | 0.77s | 8% |
| Bootstrap info → plugin init | 0.43s | 4% |
| Plugin init (0 plugins) | 0.16s | 2% |
| **Plugin-init → "Environment:" (registry/version/datapack bootstrap)** | **4.64s** | **48%** |
| **"Environment:" → recipes loaded** | **1.60s** | **17%** |
| Recipes → advancements | 0.02s | <1% |
| DataConverter init | 0.31s | 3% |
| DataConverter → "Starting minecraft server" | 0.08s | 1% |
| Properties/branding/ping-sample setup | 0.15s | 2% |
| Netty IO → Moonrise worker pool init | 0.65s | 7% |
| Worker pool → keypair + socket bind | 0.22s | 2% |
| Compression/cipher setup | 0.06s | 1% |
| Cipher → "Preparing level Hub-Map" | 0.07s | 1% |
| **World prep (Hub-Map, void)** | **0.26s** | **3%** (was 3.40s / 24% on the 3-world baseline) |
| Spark profiler start + delayed init → Done | 0.15s | 2% |

World prep itself was cut from 3.40s (3 dimensions, ground-search spawn) to 0.26s — a real, Hub-Map-attributable
win. The two largest remaining phases (registry/datapack bootstrap, recipe loading — 65% of total) are **not**
Hub-Map-specific; they are fixed Paper/vanilla bootstrap cost. Per the profiling-before-optimizing instruction for
this phase, these were *not* touched — a JFR execution-sample profile is needed to know whether that time is I/O,
JSON parsing, or registry validation before attempting to cut it, and that is listed under Phase 2 below rather
than guessed at here.

## 4. Memory — NMT-attributed breakdown

Captured via `-XX:NativeMemoryTracking=detail`, idle, zero players, after a forced full GC.

| Category | Committed | Note |
|---|---|---|
| Java Heap | 1024 MB | Only ~220 MB is live data (see GC.heap_info below) — the remaining ~800 MB is committed-but-unused headroom that exists **because `-Xms == -Xmx`**, forcing G1 to commit the entire heap upfront |
| GC bookkeeping (card tables, remembered sets) | 103 MB | Fixed cost of running G1 over a 1 GB reservation, independent of live data |
| Metaspace + Class + Symbol table | 173 MB | Class metadata for 28,581 loaded classes (Paper/NMS/CraftBukkit codebase size) — largely fixed, not Hub-Map-specific |
| Code cache (JIT) | 39 MB | Normal; will grow further under real load |
| Thread stacks | 4 MB committed (78 MB *reserved*, not resident) | Not a real memory cost currently |
| Everything else (Arena/Internal/NMT self-overhead/shared class space) | ~21 MB | Small |
| **Total committed** | **1370 MB** | |

`GC.heap_info` after the same forced GC: heap **used = 220.8 MB**, comfortably under the <400MB idle-heap target.
Process working set (Windows RSS-equivalent) measured 1250–1440MB across runs depending on GC/measurement timing —
consistently *higher* than the ~1GB "production allocation" figure in the engineering targets, entirely from
non-heap JVM overhead, not from Hub-Map's world architecture.

**Top hypothesis (not yet applied):** letting heap grow on demand (e.g. `-Xms256M -Xmx1G` instead of pinning
`Xms=Xmx=1G`) should substantially cut idle committed/resident memory, since live usage is only ~220MB. This needs
validation under an actual player-connection ramp (not just idle) to rule out G1 heap-growth latency spikes before
it becomes the shipped default — flagged for Phase 2, not applied here.

### Thread attribution (78 threads, idle)

| Group | Count | Note |
|---|---|---|
| G1GC threads (`GC Thread#`, `G1 Conc#`, `G1 Service`, `G1 Refine#`, `G1 Main Marker`, `Monitor Deflation`) | 33 (42%) | **Scaled to this dev machine's 32 logical cores** via JVM auto-detection — not representative of a production Hub VM (likely 2–4 vCPUs), will be much lower there by default and is explicitly tunable via `-XX:ParallelGCThreads`/`-XX:ConcGCThreads` |
| spark profiler (Java-engine sampler, since async-profiler isn't supported on Windows) | 7 (9%) | Dev-profiling cost; Linux production would use the native async-profiler engine with fewer threads |
| Moonrise worker pool | 8 (10%) | Intentional, matches configured worker count |
| Netty IO | 1 seen at idle (up to 4 configured) | Scales with actual connections |
| Misc JVM/Paper/Craft essentials | ~29 | VM/compiler/finalizer/watchdog/etc., one each, largely fixed |

## 5. Plugin compatibility (verified)

Tested against the single-void-world, no-Nether/End runtime:

- **LuckPerms v5.5.71** (official Modrinth release, sha1-verified) — loaded, H2 storage initialized, enabled in
  913ms, `/lp info` correctly reports platform/brand/version, zero errors.
- **Custom Bukkit + Paper API test plugin** (self-authored, compiled against `paper-api`) — `Bukkit.getWorlds()`
  correctly returns exactly 1 world; `Bukkit.getWorld("Hub-Map")` resolves; Adventure `Component` API works;
  `world.getWorldBorder().getSize()` returns the configured 500.
- **Display entity (hologram proxy)**: `/summon minecraft:text_display` succeeded, no errors.
- **NPC proxy**: `/summon minecraft:armor_stand` with `NoAI`/custom name succeeded, no errors.
- **PlaceholderAPI**: not tested this phase (not flagged as blocking by the download-approval scope).

No plugin/API regressions observed from the Hub-Map/no-Nether/no-End changes.

## 6. Correctness tests (all passed)

- **Clean install**: `worlds/` contains exactly `Hub-Map`, no `world`/`world_nether`/`world_the_end`. Void
  confirmed via block queries away from spawn. Exactly one bedrock block at the documented coordinates.
- **Restart**: world reloaded without recreation (no "creating new world" log line on second boot), bedrock/world
  data persisted, border persisted at 500, no new dimensions appeared, no migration loop.

## 7. Remaining bottlenecks, ranked by measured impact

1. **Registry/datapack/version bootstrap** (4.64s, 48% of warm startup) — generic Paper cost, not Hub-specific.
   Needs JFR sampling to attribute before touching.
2. **Recipe/datapack loading** (1.60s, 17% of warm startup) — same caveat; spec explicitly says do not remove
   recipes/advancements without measuring the actual savings and compatibility cost first.
3. **Heap over-commit from `Xms=Xmx=1G`** (~800MB committed-but-idle) — largest identified memory lever, unvalidated
   under load.
4. **G1GC thread count auto-scaled to dev-machine core count** — needs re-measurement on a target-spec VM before
   any tuning decision.
5. **Metaspace/class-loading footprint** (173MB, 28,581 classes) — fixed cost of the Paper/NMS/CraftBukkit
   codebase; not something to cut without removing subsystems, which this phase's philosophy explicitly avoids.
6. **Entirely untested**: actual behavior under concurrent players — chunk/packet sharing at 100+ viewers of the
   same chunks, entity tracking cost, networking/compression thresholds, collision cost for static NPCs/displays.
   These require a load-generating client harness that does not exist yet in this environment (no bot/load-test
   tooling was available or built this phase) — flagged here rather than fabricated with synthetic idle-connection
   numbers, per the phase's non-negotiable rule against unverified capacity claims.

## 8. Phase 2 recommendations (not implemented — list only)

- JFR execution-sample profiling of the two dominant startup phases to find the actual root cause (I/O vs. JSON
  parsing vs. registry validation) before attempting to cut them.
- `-Xms` vs `-Xmx` experiment (e.g. 256–512MB start vs. current 1GB pin), measured idle **and** under a
  player-connection ramp, before changing the shipped default.
- Explicit `-XX:ParallelGCThreads`/`-XX:ConcGCThreads` tuning, re-validated on a machine matching the actual target
  Hub VM spec (2–4 vCPU), not this 32-core dev box.
- Build (or adopt) a lightweight Minecraft protocol bot/load-test harness — required before any player-count
  capacity claim (10/25/50/100/150/200), and before profiling chunk-packet-sharing, entity tracking, or networking
  under concurrent load (spec sections 18, 19, 28–31, 42–44).
- Static NPC / entity-AI efficient pathing (sections 12–14) — deferred; Gina's own NPC system doesn't exist yet, so
  this needs to be revisited once that architecture exists.
- Async-profiler-based CPU flame graphs aren't available on this Windows dev machine (spark falls back to its
  built-in Java sampling engine); a Linux profiling pass is recommended for Phase 2 CPU work.
- Autosave interval tuning, player-data write reduction, and compression-threshold tuning (sections 29, 32, 33) —
  not started; all require the load-test harness above to measure honestly rather than guess.

## 9. Non-negotiable-rule compliance

No player-count support claims are made in this report beyond "boots correctly with 0 players and passes
plugin/correctness smoke tests." No `X% faster` claims are made without the paired before/after measurement shown
above. Where infrastructure was missing (load testing, CPU flame graphs), that is stated explicitly rather than
worked around with a fabricated number.
