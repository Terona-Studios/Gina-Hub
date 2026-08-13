#!/usr/bin/env bash
# Same as leak-cycle-test.sh but at 200 players (Phase 3 high-load leak test).
set -uo pipefail
BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"

PID=$(jcmd 2>&1 | grep Gina-Hub | awk '{print $1}')
echo "server PID=$PID"

for cycle in 1 2 3 4; do
  echo "=== cycle $cycle: connecting 200 bots ==="
  ( cd "$BENCH_DIR/loadbot" && java -cp classes GinaLoadBot 127.0.0.1 25565 200 40 15 ) \
      > "$BENCH_DIR/results/leak200-cycle-$cycle.log" 2>&1
  echo "cycle $cycle bots finished, waiting for full disconnect + stabilization"
  sleep 8
  jcmd "$PID" GC.run > /dev/null 2>&1
  sleep 2
  echo "--- cycle $cycle post-GC heap ---"
  jcmd "$PID" GC.heap_info 2>&1
  tasklist //FI "PID eq $PID" //FO LIST 2>&1 | grep "Mem Usage"
  echo ""
done
