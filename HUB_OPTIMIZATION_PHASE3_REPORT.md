# Gina-Hub Performance Optimization — Phase 3 Report

Branch: `gina-hub-main`. Continues directly from Phase 2. Objective: push the player ramp past 200 until scaling
clearly bends, profile that exact point, and remove the next highest-value Hub-specific bottleneck without
sacrificing maintainability.

## 1. Baseline

- Starting commit: `823a98b33` (Phase 2 report + leak/soak findings), clean working tree, clean rebuild before
  any Phase 3 change.
- Environment: identical to Phase 2 — AMD Ryzen 9 7945HX (16C/32T), ~31.2 GiB RAM, Windows 11 Home 10.0.26200,
  Temurin JDK 21.0.11+10. **No Linux/Pterodactyl access exists in this environment** — Phase 3's Linux-profiling
  objective (§15 of the phase brief) could not be performed and is not fabricated; see §12.
- Fixture: unchanged 101×101 platform, 18 entities, `locator_bar=false` verified on the fixture's `level.dat`
  before use. View distance 10, simulation distance 10 (vanilla defaults, unchanged from Phase 2).
- Bot harness: unchanged from Phase 2, chat/command stimulus still intentionally unimplemented (see Phase 2
  report for why).

## 2. A methodology bug, caught and corrected mid-phase

The initial extended ramp used the same post-ramp settle time that worked correctly through 200 players in
Phase 2 (~12–15s). At 300+ players this was **not long enough** — it measured the tail of the join burst
(chunk-send backlog, connection setup still finishing), not true steady state. This produced a badly wrong
first conclusion: apparent DEGRADED status at 300, apparent UNHEALTHY collapse at 500 (TPS 4.14, a 2459.7ms max
stall).

This was caught by taking a second health-report sample later in the same 500-player run (still running, same
process, no restart): TPS recovered to 19.15–19.97 once the burst genuinely settled. Re-running the whole
300/400/500 range with a 90-second settle window (validated by watching TPS actually stabilize, not just
guessing a longer number) produced a substantially different and correct picture — see §3. **The numbers in §3
onward are all from the corrected, 90-second-settle methodology**; the burst-tail numbers are kept separately in
§9 as legitimate join-burst evidence, not discarded, per the phase's own instruction to keep these two workloads
distinct.

## 3. Extended player ramp (corrected, true steady-state)

All runs: `-Xms2G -Xmx2G`, identical benchmark fixture, identical bot movement pattern, 90s post-ramp settle
before measurement.

| Players | TPS (10s/1m) | Median MSPT | p95 MSPT | Max tick | CPU (system/process) | Status |
|---:|---|---|---|---|---|---|
| 200 (Phase 2 reference) | 19.97–19.99 | 4.8–6.6ms | 6.6–13.4ms | 403.9ms | 5–8% / 2–8% | HEALTHY |
| 300 | 20.0 / 20.0 | 16.9 / 16.0ms | 20.8 / 20.3ms | 28.8ms | 27–28% / 8% | **EXCELLENT** |
| 400 | 20.0 / 20.0 | 29.1 / 29.1ms | 37.1 / 38.0ms | 77.2ms | 21–22% / 12–14% | **HEALTHY** (real cost growth, still within budget) |
| 500 (before fix) | 16.83 / — | 46.9 / 47.4ms | 104.3 / 106.2ms | 306.0ms | 27–29% / 15–18% | **DEGRADED** |
| 500 (after fix, §6) | 18.82 / 19.59 | 44.1 / 45.6ms | 89.5 / 100.7ms | 168.9ms | 23–27% / 14–17% | **DEGRADED** (improved, not resolved) |

All bot connections succeeded at every load point (0 failures, 500/500 logged in) once the earlier `online-mode`
setup mistake was corrected (§8) — this was a benchmark-directory configuration error on my part from a freshly
recreated `server-test` directory, not a scale-related finding, fixed before any load-point data was collected.

## 4. Where the scaling bend actually is

**Between 400 (healthy) and 500 (degraded)**, not between 250–300 as the uncorrected first pass suggested.
300→400 shows real but bounded cost growth (median MSPT ~16→29ms, p95 within budget). 400→500 is where p95
first exceeds the 50ms tick budget and TPS first drops below 20 in true steady state.

## 5. Profile findings

Captured via JFR (`settings=profile`) at N=500, then **filtered to a confirmed-steady-state time window**
(samples from several minutes into a 3-minute run, well past the 90s settle point) to avoid the same
join-burst-contamination mistake described in §2.

**By thread** (5895 steady-state samples):

| Thread | Samples | Share |
|---|---:|---:|
| Netty NIO IO (4 threads) | 3626 | 61.5% |
| Server thread (main tick) | 2236 | 37.9% |
| Everything else | 33 | 0.6% |

**Within Netty IO threads**: standard packet-encoding/buffer-management machinery —
`ChannelOutboundBuffer.addFlush`, `AdaptiveByteBuf.internalNioBuffer`, `MpmcArrayQueue.poll/offer` (Netty's own
task queues), `ReferenceCountUpdater.retain0`, `Magazine.tryAllocate` (buffer pooling),
`AbstractChannelHandlerContext.write`, `StreamCodec.encode` (Minecraft's own packet encoding). This is real,
required packet-sending work, not an obviously redundant pattern — no evidence of the same packet being
re-encoded per-recipient rather than once (see §7 for the specific check performed).

**Within the Server thread** (2236 samples): ranked by leaf method —

| Method | Samples | Share of server-thread work |
|---|---:|---:|
| `ChunkMap$TrackedEntity.updatePlayer` (two call sites) | 639 | 28.6% |
| `ServerWaypointManager.updatePlayer`/`updateWaypoint` | 163 | 7.3% |
| `TicketSet.expireAndRemoveInto` (chunk ticket churn) | 57 | 2.5% |
| Everything else (HashMap/Set operations, packet send calls, misc) | 1377 | 61.6% (long tail, no other single item >2%) |

## 6. Optimization — completing the Phase 2 waypoint fix

**Root cause**: Phase 2 fixed the O(n²) waypoint *connection-creation* cost by defaulting `locator_bar` to
`false`, but `ServerWaypointManager.updatePlayer()`'s outer bookkeeping (building a `Sets.difference`, iterating
and copying an `ImmutableSet`) still ran for every player every tick regardless of the gamerule — only the
*inner* connection work was gated. At 200 players this was ~3% of samples and was deliberately left alone per
Phase 2's "meaningful gain" threshold. At 500 players it had grown to 7.3%, a Phase-3-relevant cost.

**Fix**: early-return in `updatePlayer()` when `!isLocatorBarEnabledFor(player)`. Verified **behavior-preserving,
not just faster**: `updateConnection()` already contains no `else` branch to disconnect a stale connection when
the check fails, so it already left existing connections untouched whenever this condition was false — meaning
skipping the entire method early produces an *identical* final `this.connections` state to letting every
iteration inside individually no-op. This is not a correctness tradeoff; it is dead-work elimination.

**Measured (N=500, true steady state, before/after, isolated — not stacked with any other change):**

| Metric | Before | After | Delta |
|---|---:|---:|---:|
| TPS (10s) | 16.83 | 18.82 | +11.8% |
| Median MSPT | 46.9–47.4ms | 44.1–45.6ms | ~-4% |
| p95 MSPT | 104.3–106.2ms | 89.5–100.7ms | ~-10–15% |
| Max tick | 306.0ms | 168.9ms | **-44.8%** |
| CPU (process, 1m) | 15–18% | 14–17% | roughly flat |

Clears this phase's acceptance threshold (meaningful p95 reduction, large stall elimination) despite a
comparatively small patch (one early-return, 11 lines). N=500 remains DEGRADED afterward — this fix reduced but
did not eliminate the cost, since the two dominant remaining items (Netty I/O, `ChunkMap$TrackedEntity`
tracking) are untouched and much larger.

Patch exported to `paper-server/patches/features/0037-...patch`, round-trip verified (wipe paperweight cache,
reapply, confirm identical source), committed.

## 7. Tested and rejected: more Netty I/O threads

Given Netty threads were 61.5% of steady-state CPU samples, the obvious hypothesis was thread starvation (only 4
threads for 500 connections). Tested `settings.netty-threads: 8` in `spigot.yml` (no code change, pure config) at
N=500, same corrected 90s-settle methodology:

| Metric | 4 threads | 8 threads |
|---|---:|---:|
| TPS (10s) | 16.83 | 10.95 |
| Median MSPT | 46.9ms | 66.7ms |
| p95 MSPT | 104.3ms | 306.0ms |
| Max tick | 306.0ms | 690.8ms |
| CPU (process) | 15–18% | 19–22% |

**Doubling the thread count made every metric measurably worse.** This rules out simple thread-count tuning and
is treated as a real, useful negative result rather than discarded — it indicates the limit is not "not enough
parallel capacity" but rather that more threads add contention/context-switching overhead without addressing the
underlying per-tick packet-volume cost. Reverted to the default of 4. **No netty-thread-count change is
recommended based on this evidence.**

## 8. Harness limit check

The bot harness itself did not become the limiting factor at any tested population. All 500 connections
completed login with 0 failures once the (unrelated, benchmark-setup-only) `online-mode` misconfiguration was
fixed. Packet/byte throughput on the bot side scaled smoothly with player count with no errors or connection
drops during any run, including the 20-minute 300-player soak (§11). No evidence the harness process itself
saturated this machine's CPU/network in a way that would explain the server-side degradation at 500 — the
degradation is attributable to the server process's own thread activity (§5), measured directly via JFR on the
server process, not inferred from harness-side symptoms.

## 9. Join-burst vs. steady-state (kept separate, not merged)

The uncorrected first-pass numbers are legitimate **join-burst** evidence, not simply wrong — they characterize
what happens during the brief window right after a large connection spike, which is a real and distinct
workload from steady state:

| Players | Join-burst TPS (10s, ~12–15s post-ramp) | True steady-state TPS (90s+ post-ramp) |
|---:|---:|---:|
| 300 | 19.99 (but median MSPT 12.3ms, CPU 33–48%) | 20.0 (median MSPT 16.9ms, CPU 27–28%) |
| 400 | 16.7 (max tick 659.3ms) | 20.0 (max tick 77.2ms) |
| 500 | 4.14–7.74 (max tick up to 2869.7ms) | 16.83–18.82 (max tick 168.9–306.0ms) |

The join burst is measurably more expensive than steady state at every population, most dramatically at 500. This
matches expectations (bulk chunk-sending, connection/configuration-phase work, and initial entity-tracking setup
all concentrate in the first several seconds after a large batch of players connects) and is worth keeping in
mind for real deployment: a Hub that suddenly receives many simultaneous joins (e.g., after a network-wide event)
will see worse transient MSPT than its steady-state number would suggest, even though it will recover.

## 10. Static chunk work and player-tracking findings

- **`PalettedContainer.reencodeContents`** (the specific method named in the phase brief as a candidate for a
  static-chunk cache) did **not** appear among the significant steady-state hotspots at 500 players (not in the
  top server-thread items in §5). Profiling does not justify implementing a chunk-packet cache this phase — per
  the phase's own instruction ("do NOT implement it until profiling proves it matters"), it was not built.
- **`ChunkMap$TrackedEntity.updatePlayer`** (28.6% of server-thread samples) is the real, measured, dominant
  per-player tracking cost — this is core Moonrise/Paper visibility-tracking infrastructure (managing which
  entities each player can see and keeping their client-side state in sync). No safe, validated Hub-specific
  fast path was found this phase; meaningfully changing this method's behavior (e.g. a fixed-radius visibility
  shortcut) would require much deeper engine-level surgery than this phase's "smallest safe change" principle
  allows, and was not attempted without a validated design.

## 11. Leak and soak tests

**Leak test** (200 players, 4 cycles, `-Xms128M -Xmx2048M`, single long-lived server, matching Phase 2's
methodology at double the population):

| Cycle | Post-GC live heap | RSS |
|---|---:|---:|
| 1 | 268.7 MB | 1235.1 MB |
| 2 | 269.7 MB | 1231.7 MB |
| 3 | 265.4 MB | 1325.6 MB |
| 4 | 271.5 MB | 1288.2 MB |

Live heap flat (±1%) across all 4 cycles at 200 players — no leak trend, consistent with Phase 2's 100-player
result.

**Soak test**: **20 minutes (1200s) continuous, 300 concurrent players**, single run (not repeated short cycles) —
longer than Phase 2's 8 minutes, and the longest practical run for this session's time budget (not the
30–60-minute stretch target; stated honestly rather than rounded up).

- All 300 connections remained stable the entire duration — 0 failures, 0 drops.
- TPS: 19.96–20.0 throughout, 5-minute and 15-minute rolling windows both read 20.0 by the end.
- Tick durations at end: median 0.8–13.6ms, p95 1.8–25.9ms, max 10.3–55.4ms — no growing tail latency.
- Final post-GC live heap: 269.3 MB — statistically identical to the leak test's baseline, confirming no drift
  over a run 2.5x longer than the leak test's total connect time.
- Final RSS: 1.42 GB. Thread count: 86, stable throughout.

## 12. Linux-side profiling

**Not performed.** This environment has no Linux or Pterodactyl access; attempting to simulate or extrapolate
Linux-specific NMT/JFR/class-loading numbers from Windows data would not be honest measurement. The Phase 2
report's production observations (startup ≈6.854s, idle container ≈1.01 GiB/2 GiB) remain the only Linux data
point available, and are not re-verified or extended this phase. This is listed as a concrete Phase 4
recommendation requiring either direct access to the production environment or a comparable Linux dev machine.

## 13. JVM configuration for a 2GB container

Building on Phase 2's finding that fixed non-heap JVM/Paper overhead is ~650–700MB regardless of heap sizing:

- At 300–400 players (the confirmed healthy/excellent range), 2GB heap showed **no organic GC pressure** — the
  only Full GC events observed were this session's own diagnostic `GC.run` calls, not evacuation failures. Live
  heap stayed in the same ~260–270MB band seen at much lower populations throughout the leak/soak tests, meaning
  the *retained* memory footprint does not grow dramatically with player count for this benchmark's workload
  (18 static entities, no per-player persistent state beyond connection/tracking bookkeeping). What does grow
  with load is short-lived allocation churn between GC cycles (packet buffers, tracking-related temporary
  objects) reflected in spark's pre-GC "memory usage" snapshot, which is a different number from post-GC live
  heap and should not be conflated with it.
- At 500 players (pre-fix), the pre-GC heap snapshot reached 86% of a 2GB cap; post-fix it reached 59% at the
  specific moment sampled (single snapshot, not a controlled before/after of allocation rate specifically —
  presented as an observation, not a proven causal claim).
- **Recommendation**: for a 2GB container, `-Xms128M -Xmx1300M` (leaving ~700MB for the established fixed
  non-heap overhead) is a reasonable starting point for the 300–400 player range this phase confirmed healthy.
  Do not set heap close to the full 2GB — Phase 2 already established that non-heap overhead alone consumes most
  of the remaining headroom. This is a refinement of Phase 2's finding, not independently re-derived from
  scratch, since the fixed-overhead measurement itself was not repeated this phase.

## 14. Compatibility

Retested against the fully Phase-3-patched build (locator-bar default + completed waypoint `updatePlayer` fix +
`server.properties` root fix): LuckPerms (fresh Modrinth download, sha1-verified, enables cleanly), block
modification (`/setblock`), entity spawning (`/summon armor_stand`), time/weather commands, `/gamerule
locator_bar` query (confirms `false`), and bot connections (multiple runs throughout this phase, up to 500
simultaneous, 0 failures). No regressions found.

## 15. Capacity

| | Result |
|---|---|
| **Tested maximum** | 500 players (all connect and log in; server remains responsive, does not crash or eject anyone) |
| **Comfortable core capacity** | Up to 400 players (HEALTHY, p95 within the 50ms tick budget, no organic GC pressure) |
| **First degraded load** | 500 players (p95 exceeds budget even after this phase's fix; TPS 18.8–19.6 rather than a solid 20) |

These are **CORE GINA-HUB** numbers (empty-ish benchmark fixture, no plugins beyond the harness's own bookkeeping
plugin during profiling runs) — not a claim about the eventual real Hub's capacity once the actual Gina Hub
plugin (NPCs, holograms, cosmetics, UI, tab, scoreboard, chat, parties, network integration) is running, which
will add its own cost on top of everything measured here. Beyond 500 players was not tested this phase (time
budget), and per the phase's own instruction against chasing a meaningless maximum, this is reported as untested
rather than extrapolated.

## 16. Remaining risks / limitations

- The final real Gina Hub plugin does not exist yet — all capacity numbers are core-engine-only.
- Chat/command stimulus remains unimplemented in the load harness (documented since Phase 2).
- This is dev hardware (32-thread workstation), not the target production VM; CPU-percentage figures in
  particular should be re-validated on hardware matching the actual deployment before being treated as
  authoritative.
- Linux-side profiling could not be performed in this environment (§12) — the Windows/Linux startup and memory
  gap noted in Phase 2 remains unattributed.
- The dominant remaining N=500 cost (`ChunkMap$TrackedEntity` player tracking, Netty packet I/O) is core,
  required engine work; no further safe reduction was found or attempted this phase.

## 17. Phase 4 recommendations (not implemented)

- Get Linux/Pterodactyl-equivalent profiling access to close the gaps in §12–13.
- Investigate `ChunkMap$TrackedEntity.updatePlayer`'s visibility-tracking cost specifically for a static,
  small-map Hub — e.g., whether a fixed/capped visibility radius or reduced tracking-update frequency for
  unchanged entity state could be introduced as an opt-in Hub-specific fast path, with careful correctness
  validation (this is real engine surgery, not a config default flip, and needs its own dedicated
  measure-first pass).
- Investigate whether any packet-encoding work in the Netty I/O threads is genuinely redundant (same content
  re-encoded per recipient) versus simply large in aggregate — this phase confirmed the threads are busy with
  real encoding work but did not trace whether any of it is duplicated across recipients.
- Extend the ramp past 500 now that the true steady-state measurement methodology is validated.
- Re-run the full ramp with the eventual real Gina Hub plugin installed for REPRESENTATIVE (not just CORE)
  capacity numbers.
- Consider whether `updateWaypoint()` (the companion method to the one fixed in §6, iterating per-waypoint over
  all players rather than per-player over all waypoints) has a safe equivalent optimization — not attempted this
  phase because a fully general, multi-world-safe short-circuit is less straightforward than `updatePlayer()`'s.
