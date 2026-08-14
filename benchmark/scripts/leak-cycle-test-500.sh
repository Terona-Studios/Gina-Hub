#!/usr/bin/env bash
# Phase 4 high-load leak test: boots a fresh server from the core fixture, then runs
# repeated 500-player join/leave cycles, checking post-GC heap after each cycle for
# growth that doesn't release (a leak) vs. heap that returns to a stable baseline.
set -uo pipefail
BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$BENCH_DIR/server-test"
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
RESULTS="$BENCH_DIR/results/leak500-results.txt"
: > "$RESULTS"

STALE_PID=$(jcmd 2>&1 | grep "Gina-Hub" | awk '{print $1}')
if [ -n "$STALE_PID" ]; then
    taskkill //F //PID "$STALE_PID" > /dev/null 2>&1
    sleep 2
fi

rm -rf "$SERVER_DIR/worlds" "$SERVER_DIR/logs"
mkdir -p "$SERVER_DIR/worlds"
cp -r "$BENCH_DIR/hub-map-fixture/Hub-Map" "$SERVER_DIR/worlds/Hub-Map"

cd "$SERVER_DIR"
rm -f stdin.fifo server.log
mkfifo stdin.fifo
( tail -f stdin.fifo | java -Xms1G -Xmx1G -jar Gina-Hub.jar --nogui > server.log 2>&1 ) &
disown

for i in $(seq 1 60); do
    if grep -q "Done (" server.log 2>/dev/null; then break; fi
    sleep 1
done
if ! grep -q "Done (" server.log 2>/dev/null; then
    echo "SERVER FAILED TO BOOT" | tee -a "$RESULTS"
    exit 1
fi
sleep 3
PID=$(jcmd 2>&1 | grep Gina-Hub | awk '{print $1}')
echo "server PID=$PID" >> "$RESULTS"

jcmd "$PID" GC.run > /dev/null 2>&1
sleep 2
echo "--- baseline (0 cycles) heap ---" >> "$RESULTS"
jcmd "$PID" GC.heap_info >> "$RESULTS" 2>&1
tasklist //FI "PID eq $PID" //FO LIST 2>&1 | grep "Mem Usage" >> "$RESULTS"

for cycle in 1 2 3 4; do
    echo "=== cycle $cycle: connecting 500 bots ===" >> "$RESULTS"
    ( cd "$BENCH_DIR/loadbot" && java -cp classes GinaLoadBot 127.0.0.1 25565 500 60 75 ) \
        > "$BENCH_DIR/results/leak500-cycle-$cycle.log" 2>&1
    echo "cycle $cycle bots finished, waiting for full disconnect + stabilization" >> "$RESULTS"
    sleep 10
    jcmd "$PID" GC.run > /dev/null 2>&1
    sleep 2
    echo "--- cycle $cycle post-GC heap ---" >> "$RESULTS"
    jcmd "$PID" GC.heap_info >> "$RESULTS" 2>&1
    tasklist //FI "PID eq $PID" //FO LIST 2>&1 | grep "Mem Usage" >> "$RESULTS"
    echo "" >> "$RESULTS"
done

echo "stop" > stdin.fifo
for i in $(seq 1 60); do
    if ! jcmd 2>&1 | grep -q "$PID Gina-Hub"; then break; fi
    sleep 1
done
echo "=== leak test complete ===" >> "$RESULTS"
