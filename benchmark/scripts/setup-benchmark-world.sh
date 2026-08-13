#!/usr/bin/env bash
# Deterministically builds the Phase 2 benchmark Hub-Map world content.
#
# Run against a freshly-booted, otherwise-clean Gina-Hub server directory (a plain
# clean-install Hub-Map: single bedrock spawn block, no other content). Requires a
# stdin.fifo already wired to the server's console (see benchmark/README.md).
#
# Usage: ./setup-benchmark-world.sh <path-to-stdin.fifo>

set -euo pipefail
FIFO="${1:?usage: setup-benchmark-world.sh <path-to-stdin.fifo>}"

send() { echo "$1" > "$FIFO"; sleep "${2:-1}"; }

# Load the region we're about to edit (nothing is loaded with zero players connected)
send "execute in minecraft:overworld run forceload add -50 -50 50 50" 2

# 101x101 walkable stone platform at y=64 (spawn bedrock sits within this area),
# with a bedrock layer underneath so nothing can be dug through into the void.
send "execute in minecraft:overworld run fill -50 64 -50 50 64 50 minecraft:smooth_stone" 3
send "execute in minecraft:overworld run fill -50 63 -50 50 63 50 minecraft:bedrock" 3

# 6 static NPC-proxy armor stands (NoAI, invulnerable, silent - near-zero simulation cost,
# stands in for Gina's future NPC system) in a ring around spawn
for coords in "10 65 0" "-10 65 0" "0 65 10" "0 65 -10" "7 65 7" "-7 65 -7"; do
  send "execute in minecraft:overworld run summon minecraft:armor_stand $coords {NoAI:1b,Invulnerable:1b,Silent:1b,CustomNameVisible:1b,CustomName:'\"NPC\"'}" 1
done

# 4 static hologram text_displays
for coords in "10 66.5 0" "-10 66.5 0" "0 66.5 10" "0 66.5 -10"; do
  send "execute in minecraft:overworld run summon minecraft:text_display $coords {text:'\"Hub Sign\"',billboard:\"center\"}" 1
done

# 8 decorative item_display entities scattered around
for coords in "5 65 5" "-5 65 5" "5 65 -5" "-5 65 -5" "15 65 15" "-15 65 15" "15 65 -15" "-15 65 -15"; do
  send "execute in minecraft:overworld run summon minecraft:item_display $coords {item:{id:\"minecraft:diamond\",count:1}}" 1
done

send "save-all" 3
echo "Benchmark world content applied. See benchmark/README.md for the exact inventory."
