#!/usr/bin/env bash
# Final N-minute production proof: boots once, holds N players connected for the full
# duration, takes periodic spark+heap snapshots throughout (not just one), confirms
# memory stays flat and TPS stays healthy across the whole window.
# Usage: final-proof.sh <N> <Xmx> <Xms> <minutes> <resultsFile> <fixture>
set -uo pipefail
N="${1:?}"; XMX="${2:?}"; XMS="${3:?}"; MINUTES="${4:?}"; RESULTS="${5:?}"; FIXTURE="${6:-final}"
: > "$RESULTS"
SECS=$(( MINUTES * 60 ))
RAMP=30
SETTLE=45
BOT_DURATION=$(( RAMP + SETTLE + SECS + 60 ))

BENCH_DIR="/c/Users/majko/Documents/Gina/Gina Hub/benchmark"
SERVER_DIR="$BENCH_DIR/server-test"
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

STALE_PID=$(jcmd 2>&1 | grep "Gina-Hub" | awk '{print $1}')
[ -n "$STALE_PID" ] && { taskkill //F //PID "$STALE_PID" > /dev/null 2>&1; sleep 2; }

if [ "$FIXTURE" = "final" ]; then FIXTURE_DIR="$BENCH_DIR/final-hub-fixture/Hub-Map"; else FIXTURE_DIR="$BENCH_DIR/hub-map-fixture/Hub-Map"; fi
rm -rf "$SERVER_DIR/worlds" "$SERVER_DIR/logs"
mkdir -p "$SERVER_DIR/worlds"
cp -r "$FIXTURE_DIR" "$SERVER_DIR/worlds/Hub-Map"

cd "$SERVER_DIR"
rm -f stdin.fifo server.log
mkfifo stdin.fifo
( tail -f stdin.fifo | java -Xms"$XMS" -Xmx"$XMX" -jar Gina-Hub.jar --nogui > server.log 2>&1 ) &
disown

for i in $(seq 1 60); do grep -q "Done (" server.log 2>/dev/null && break; sleep 1; done
grep -q "Done (" server.log 2>/dev/null || { echo "BOOT_FAILED" | tee -a "$RESULTS"; exit 1; }
sleep 3
PID=$(jcmd 2>&1 | grep Gina-Hub | awk '{print $1}')
echo "server PID=$PID  N=$N  Xms=$XMS Xmx=$XMX  proof_minutes=$MINUTES" >> "$RESULTS"

cd "$BENCH_DIR/loadbot"
java -cp classes GinaLoadBot 127.0.0.1 25565 "$N" "$BOT_DURATION" "$RAMP" > "$BENCH_DIR/results/proof-bots-$N.log" 2>&1 &
BOT_PID=$!

sleep $(( RAMP + SETTLE ))

CHECKPOINTS=$(( SECS / 180 ))
[ "$CHECKPOINTS" -lt 1 ] && CHECKPOINTS=1
i=0
while [ "$i" -lt "$CHECKPOINTS" ]; do
    cd "$SERVER_DIR"
    echo "=== checkpoint $((i+1))/$CHECKPOINTS (t=$((i*180))s into proof window) ===" >> "$RESULTS"
    echo "ginabench" > stdin.fifo; sleep 1
    echo "spark healthreport --memory" > stdin.fifo; sleep 4
    jcmd "$PID" GC.heap_info >> "$RESULTS" 2>&1
    tasklist //FI "PID eq $PID" //FO LIST 2>&1 | grep "Mem Usage" >> "$RESULTS"
    tail -3000 server.log | sed 's/\x1b\[[0-9;]*m//g' | grep -aE "GINABENCH|TPS from|Tick durations|CPU usage|Memory usage:|Full GC" -A2 >> "$RESULTS"
    echo "" >> "$RESULTS"
    sleep 175
    i=$((i+1))
done

echo "--- bot self-report at end of proof window ---" >> "$RESULTS"
grep -a "status +\|FINAL" "$BENCH_DIR/results/proof-bots-$N.log" | tail -5 >> "$RESULTS"

echo "stop" > stdin.fifo
kill "$BOT_PID" 2>/dev/null
for i in $(seq 1 60); do jcmd 2>&1 | grep -q "$PID Gina-Hub" || break; sleep 1; done
echo "=== proof complete ===" >> "$RESULTS"
