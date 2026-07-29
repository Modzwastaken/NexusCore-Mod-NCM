#!/usr/bin/env bash
#
# Runs a loader's GameTests and REFUSES A RUN THAT EXECUTED NOTHING.
#
#   ./tools/verify-gametests.sh neoforge
#   ./tools/verify-gametests.sh fabric   [--expect 12]
#   ./tools/verify-gametests.sh forge
#
# WHY THIS EXISTS, and why it is a separate script rather than a flag.
#
# A GameTest run that registers zero tests exits 0 and prints nothing alarming. The
# build is green, the task ran, and the number it describes is zero — so "GameTests
# pass" reads identically whether twelve tests passed or none existed. That is not a
# hypothetical: two designs were rejected while sharing these tests across loaders
# BECAUSE they would have registered nothing. Vanilla's GameTestRegistry enumerates
# getDeclaredMethods(), so a subclass of a shared holder registers zero; and Fabric
# instantiates its entrypoint class, which a private-constructor holder cannot
# satisfy. Both compile. Both would have been reported as a passing three-loader run.
#
# So the guard is not "did the task fail" — the task will not fail. It is "did this
# run execute a positive number of tests, and does the count agree with what the
# loader itself recorded".
#
# THE GUARD MUST BE PROVEN TO FIRE. `tools/mutate-gametests.sh` un-registers the
# tests on a loader and asserts this script goes red. A zero-test guard nobody has
# watched trigger is the same species as the check that silently vanishes.

set -u

LOADER="${1:-}"
EXPECT=""
if [ "${2:-}" = "--expect" ]; then EXPECT="${3:-}"; fi

case "$LOADER" in
  neoforge|forge) TASK="runGameTestServer" ;;
  fabric)         TASK="runGametest" ;;
  *) echo "usage: $0 <neoforge|fabric|forge> [--expect N]" >&2; exit 2 ;;
esac

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$(mktemp)"
trap 'rm -f "$LOG"' EXIT

echo "== $LOADER: $TASK"
( cd "$ROOT/$LOADER" && ./gradlew "$TASK" --offline ) > "$LOG" 2>&1
GRADLE_STATUS=$?

# ---- what the run itself reported -------------------------------------------------
#
# Vanilla's GameTestServer prints one of these and nothing else that carries a count:
#   "All N required tests passed :)"      -> N executed, none failed
#   "N required tests failed :("          -> failures, count is of FAILURES not total
#
passed=$(grep -oE "All ([0-9]+) required tests passed" "$LOG" | grep -oE "[0-9]+" | tail -1)
failed=$(grep -oE "([0-9]+) required tests failed"     "$LOG" | grep -oE "[0-9]+" | tail -1)

if [ -n "${failed:-}" ] && [ "${failed:-0}" -gt 0 ]; then
  echo "FAIL: $failed test(s) failed on $LOADER"
  grep -iE "failed at|Unrecognized|Exception" "$LOG" | head -10
  exit 1
fi

if [ -z "${passed:-}" ]; then
  echo "FAIL: $LOADER produced no test count at all."
  echo "      The task exited $GRADLE_STATUS, but nothing reported how many tests ran."
  echo "      A run with no count is not a passing run — it is an unmeasured one."
  tail -25 "$LOG"
  exit 1
fi

# ---- THE GUARD --------------------------------------------------------------------
if [ "$passed" -eq 0 ]; then
  echo "FAIL: $LOADER executed ZERO tests."
  echo "      The task succeeded and the suite is empty, which is the failure this"
  echo "      script exists for: registration is broken, not the tests. Check that the"
  echo "      loader registers the SHARED holder directly — vanilla enumerates"
  echo "      getDeclaredMethods(), so an inherited method registers nothing."
  exit 1
fi

if [ -n "$EXPECT" ] && [ "$passed" -ne "$EXPECT" ]; then
  echo "FAIL: $LOADER executed $passed test(s), expected $EXPECT."
  echo "      A count that drifts down silently is how a loader stops covering things"
  echo "      while still reporting green."
  exit 1
fi

# ---- cross-check against the loader's own report, where it writes one --------------
REPORT="$ROOT/fabric/build/gametest-report.xml"
if [ "$LOADER" = "fabric" ] && [ -f "$REPORT" ]; then
  reported=$(grep -oE 'tests="[0-9]+"' "$REPORT" | grep -oE '[0-9]+' | head -1)
  if [ -n "${reported:-}" ] && [ "$reported" != "$passed" ]; then
    echo "FAIL: fabric's log says $passed but its report file says $reported."
    echo "      Two sources disagreeing about the same run means one of them is not"
    echo "      describing this run."
    exit 1
  fi
  echo "   report file agrees: $reported"
fi

if [ $GRADLE_STATUS -ne 0 ]; then
  echo "FAIL: $passed test(s) passed but the task exited $GRADLE_STATUS"
  tail -20 "$LOG"
  exit 1
fi

echo "PASS: $LOADER executed $passed test(s), 0 failed"
