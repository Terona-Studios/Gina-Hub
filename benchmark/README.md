# Gina-Hub Phase 2 benchmark harness (development-only)

Everything in this directory is dev/benchmark tooling. **None of it ships in the
production jar** — `benchmark/loadbot` is a standalone client program run separately
against a running Gina-Hub server, not a plugin or server-side dependency.

## Layout

- `loadbot/` — `GinaLoadBot.java`, a minimal Minecraft-protocol load-test client
  (see its own header comment for exactly what it does and does not implement).
- `scripts/setup-benchmark-world.sh` — deterministically builds the benchmark Hub-Map
  content (platform + NPCs + holograms + decorations) against a fresh clean-install
  server via console commands.
- `server-test/` (gitignored, generated) — scratch server directory used for running
  benchmarks locally.
- `hub-map-fixture/` (gitignored, generated) — a saved copy of the world produced by
  the setup script, so repeated benchmark runs start from identical content without
  re-running the setup script each time.
- `results/` (gitignored) — raw benchmark output dumps.

## Benchmark Hub-Map content (exact inventory)

Produced by `scripts/setup-benchmark-world.sh` against a clean Hub-Map:

- **Platform**: 101×101 `smooth_stone` floor at y=64 (x/z from -50 to 50), with a
  `bedrock` layer at y=63 underneath. This replaces/extends the single-bedrock-block
  clean-install spawn platform so bots have somewhere to walk.
- **6 static NPC-proxy entities**: `armor_stand`, `NoAI:1b Invulnerable:1b Silent:1b`,
  named "NPC", arranged in a ring around spawn at (±10,65,0), (0,65,±10), (±7,65,±7).
- **4 hologram entities**: `text_display` reading "Hub Sign", billboard mode `center`,
  at (±10,66.5,0) and (0,66.5,±10).
- **8 decorative entities**: `item_display` holding a diamond, scattered at
  (±5,65,±5) and (±15,65,±15).

Total: 18 non-player entities, deterministic and reproducible. This is intentionally
a small, documented stand-in for "NPCs / holograms / cosmetics" — not a claim that it
matches the eventual real Hub's actual content or entity count.

## Bot harness limitations (documented, not silently worked around)

- **Chat/commands are disabled.** `ServerboundChatCommandPacket`'s signed-argument
  wire format (message signatures, last-seen-message acknowledgements) is nontrivial;
  an incorrect encoding was found to corrupt server-side packet decoding and drop the
  connection. Movement, keep-alive, teleport acknowledgement, and chunk receipt are
  real and protocol-correct; chat/command stimulus is not simulated in Phase 2.
- **Chunk data received is counted, not rendered/parsed.** The bot proves the server
  actually did the real chunk-serialization work (that's the expensive part) without
  spending client-side CPU decoding NBT it doesn't need.
- **No inventory/container UI interaction.** Only movement, keep-alive, teleport-ack,
  and chunk receipt are simulated; there is no GUI-equivalent interaction in Phase 2.
- Protocol details were derived directly from this fork's own decompiled packet
  definitions (`paper-server/src/minecraft/java/net/minecraft/network/protocol/`),
  since this build's protocol version has no public client/library to test against.

## Running

```bash
# 1. Boot a Gina-Hub server with online-mode=false (see server-test/configs/server.properties)
# 2. First time only: build the benchmark world
./scripts/setup-benchmark-world.sh /path/to/server-test/stdin.fifo
# 3. Compile the bot
cd loadbot && javac -d classes src/GinaLoadBot.java
# 4. Run a load test: <host> <port> <botCount> <durationSeconds> [rampSeconds]
java -cp classes GinaLoadBot 127.0.0.1 25565 50 60 10
```
