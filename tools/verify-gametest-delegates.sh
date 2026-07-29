#!/usr/bin/env bash
#
# Asserts every shared @GameTest has a delegate in EVERY loader's holder.
#
#   ./tools/verify-gametest-delegates.sh
#
# WHY THIS EXISTS.
#
# The shared GameTests in common/ do not register themselves. Vanilla's GameTestRegistry
# enumerates getDeclaredMethods(), so an inherited method is invisible to it — which is
# why each loader carries a holder that declares one delegate per shared test and calls
# through. That design is sound and documented. What it creates is a second place to
# forget.
#
# Found empirically on 2026-07-29, not by reading. A thirteenth test was added to
# NexusWorldGameTests with no delegate. It compiled. The build was BUILD SUCCESSFUL. The
# run reported "All 12 required tests passed :)" and exited 0. The new test did not run,
# and nothing anywhere said so.
#
# verify-gametests.sh does NOT catch this, and it is worth being precise about why: its
# --expect N compares against a number a human typed. A forgotten delegate holds the
# count at the OLD value, so --expect 12 passes for the same reason it always did. That
# guard catches a count that DROPS. It cannot catch a count that failed to RISE, because
# it has no idea a test was written. The two guards fail in opposite directions, which is
# the only reason both are needed.
#
# This is the house failure family in its purest form: a test that exists, compiles,
# reads as covered, is listed in the rung, and has never once executed. It is worse than
# no test, because no test is visibly absent.
#
# THE THREE LOADERS DO NOT REGISTER THE SAME WAY, and this script would have been wrong
# if it assumed they did — its first version failed fabric for having no holder, which
# fabric correctly does not need.
#
#   neoforge, forge   A loader-side *Holder class declares ONE DELEGATE PER TEST and
#                     calls through. Adding a shared test registers NOTHING here until
#                     a delegate is written by hand.
#   fabric            fabric.mod.json lists the SHARED CLASSES themselves in the
#                     fabric-gametest entrypoint array. Adding a shared test to a class
#                     already listed registers it AUTOMATICALLY.
#
# That asymmetry is the actual hazard, and it is worse than a plain forgotten delegate:
# a new shared test runs on fabric and silently does not run on neoforge or forge. All
# three report green. All three are "passing GameTests". They are not running the same
# suite, and no count anywhere disagrees, because each loader is internally consistent
# with its own expectation. So this script also asserts the three totals AGREE.

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHARED_DIR="$ROOT/common/src/main/java/com/mwtstudios/nexuscore/gametest"
FAILURES=0
NEOFORGE_TOTAL=0
FABRIC_TOTAL=0
FORGE_TOTAL=0

# One pass per shared class. Each is checked against fabric's entrypoint list and against
# the neoforge/forge holder that must declare a delegate for every test it contains.
for shared in "$SHARED_DIR"/*.java; do
  [ -f "$shared" ] || continue
  base="$(basename "$shared" .java)"

  # Names of every @GameTest method in the shared class. A method only counts if the
  # annotation is on the line above it, which is how every test in this repo is written.
  mapfile -t tests < <(grep -A2 "@GameTest(" "$shared" \
    | grep -oE "(public|static)[^(]*\b([a-zA-Z0-9_]+)\(GameTestHelper" \
    | grep -oE "[a-zA-Z0-9_]+\(GameTestHelper" \
    | sed 's/(GameTestHelper//' | sort -u)

  [ "${#tests[@]}" -gt 0 ] || continue
  echo "== $base: ${#tests[@]} shared test(s)"

  # fabric: the shared class must appear in the fabric-gametest entrypoint array. There
  # are no delegates to check, so the whole class registering is all-or-nothing.
  MOD_JSON="$ROOT/fabric/src/main/resources/fabric.mod.json"
  if grep -q "gametest\.$base\"" "$MOD_JSON" 2>/dev/null \
     || grep -q "\.$base\"" "$MOD_JSON" 2>/dev/null; then
    echo "   fabric: registered via entrypoint (all ${#tests[@]} automatically)"
    FABRIC_TOTAL=$((FABRIC_TOTAL + ${#tests[@]}))
  else
    echo "   *** fabric: $base is NOT in the fabric-gametest entrypoint array."
    echo "       None of its ${#tests[@]} tests register on fabric."
    FAILURES=$((FAILURES + 1))
  fi

  for loader in neoforge forge; do
    holder="$(find "$ROOT/$loader/src/main/java" -name "${base}Holder.java" 2>/dev/null | head -1)"
    if [ -z "$holder" ]; then
      echo "   *** $loader has NO holder for $base — every one of its tests is unregistered."
      FAILURES=$((FAILURES + 1))
      continue
    fi

    missing=""
    for t in "${tests[@]}"; do
      # The delegate must both DECLARE the method and CALL the shared one. Declaring it
      # without calling through would run an empty test that passes, which is a greener
      # version of the same lie.
      if ! grep -q "\b$t(GameTestHelper" "$holder"; then
        missing="$missing $t"
      elif ! grep -q "\.$t(helper)\|$base\.$t(helper)" "$holder"; then
        echo "   *** $loader: $t is declared but never calls through to $base."
        FAILURES=$((FAILURES + 1))
      fi
    done

    if [ -n "$missing" ]; then
      echo "   *** $loader is MISSING delegate(s):$missing"
      echo "       These tests compile and will never run. The suite stays green at the"
      echo "       old count, so no existing guard reports them."
      FAILURES=$((FAILURES + 1))
    else
      echo "   $loader: all ${#tests[@]} delegated"
      if [ "$loader" = "neoforge" ]; then NEOFORGE_TOTAL=$((NEOFORGE_TOTAL + ${#tests[@]})); fi
      if [ "$loader" = "forge" ];    then FORGE_TOTAL=$((FORGE_TOTAL + ${#tests[@]})); fi
    fi
  done
done

# The totals must agree. Each loader being internally consistent is not the same as the
# three running the same suite — that is precisely how fabric could run 13 while the
# other two run 12, with every report green.
echo
echo "== registered totals: neoforge=$NEOFORGE_TOTAL fabric=$FABRIC_TOTAL forge=$FORGE_TOTAL"
if [ "$NEOFORGE_TOTAL" != "$FABRIC_TOTAL" ] || [ "$NEOFORGE_TOTAL" != "$FORGE_TOTAL" ]; then
  echo "   *** THE LOADERS DO NOT REGISTER THE SAME NUMBER OF TESTS."
  echo "       Every loader will still report a green run of whatever it registered."
  FAILURES=$((FAILURES + 1))
fi

if [ "$FAILURES" -gt 0 ]; then
  echo
  echo "FAIL: $FAILURES delegation problem(s). A shared test with no delegate is not a test."
  exit 1
fi

echo
echo "PASS: every shared GameTest is delegated by all three loaders."
