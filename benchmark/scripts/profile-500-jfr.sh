#!/usr/bin/env bash
# Deep JFR profile of true 500-player steady state (Phase 4).
#
# Unlike run-load-point.sh's routine spark snapshot, this starts a JFR recording
# ONLY after the ramp+settle window has fully passed (same join-burst-vs-steady-state
# lesson learned in Phase 3), lets it run for a long steady-state window while bots
# stay connected and moving, then stops and dumps it -- so the profiler samples are
# not contaminated by connection-storm / chunk-send burst activity.
#
# Usage: profile-500-jfr.sh <playerCount> <jfrOutFile> <resultsFile>

set -uo pipefail
N="${1:?usage: profile-500-jfr.sh <playerCount> <jfrOutFile> <resultsFile> [jfrWindowSeconds]}"
JFR_OUT="${2:?usage: profile-500-jfr.sh <playerCount> <jfrOutFile> <resultsFile> [jfrWindowSeconds]}"
RESULTS="${3:?usage: profile-500-jfr.sh <playerCount> <jfrOutFile> <resultsFile> [jfrWindowSeconds]}"
: > "$RESULTS"

RAMP=75
SETTLE=90
JFR_WINDOW="${4:-180}"
DURATION=$(( RAMP + SETTLE + JFR_WINDOW + 40 ))
XMX=1G
XMS=1G

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_DIR="$BENCH_DIR/server-test"
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"

echo "=== JFR steady-state profile: $N players (ramp=${RAMP}s settle=${SETTLE}s jfr_window=${JFR_WINDOW}s duration=${DURATION}s) ===" | tee -a "$RESULTS"

STALE_PID=$(jcmd 2>&1 | grep "Gina-Hub" | awk '{print $1}')
if [ -n "$STALE_PID" ]; then
    echo "killing stale Gina-Hub process $STALE_PID before starting" >> "$RESULTS"
    taskkill //F //PID "$STALE_PID" > /dev/null 2>&1
    sleep 2
fi

rm -rf "$SERVER_DIR/worlds" "$SERVER_DIR/logs"
mkdir -p "$SERVER_DIR/worlds"
cp -r "$BENCH_DIR/hub-map-fixture/Hub-Map" "$SERVER_DIR/worlds/Hub-Map"

cd "$SERVER_DIR"
rm -f stdin.fifo server.log gc.log "$JFR_OUT"
mkfifo stdin.fifo
( tail -f stdin.fifo | java -Xms"$XMS" -Xmx"$XMX" \
    -XX:+UnlockDiagnosticVMOptions \
    -jar Gina-Hub.jar --nogui > server.log 2>&1 ) &
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

cd "$BENCH_DIR/loadbot"
java -cp classes GinaLoadBot 127.0.0.1 25565 "$N" "$DURATION" "$RAMP" > "$BENCH_DIR/results/bots-jfr-$N.log" 2>&1 &
BOT_PID=$!

echo "waiting ${RAMP}+${SETTLE}s for ramp+settle before starting JFR recording..." >> "$RESULTS"
sleep $(( RAMP + SETTLE ))

cd "$SERVER_DIR"
echo "--- starting JFR recording (steady state) ---" >> "$RESULTS"
# jcmd's DCMD parser re-tokenizes on spaces even when bash quoted the argument as one word,
# so an absolute path containing a space (e.g. "Gina Hub") silently truncates. Use a bare
# filename instead -- the java process's cwd is already $SERVER_DIR (set before launch above).
JFR_BASENAME="$(basename "$JFR_OUT")"
jcmd "$PID" JFR.start name=steady settings=profile filename="$JFR_BASENAME" >> "$RESULTS" 2>&1

# take a mid-window spark snapshot too, for cross-reference against the JFR data
sleep $(( JFR_WINDOW / 2 ))
echo "ginabench" > stdin.fifo
sleep 1
echo "spark healthreport --memory" > stdin.fifo
sleep 4
echo "--- mid-window console output (N=$N) ---" >> "$RESULTS"
tail -5000 server.log | sed 's/\x1b\[[0-9;]*m//g' \
    | grep -aE "GINABENCH|TPS from|Tick durations|CPU usage|Memory usage:|Non-heap|G1 " -A2 >> "$RESULTS"

sleep $(( JFR_WINDOW / 2 - 5 ))

echo "--- stopping JFR recording ---" >> "$RESULTS"
jcmd "$PID" JFR.stop name=steady >> "$RESULTS" 2>&1
sleep 2

echo "--- JFR file check ---" >> "$RESULTS"
ls -la "$JFR_OUT" >> "$RESULTS" 2>&1

# --- teardown ---
wait "$BOT_PID" 2>/dev/null
echo "stop" > stdin.fifo
for i in $(seq 1 60); do
    if ! jcmd 2>&1 | grep -q "$PID Gina-Hub"; then break; fi
    sleep 1
done
sleep 2

echo "" >> "$RESULTS"
echo "=== end JFR profile N=$N ===" >> "$RESULTS"
