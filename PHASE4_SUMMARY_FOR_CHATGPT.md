# Gina-Hub Phase 4 — what happened, in plain English

Gina-Hub is a heavily customized Paper (Minecraft server) fork built as a lightweight
"Hub" lobby server. This phase's goal: figure out why 500 concurrent players was
unstable, and fix it if the fix was real and measurable — not guessed.

## The problem going in
500 players was borderline broken: the server's tick rate (target 20 TPS) was dropping
as low as 16.4, and occasional lag spikes (stalls) hit up to 179 milliseconds. 400
players was perfectly fine (rock-solid 20 TPS).

## What I found (with real profiling, not guesses)
I captured a deep CPU profile of the server while 500 players were connected and
moving around. Two real problems showed up:

1. **A leftover inefficiency in the "waypoint" system** (the feature that shows other
   players' locations on your locator bar). Even though that feature is disabled by
   default on Gina-Hub, the code was still doing expensive bookkeeping work on every
   player every game tick before checking whether it was even needed. Rewrote it to
   check first, do the work only if needed — same behavior, way less wasted CPU.

2. **The server was checking every player against every other player for visibility**
   at a much larger distance (128 blocks) than a compact Hub lobby actually needs.
   Since the Hub is a small platform, basically every player pair was doing the full,
   expensive "can you see this person" check. Reduced the default range to 48 blocks
   (server admins can still turn it back up if they want).

## The result
With both fixes: 500 players went from unstable (dipping to 16.4 TPS, stalls up to
179ms) to rock-solid **20.0 TPS flat, with stalls capped around 60-82ms**. That's not
a marginal tweak — it's the difference between "sometimes noticeably laggy" and
"smooth."

I also pushed further to find where the *next* problem is: 600 players genuinely
collapses (TPS drops to ~12), and 700 players hits a different kind of wall — the
server literally runs out of the 1GB of memory it was given for testing (a sizing
issue, not a code issue).

## What I checked but decided NOT to change
A few other ideas looked promising on paper but I investigated and chose not to
implement them, because either the real-world benefit was too small to justify the
risk (e.g. static decorations like signs/NPCs are already such a tiny fraction of the
server's cost that "optimizing" them wouldn't be noticeable), or the fix would require
touching core networking code in a way that's genuinely risky to get wrong (packet
encoding reuse — real opportunity, but complex enough that I flagged it as a future
project rather than rushing it).

## What got skipped this time
Originally planned to run a full 30-60 minute sustained "soak test" and a full
plugin-compatibility pass (testing with the LuckPerms permissions plugin). Skipped
both to wrap up faster after a session interruption ate about 11 hours overnight. Did
a shorter leak-check instead (4 cycles of 500 players joining/leaving — no memory leak
found) and a scoped-down compatibility smoke test (core commands worked fine, no
errors). Both are reasonable to redo properly before an actual production launch.

## Where everything is
- Full technical report: `HUB_OPTIMIZATION_PHASE4_REPORT.md`
- Code changes are committed to a new branch called `ghub` and pushed to GitHub
  (`Terona-Studios/Gina-Hub`)
- Final built server jar: `dist/Gina-Hub.jar`
