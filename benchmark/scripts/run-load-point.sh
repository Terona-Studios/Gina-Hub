#!/usr/bin/env bash
# Runs one player-ramp load point against a freshly-booted Gina-Hub benchmark server.
#
# Usage: run-load-point.sh <playerCount> <resultsFile> [durationSeconds] [rampSeconds]
#
# Boots a clean server from the saved benchmark fixture, connects <playerCount> bots
# (ramped in progressively, not an instant connection storm), waits for stabilization,
# then captures spark healthreport + GC log stats + jcmd heap/NMT + process RSS into
# <resultsFile>. Tears everything down afterward. Not shipped in production.

set -uo pipefail
N="${1:?usage: run-load-point.sh <playerCount> <resultsFile> [durationSeconds] [rampSeconds]}"
RESULTS="${2:?usage: run-load-point.sh <playerCount> <resultsFile> [durationSeconds] [rampSeconds]}"
: > "$RESULTS"  # start each load point's results file fresh
DURATION="${3:-$(( N < 50 ? 60 : (N < 150 ? 90 : 120) ))}"
RAMP="${4:-$(( N < 20 ? 3 : N / 8 ))}"
XMX="${5:-1G}"
XMS="${6:-1G}"
# Extra settle time after the ramp finishes, before the steady-state measurement window.
# Default 12s worked through ~200 players; larger populations need longer for chunk-send/
# connection-setup backlog to fully drain -- otherwise this measures join-burst tail, not
# true steady state. Bump this explicitly for 300+ player runs.
SETTLE="${7:-12}"
# Which world fixture to boot from: "core" (default, canonical small fixture) or
# "heavy" (Phase 4 heavy-hub fixture: 25 NPCs, 20 holograms, 30 item displays).
FIXTURE="${8:-core}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$BENCH_DIR/server-test"
if [ "$FIXTURE" = "heavy" ]; then
    FIXTURE_DIR="$BENCH_DIR/heavy-hub-fixture/Hub-Map"
else
    FIXTURE_DIR="$BENCH_DIR/hub-map-fixture/Hub-Map"
fi
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "=== Load point: $N players (duration=${DURATION}s ramp=${RAMP}s fixture=${FIXTURE}) ===" | tee -a "$RESULTS"

# safety: make sure no stale server from a previous (possibly failed) run is holding the port
STALE_PID=$(jcmd 2>&1 | grep "Gina-Hub" | awk '{print $1}')
if [ -n "$STALE_PID" ]; then
    echo "killing stale Gina-Hub process $STALE_PID before starting" >> "$RESULTS"
    taskkill //F //PID "$STALE_PID" > /dev/null 2>&1
    sleep 2
fi

# --- fresh server from the fixture ---
rm -rf "$SERVER_DIR/worlds" "$SERVER_DIR/logs"
mkdir -p "$SERVER_DIR/worlds"
cp -r "$FIXTURE_DIR" "$SERVER_DIR/worlds/Hub-Map"

cd "$SERVER_DIR"
rm -f stdin.fifo server.log gc.log
mkfifo stdin.fifo
echo "JVM: -Xms$XMS -Xmx$XMX" >> "$RESULTS"
( tail -f stdin.fifo | java -Xms"$XMS" -Xmx"$XMX" \
    -Xlog:gc*:file=gc.log:time,uptime:filecount=0 \
    -XX:+UnlockDiagnosticVMOptions -XX:NativeMemoryTracking=summary \
    -jar Gina-Hub.jar --nogui > server.log 2>&1 ) &
disown
SERVER_LAUNCHER_PID=$!

# wait for boot (up to 60s)
for i in $(seq 1 60); do
    if grep -q "Done (" server.log 2>/dev/null; then break; fi
    sleep 1
done
if ! grep -q "Done (" server.log 2>/dev/null; then
    echo "SERVER FAILED TO BOOT for N=$N" | tee -a "$RESULTS"
    exit 1
fi
sleep 3  # let JIT/threads settle post-"Done"

PID=$(jcmd 2>&1 | grep Gina-Hub | awk '{print $1}')
echo "server PID=$PID" >> "$RESULTS"

# --- ramp bots in background ---
cd "$BENCH_DIR/loadbot"
java -cp classes GinaLoadBot 127.0.0.1 25565 "$N" "$DURATION" "$RAMP" > "$BENCH_DIR/results/bots-$N.log" 2>&1 &
BOT_PID=$!

# wait for ramp to finish + stabilization window
sleep $(( RAMP + SETTLE ))

# --- steady-state measurement ---
cd "$SERVER_DIR"
echo "ginabench" > stdin.fifo
sleep 2
echo "spark healthreport --memory" > stdin.fifo
sleep 4

# force a GC and capture heap/NMT while bots are still connected (steady-state, not idle)
jcmd "$PID" GC.run > /dev/null 2>&1
sleep 2
echo "--- GC.heap_info (N=$N) ---" >> "$RESULTS"
jcmd "$PID" GC.heap_info >> "$RESULTS" 2>&1
echo "--- NMT summary (N=$N) ---" >> "$RESULTS"
jcmd "$PID" VM.native_memory summary scale=MB >> "$RESULTS" 2>&1
echo "--- RSS (N=$N) ---" >> "$RESULTS"
tasklist //FI "PID eq $PID" //FO LIST 2>&1 | grep "Mem Usage" >> "$RESULTS"
echo "--- Thread count (N=$N) ---" >> "$RESULTS"
jcmd "$PID" Thread.print 2>&1 | grep -c '^"' >> "$RESULTS"

# pull the relevant console output out of server.log RIGHT NOW, while the measurement
# window is still near the tail of the log. At high player counts the "moved too quickly"
# warning spam grows the log by tens of thousands of lines per minute, so waiting until
# teardown (after the bots run out their full remaining duration) pushes the spark
# healthreport output clean out of any reasonably-sized tail window -- this must happen
# immediately after the healthreport command above, not after wait "$BOT_PID".
echo "--- console output (N=$N) ---" >> "$RESULTS"
tail -5000 server.log | sed 's/\x1b\[[0-9;]*m//g' \
    | grep -aE "GINABENCH|TPS from|Tick durations|CPU usage|Memory usage:|Non-heap|G1 |[0-9]%.*process" -A2 >> "$RESULTS"

# --- teardown ---
wait "$BOT_PID" 2>/dev/null

# bot-side self-reported stats (to detect harness-side bottlenecks)
echo "--- bot harness self-report (N=$N) ---" >> "$RESULTS"
grep -a "status +" "$BENCH_DIR/results/bots-$N.log" | tail -3 >> "$RESULTS"
grep -a "FINAL" "$BENCH_DIR/results/bots-$N.log" >> "$RESULTS"
echo "stop" > stdin.fifo
# wait for the process to actually exit (larger populations take longer to save/close)
# rather than a fixed sleep, so the next load point never races a still-shutting-down JVM
for i in $(seq 1 60); do
    if ! jcmd 2>&1 | grep -q "$PID Gina-Hub"; then break; fi
    sleep 1
done
sleep 2

echo "" >> "$RESULTS"
echo "=== end load point N=$N ===" >> "$RESULTS"
echo "" >> "$RESULTS"
