#!/usr/bin/env bash
# Runs repeated connect/stabilize/disconnect cycles against an already-running
# Gina-Hub server and records post-GC heap after each cycle, to check for
# retained per-player state. Not shipped in production.
set -uo pipefail
BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"

PID=$(jcmd 2>&1 | grep Gina-Hub | awk '{print $1}')
echo "server PID=$PID"

for cycle in 1 2 3 4; do
  echo "=== cycle $cycle: connecting 100 bots ==="
  ( cd "$BENCH_DIR/loadbot" && java -cp classes GinaLoadBot 127.0.0.1 25565 100 25 8 ) \
      > "$BENCH_DIR/results/leak-cycle-$cycle.log" 2>&1
  echo "cycle $cycle bots finished, waiting for full disconnect + stabilization"
  sleep 5
  jcmd "$PID" GC.run > /dev/null 2>&1
  sleep 2
  echo "--- cycle $cycle post-GC heap ---"
  jcmd "$PID" GC.heap_info 2>&1
  tasklist //FI "PID eq $PID" //FO LIST 2>&1 | grep "Mem Usage"
  echo ""
done
