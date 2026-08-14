#!/usr/bin/env bash
# Builds the Phase 4 "heavy-hub" benchmark world content -- a heavier but still reasonable
# Hub scene meant to approximate the eventual real Gina Hub plugin's entity load, kept
# SEPARATE from the canonical core fixture (setup-benchmark-world.sh). Same platform
# geometry as the core fixture, just more static decoration:
#   25 NPC-proxy armor stands, 20 hologram text_displays, 30 decorative item_displays
#
# Usage: ./setup-heavy-hub-world.sh <path-to-stdin.fifo>

set -euo pipefail
FIFO="${1:?usage: setup-heavy-hub-world.sh <path-to-stdin.fifo>}"

send() { echo "$1" > "$FIFO"; sleep "${2:-0.5}"; }

send "execute in minecraft:overworld run forceload add -50 -50 50 50" 2
send "execute in minecraft:overworld run fill -50 64 -50 50 64 50 minecraft:smooth_stone" 3
send "execute in minecraft:overworld run fill -50 63 -50 50 63 50 minecraft:bedrock" 3

# 25 NPC-proxy armor stands in a wider ring/grid than the core fixture
n=0
for coords in \
  "10 65 0" "-10 65 0" "0 65 10" "0 65 -10" "7 65 7" "-7 65 -7" "7 65 -7" "-7 65 7" \
  "20 65 0" "-20 65 0" "0 65 20" "0 65 -20" "14 65 14" "-14 65 14" "14 65 -14" "-14 65 -14" \
  "30 65 0" "-30 65 0" "0 65 30" "0 65 -30" "21 65 21" "-21 65 21" "21 65 -21" "-21 65 -21" \
  "35 65 5"; do
  send "execute in minecraft:overworld run summon minecraft:armor_stand $coords {NoAI:1b,Invulnerable:1b,Silent:1b,CustomNameVisible:1b,CustomName:'\"NPC $n\"'}" 0.4
  n=$((n+1))
done

# 20 hologram text_displays
n=0
for coords in \
  "10 66.5 0" "-10 66.5 0" "0 66.5 10" "0 66.5 -10" "20 66.5 0" "-20 66.5 0" "0 66.5 20" "0 66.5 -20" \
  "5 66.5 5" "-5 66.5 5" "5 66.5 -5" "-5 66.5 -5" "15 66.5 15" "-15 66.5 15" "15 66.5 -15" "-15 66.5 -15" \
  "25 66.5 25" "-25 66.5 25" "25 66.5 -25" "-25 66.5 -25"; do
  send "execute in minecraft:overworld run summon minecraft:text_display $coords {text:'\"Hologram $n\"',billboard:\"center\"}" 0.4
  n=$((n+1))
done

# 30 decorative item_displays scattered around
n=0
for coords in \
  "5 65 5" "-5 65 5" "5 65 -5" "-5 65 -5" "15 65 15" "-15 65 15" "15 65 -15" "-15 65 -15" \
  "25 65 5" "-25 65 5" "25 65 -5" "-25 65 -5" "5 65 25" "-5 65 25" "5 65 -25" "-5 65 -25" \
  "32 65 10" "-32 65 10" "32 65 -10" "-32 65 -10" "10 65 32" "-10 65 32" "10 65 -32" "-10 65 -32" \
  "38 65 0" "-38 65 0" "0 65 38" "0 65 -38" "27 65 27" "-27 65 -27"; do
  send "execute in minecraft:overworld run summon minecraft:item_display $coords {item:{id:\"minecraft:diamond\",count:1}}" 0.4
  n=$((n+1))
done

send "save-all" 3
echo "Heavy-hub benchmark world content applied: 25 NPC armor stands, 20 holograms, 30 item displays."
