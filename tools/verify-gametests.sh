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
#
# WHICH BRANCH FIRES DEPENDS ON HOW REGISTRATION DIES, and the three are not
# interchangeable. Corrected 2026-07-29 after the header above was read as claiming all
# three loaders exercise the same path — they do not:
#
#   ZERO-count  (line ~114)  A count is printed and it is 0. This is the shipped-defect
#                            shape: @GameTestHolder missing -> namespace falls back to
#                            "minecraft" -> enabledGameTestNamespaces drops everything ->
#                            the server runs, reports zero, exits 0. Green and empty.
#   NO-count    (line ~105)  The run died before printing any count. Emptying fabric's
#                            entrypoint list lands HERE, not on the zero branch: with no
#                            test functions, GameTestServer.create throws and no count is
#                            ever produced. Refused because an unmeasured run is not a
#                            passing one — but it is a DIFFERENT refusal.
#   TIMEOUT     (line ~68)   No verdict at all. Forge, measured: severing the holders
#                            hangs the server rather than failing it.
#
# So mutate-gametests.sh proves the guard rejects three distinct broken states. It does
# NOT prove all three loaders reach the zero-count branch, and the earlier framing of
# "the guard fires on all three" quietly implied that it did.

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

REPORT="$ROOT/fabric/build/gametest-report.xml"

# Delete any previous report BEFORE running. Nothing cleans it, so a run that dies
# early leaves the LAST run's file in place — and a cross-check that reads it is
# comparing this run against a different one. That is not hypothetical: a report from
# before the namespace fix, carrying a failure, survived long enough to be mistaken
# for the current run's.
if [ "$LOADER" = "fabric" ]; then rm -f "$REPORT"; fi

# TIMEOUT LIVES INSIDE THE GATE, not in whoever calls it.
#
# A Forge GameTest server whose registration is empty throws inside the server spin
# lambda and never reaches exit — it HANGS. Measured 2026-07-29: a severed-holder run
# sat for the full 560s cap and was killed with 124. While the timeout was the
# caller's job, that hang read as an ordinary non-zero exit to anything checking $?,
# so a harness could record it as "the guard fired" when nothing had been judged at
# all. A gate whose liveness depends on the caller remembering a wrapper is not a
# gate. Override with GAMETEST_TIMEOUT for a slow machine.
GAMETEST_TIMEOUT="${GAMETEST_TIMEOUT:-480}"

echo "== $LOADER: $TASK (timeout ${GAMETEST_TIMEOUT}s)"
( cd "$ROOT/$LOADER" && timeout "$GAMETEST_TIMEOUT" ./gradlew "$TASK" --offline ) > "$LOG" 2>&1
GRADLE_STATUS=$?

if [ "$GRADLE_STATUS" -eq 124 ]; then
  echo "FAIL: $LOADER did not finish within ${GAMETEST_TIMEOUT}s — TIMED OUT."
  echo "      This is a distinct outcome from a failing run and must not be read as one."
  echo "      An empty registration on Forge throws inside the server spin and never"
  echo "      exits, so a hang here usually means ZERO tests registered."
  tail -15 "$LOG"
  exit 124
fi

# ---- what the run itself reported -------------------------------------------------
#
# Vanilla's GameTestServer prints one of these and nothing else that carries a count:
#   "All N required tests passed :)"      -> N executed, none failed
#   "N required tests failed :("          -> failures, count is of FAILURES not total
#
passed=$(grep -oE "All ([0-9]+) required tests passed" "$LOG" | grep -oE "[0-9]+" | tail -1)
failed=$(grep -oE "([0-9]+) required tests failed"     "$LOG" | grep -oE "[0-9]+" | tail -1)

# An OPTIONAL test (required = false) failing prints a different line, and
# System.exit(getFailedRequiredCount()) stays 0 — so the run is green, the required
# count is untouched, and nothing here would notice. Unreachable while all 12 use a
# bare @GameTest (required defaults true), but it becomes live the first time someone
# writes required = false, which is exactly when nobody is looking for it.
optional_failed=$(grep -oE "([0-9]+) optional tests failed" "$LOG" | grep -oE "[0-9]+" | tail -1)
if [ -n "${optional_failed:-}" ] && [ "${optional_failed:-0}" -gt 0 ]; then
  echo "FAIL: $optional_failed OPTIONAL test(s) failed on $LOADER"
  echo "      The run still exits 0 because only REQUIRED failures set the exit code."
  grep -iE "failed at" "$LOG" | head -10
  exit 1
fi

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
# Cross-check against fabric's own report.
#
# THIS COMPARED NOTHING UNTIL 2026-07-29 and said it agreed anyway. It grepped
# tests="N", an attribute the format does not have, so the count came back EMPTY, the
# -n guard skipped the comparison, and every run printed "report file agrees: " with
# nothing after the colon. A correct parse would have agreed, which is why it survived
# unnoticed — the check was absent, not wrong, and absence reads exactly like a pass.
# The comment below it claimed two sources were being compared while source B was
# never read. Counting the elements that actually exist is the fix.
if [ "$LOADER" = "fabric" ]; then
  if [ ! -f "$REPORT" ]; then
    echo "FAIL: fabric ran but wrote no report at $REPORT."
    echo "      The run is unconfirmed by its own second source."
    exit 1
  fi
  reported=$(grep -o "<testcase" "$REPORT" | wc -l | tr -d ' ')
  report_failures=$(grep -o "<failure" "$REPORT" | wc -l | tr -d ' ')

  if [ "$reported" != "$passed" ]; then
    echo "FAIL: fabric's log says $passed test(s) but its report holds $reported."
    echo "      Two sources disagreeing about the same run means one of them is not"
    echo "      describing this run."
    exit 1
  fi
  if [ "$report_failures" -gt 0 ]; then
    echo "FAIL: fabric's log reported no failures but its report holds $report_failures."
    grep -o '<failure message="[^"]*"' "$REPORT" | head -5
    exit 1
  fi
  echo "   report file agrees: $reported testcase(s), $report_failures failure(s)"
fi

if [ $GRADLE_STATUS -ne 0 ]; then
  echo "FAIL: $passed test(s) passed but the task exited $GRADLE_STATUS"
  tail -20 "$LOG"
  exit 1
fi

echo "PASS: $LOADER executed $passed test(s), 0 failed"
