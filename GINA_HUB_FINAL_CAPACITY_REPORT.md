# Gina-Hub Final Proof Benchmark — Real Hub Load, Hard RAM Caps, Verified Max Capacity

## Purpose

Answer, with measured evidence: how many real Hub-style concurrent players can Gina-Hub
safely and consistently support under 1 GB and 2 GB total server memory while running
representative Hub systems (TAB, scoreboard, action bar, cosmetics, NPCs, holograms,
LuckPerms)?

## Environment

- Java: OpenJDK 21.0.11+10 (Eclipse Adoptium Temurin)
- OS: Windows 11 Home 10.0.26200
- CPU: AMD Ryzen 9 7945HX, 16 cores / 32 logical processors
- Gina commit: `ghub` branch, Phase 4 tip (waypoint fix + tracking-range=48 active)
- view-distance / simulation-distance: 10 / 10

### Memory-limit enforcement — important caveat

**No true hard container/cgroup memory limit was enforced.** This is a Windows
development machine with no Docker/container runtime available. A custom Windows Job
Object wrapper (which can enforce a genuine whole-process memory cap, closer in spirit
to a cgroup limit than a JVM flag) was considered but not implemented given the
session's explicit tight time budget. **What was actually tested: a tightly-capped JVM
heap (`-Xmx`) combined with measuring total process RSS to confirm it stays under the
target total.** This is NOT the same guarantee as an OS-enforced hard container limit —
if something drives native/off-heap memory far outside what was observed here, a real
container would kill the process where this test could not. Treat the numbers below as
a strong, measured indicator, not a certified hard-limit guarantee.

## Realistic Hub fixture (`benchmark/final-hub-fixture/`)

Kept separate from the core and Phase 4 heavy fixtures. Same 101x101 platform geometry.
Static content (reused from the Phase 4 heavy fixture, matching the requested counts):
- 25 NPC-proxy armor stands (NoAI, invulnerable, silent, named)
- 20 hologram text_displays
- 30 decorative item_displays

### Representative Hub plugin (`GinaHubBenchmarkPlugin`, benchmark-only)

Built specifically for this phase to model the eventual real Gina Hub plugin's
continuous background cost:
- **TAB header/footer**: updated every 40 ticks (2s) for all online players
- **Sidebar scoreboard**: shared-scoreboard sidebar (network name, online count, rank,
  coins, lobby ID, site URL placeholder fields), rebuilt every 40 ticks (2s)
- **Action bar**: one lightweight persistent message every 100 ticks (5s)
- **Cosmetics**: ~25% of players (deterministic by UUID hash) get a small particle
  effect (`HAPPY_VILLAGER`, 3 particles) every 40 ticks (2s)
- Update intervals were deliberately chosen to be realistic (not per-tick, not
  intentionally worst-case) per the phase's own instruction not to write deliberately
  bad code to inflate the measured cost

**Excluded workloads** (documented, not implemented, per explicit time-budget
permission to skip):
- Chat traffic — harness doesn't support signed chat packets (documented limitation
  carried over from Phase 2/3)
- Commands — same signed-packet limitation
- Menu/inventory GUI interaction — bot harness has no inventory-interaction support
- Parkour-specific movement — existing continuous random-walk movement used as-is

### Third-party plugin

**LuckPerms v5.5.71** (current release, fetched from Modrinth, sha1-verified,
lists native `1.21.11` support)

## JVM configuration

Per-tier JVM args (not `MaxRAMPercentage`, per the phase's explicit instruction to use
a predictable fixed heap cap instead):

- **1 GB tier**: `-Xms128M -Xmx450M`
- **2 GB tier**: see below (chosen after observing 1GB-tier native/metaspace overhead)

Idle baseline observed (both plugins + final fixture loaded, before any bots): heap
used ~255MB, metaspace ~156MB committed, **total process RSS ~1.4GB even with
`-Xmx1G`** — confirming that JVM/metaspace/native overhead alone is substantial before
any player load, which is why the 1GB-tier heap cap has to be set well below 1024MB to
leave room for that fixed overhead within an actual 1GB total budget.

## 1 GB total tier — bracket search results

| N   | TPS (1m)      | median MSPT | p95 tick | max tick | heap used/cap | bots connected |
|-----|---------------|-------------|----------|----------|----------------|------------------|
| 50  | 20.0 (flat)   | 2.3ms       | 3.3ms    | 12.3ms   | 407/450 MB (90%) | 50/50 |
| 100 | 20.0 (flat)   | 3.9ms       | 6.0ms    | 11.2ms   | 421/450 MB (93%) | 100/100 |
| 125 | 19.22-20.0 (unstable) | 4.7-4.8ms | 96.5-96.7ms | **585.1ms** | 450/450 MB (99%) | 125/125, 0 failed |
| 150 | N/A — **crashed** | N/A | N/A | N/A | OOM | 135/150, 2 failed, server crashed with `OutOfMemoryError: Java heap space` across Server thread, Netty IO threads, and other core threads |

**1 GB tier: Tested maximum 125 (completed but severely degraded) / Production-safe
maximum 100 / Stress limit 125 (585ms max-tick spike, 99% heap, first real
degradation).**

## 1 GB tier — final proof (100 players, ~12.7 minutes)

4 checkpoints at t=0, 180, 360, 540s into the steady-state window, plus a final reading
at t≈751s:

| checkpoint | TPS (10s/1m) | median MSPT | p95 tick | max tick | heap used | RSS |
|---|---|---|---|---|---|---|
| t=0s | 20.0/20.0 | 3.5-3.9ms | 5.2-6.9ms | 12.2-107.9ms | 434.7MB | 907.8MB |
| t=180s | 19.98/20.0 | 3.5-3.6ms | 5.2-5.5ms | 6.8-7.8ms | 437.5MB | 921.7MB |
| t=360s | 20.0/20.0 | 3.5ms | 5.2-5.6ms | 6.6-8.3ms | 432.1MB | 927.1MB |
| t=540s | 20.0/20.0 | 3.5ms | 5.2-5.6ms | 6.6-8.3ms | 435.4MB | 929.6MB |
| t≈751s (end) | 20.0/20.0 | 3.5-3.8ms | 8.9-10.5ms | 14.7-20.8ms | ~434MB (GINABENCH) | n/a |

TPS held 19.98-20.0 for the entire ~12.7 minutes. Heap oscillated in a stable 410-437MB
band around the 450MB cap (90-95%) with no upward trend — no leak. 100/100 bots stayed
connected the whole time, 0 failures. No Full GC warnings appeared in the log. RSS grew
slightly (908MB -> 930MB) over the window but plateaued, consistent with normal
metaspace/JIT warmup settling rather than a leak.

**Verified 1 GB production benchmark: 100 concurrent representative Hub players, 20.0
TPS sustained for ~12.7 minutes, heap stable at 90-95% of a 450MB cap, ~930MB peak
total process RSS (JVM-heap-capped test, not an OS-enforced hard container limit — see
caveat above).**

## 2 GB total tier

JVM args: `-Xms128M -Xmx1100M` (spec's suggested 2GB starting point; empirically 1.1GB
heap cap kept total RSS comfortably under a 2GB budget through the healthy range).

### Bracket search results

| N   | TPS (1m)      | median MSPT | p95 tick | max tick | heap used/cap | bots connected |
|-----|---------------|-------------|----------|----------|----------------|------------------|
| 200 | *20.0 (flat)  | 8.1ms       | 11.0ms   | 34.3ms   | 608.5MB/1.1GB (55%) | 200/200 |
| 300 | 20.0 (flat)   | 14.0ms      | 20.9ms   | 28.7ms   | 793.4MB/1.1GB (72%) | 300/300 |
| 400 | 19.75-19.98   | 26.8ms      | 36.6ms   | **675.8ms** (single spike) | 922.4MB/1.1GB (83%) | 400/400, 0 failed |
| 450 | 19.21-20.0 (below 19.5 threshold) | 35.5ms | 66.2ms | **944.8ms** | 898.8MB/1.1GB (81%) | 450/450, 0 failed |
| 500 | 15.54-18.2 (**failed health criteria**) | 43.8ms | 118.9-119.4ms | **1886.6ms** | 1018.7MB/1.1GB (92%) | 500 connected, **201 failed** |

**2 GB tier: Tested maximum 500 (ran to completion but severely degraded — 201/500 bot
connection failures, TPS as low as 15.5, 1.9s max-tick stall) / Production-safe maximum
300 (all health criteria cleanly met) / Stress limit 400 (first degradation signal: a
single 675.8ms stall, otherwise healthy — 450 confirms the real wall with TPS dropping
below the 19.5 threshold and repeated near-second-long stalls).**

### 2 GB tier — final proof (300 players, ~9.75 minutes)

3 checkpoints at t=0, 180, 360s into the steady-state window:

| checkpoint | TPS (10s/1m) | median MSPT | p95 tick | max tick | heap used | RSS |
|---|---|---|---|---|---|---|
| t=0s | 20.0/20.0 | 14.2/17.5ms | 21.3/32.3ms | 24.9/142.8ms | 746-996.8MB | 1.669GB |
| t=180s | 20.0/20.0 | 14.1/17.4ms | 20.6/31.4ms | 24.9/109.7ms | 792-1023.9MB | 1.680GB |
| t=360s | 19.98/20.0 | 13.6/13.7ms | 19.6/20.0ms | 21.0/28.8ms | 947-787.2MB | 1.687GB |

TPS held 19.96-20.0 for the full ~9.75 minutes. Heap usage oscillated between 71-93% of
the 1.1GB cap across checkpoints (normal G1 GC cycling, not a monotonic climb — no leak
trend). 300/300 bots stayed connected the entire window, 0 failures at every 5-second
status interval sampled. Max tick occasionally spiked to 109-143ms during heavier GC
cycles but never approached the 250ms recurring-stall threshold, and TPS never dipped
below 19.96.

**Verified 2 GB production benchmark: 300 concurrent representative Hub players
(LuckPerms + full TAB/scoreboard/action-bar/cosmetic workload + 75 static Hub entities),
19.96-20.0 TPS sustained for ~9.75 minutes, heap oscillating 71-93% of a 1.1GB cap with
no leak trend, ~1.69GB peak total process RSS (JVM-heap-capped test, not an
OS-enforced hard container limit — see caveat above).**

## Compatibility

- **LuckPerms v5.5.71**: loaded and enabled cleanly, `/lp info` responded correctly
  ("Running LuckPerms v5.5.71 by Luck"), no console errors during load, run, or clean
  shutdown.
- **Bukkit/Paper API smoke test**: plugin list (`Server Plugins (2)`), block
  modification (setblock), entity spawning (`Summoned new Pig`), gamerule query all
  executed with zero errors or exceptions.
- **NPCs/holograms/displays**: confirmed present and stable — the 2GB proof's periodic
  `entities=375` reading at N=300 (300 players + 75 static fixture entities: 25 NPCs +
  20 holograms + 30 displays) matches the fixture's exact composition throughout the
  ~9.75 minute run, with no unexpected entity-count drift, no console spam, and no
  runaway ticking observed.

## Core fixture vs final realistic Hub fixture comparison

| Players | Core fixture (Phase 4, engine-only) | Final realistic Hub fixture (this phase) |
| ------: | -----------------------------------: | -----------------------------------------: |
|     300 | not separately tested at this exact point | 20.0 TPS flat, p95 20.9ms, heap 72% of 1.1GB (2GB tier) |
|     400 | 20.0 TPS flat, max tick 62.4ms (1GB Xmx) | 19.75-19.98 TPS, one 675.8ms stall spike, heap 83% of 1.1GB |
|     500 | 20.0 TPS flat (after Phase 4 fixes), max tick 60-82ms (1GB Xmx) | **fails** — TPS 15.5-18.2, 1.9s max stall, 201/500 connection failures (1.1GB Xmx) |

The gap is stark and expected: the core fixture numbers came from a bare engine test
with a 1GB *uncapped-relative-to-workload* heap and no LuckPerms/TAB/scoreboard/
cosmetics/75 extra entities. Once real Hub systems and a genuinely constrained memory
budget are added, capacity drops substantially — from a healthy 500 down to a
production-safe 300 at the 2GB tier. This is the actual value of this phase: the
Phase 4 numbers describe the engine's ceiling, not what a real, memory-constrained,
plugin-loaded Hub deployment can actually support.

## Limitations

- Benchmark bots are not human players: no genuine human input latency/variance, no
  real client-side rendering load, simplified movement pattern (continuous random walk,
  no real parkour/menu/chat behavior).
- Chat and command traffic excluded — the bot harness doesn't support signed chat/
  command packets (a carried-over limitation from Phase 2/3, not newly introduced).
- The final production Gina Hub plugin may differ meaningfully from
  `GinaHubBenchmarkPlugin` — this is a representative approximation of expected
  continuous background cost, not the real feature set.
- Hardware matters: these numbers are specific to this Ryzen 9 7945HX / 32-thread
  machine; a smaller/shared VPS will have a lower ceiling.
- Network/proxy cost (Velocity/BungeeCord hop, real internet latency/jitter) is outside
  this backend-only measurement.
- **No true hard container/cgroup memory limit was enforced** (see caveat under
  Environment above) — these are JVM-heap-capped results with measured total-process
  RSS, not certified OS-level hard-limit results.
- Given the session's explicit tight time budget, most points used short (3-4 minute)
  screening windows rather than long soaks; only the two identified production-safe
  maxima got a longer (10-15 minute) validation run.

## Final defensible claims

> On an AMD Ryzen 9 7945HX (16 cores/32 threads), with a JVM heap capped at 450MB
> (`-Xms128M -Xmx450M`, total process RSS ~930MB, not an OS-enforced hard 1GB limit),
> Gina-Hub sustained 100 concurrent representative Hub clients (LuckPerms, TAB,
> scoreboard, action bar, cosmetics, 75 static entities) for ~12.7 minutes at a
> sustained 20.0 TPS with a 90-95% heap utilization and no memory leak.
>
> On the same hardware, with a JVM heap capped at 1100MB (`-Xms128M -Xmx1100M`, total
> process RSS ~1.69GB, not an OS-enforced hard 2GB limit), Gina-Hub sustained 300
> concurrent representative Hub clients for ~9.75 minutes at 19.96-20.0 TPS with heap
> utilization oscillating 71-93% and no memory leak.
>
> Beyond these points, capacity drops sharply: 125 players broke the 1GB tier (585ms
> stalls, 99% heap) and 150 crashed it outright with a genuine `OutOfMemoryError`. 400
> players showed the first stress signal on the 2GB tier (a single 675.8ms stall); 450
> and 500 players clearly failed health criteria, with 500 producing 201 failed bot
> connections and TPS as low as 15.5.

Numbers are not rounded upward. The gap between these figures and the Phase 4
"core engine, 1GB Xmx, no real plugins" numbers (500 healthy) reflects the real cost of
LuckPerms + representative Hub systems + a genuinely tight memory budget, not a
regression in the underlying engine work from Phase 4.

## Final output table

| Total RAM | Production-safe players | Tested max | Stress point | TPS at safe max | Peak RSS |
| --------- | ----------------------: | ---------: | ------------: | ---------------: | -------: |
| 1 GB (450MB heap cap) | **100** | 125 (degraded) | 125 | 20.0 (12.7min sustained) | ~930MB |
| 2 GB (1100MB heap cap) | **300** | 500 (degraded, 201 failures) | 400 | 19.96-20.0 (9.75min sustained) | ~1.69GB |

## Final build

Clean rebuild of `dist/Gina-Hub.jar` from the current committed state (no engine code
changed this phase — this was a measurement-only proof phase per its own explicit
instruction not to start new optimization work unless a bug was found; none was).

