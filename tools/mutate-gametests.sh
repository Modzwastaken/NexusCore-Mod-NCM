#!/usr/bin/env bash
#
# Proves tools/verify-gametests.sh fires when a loader registers NOTHING.
#
#   ./tools/mutate-gametests.sh            every loader
#   ./tools/mutate-gametests.sh forge      one
#
# WHY THIS EXISTS.
#
# verify-gametests.sh has one job: refuse a run that executed zero tests. That job is
# only worth anything if the refusal has been SEEN. A zero-test guard nobody has
# watched trigger is the same species as the check that silently vanishes — it reads
# like protection and has never been asked to protect anything.
#
# The guard has fired three times for real, on genuine regressions. That is evidence,
# but it is incidental evidence: it depended on a defect existing. This makes the
# proof repeatable and deliberate, which is the standard the rest of this repo holds
# (see mutate-journal.sh and mutate-storage.sh).
#
# WHAT IT DOES. For each loader it un-registers the tests the way a real mistake
# would — the registration is severed, the tests still compile and still exist — then
# asserts verify-gametests.sh goes RED. Source is restored from a backup and compared
# byte-for-byte, including if you interrupt it.
#
# THREE PROOFS, not one. Each pins a different way the gate could be worthless.
#
#   1. ANNOTATION SEVERED -> guard RED.        Registration dies, the guard notices.
#   2. SHIM SEVERED       -> guard GREEN at 12. The redundancy is real, not folklore.
#   3. ASSERTION MUTATED  -> guard RED on the FAILURE branch, not the zero branch.
#
# Why 2 exists. The holders are discovered twice: by @GameTestHolder annotation scan,
# and by the explicit event.register(...) call in NexusGameTestRegistration. Proof 1
# cuts the annotation. Proof 2 cuts the other side and asserts the run still executes
# EXACTLY 12 — the count, not merely "still green". Asserting green alone would be
# satisfied by a run that quietly dropped to 3, which is the same green-run-of-fewer
# this repo keeps rediscovering. Only neoforge and forge have a shim; fabric's single
# registration path is its entrypoint list, which proof 1 already cuts.
#
# Why 3 exists. verify-gametests.sh has a branch for "N required tests failed" that had
# never fired for real — every observed firing was the zero-test branch. A branch nobody
# has watched run is exactly what this script exists to distrust, so it is made to run:
# one assertion is inverted, and the guard must go red AND name a failure rather than an
# empty run. That also settles, empirically rather than by reading, that the count the
# guard trusts describes tests that EXECUTED.
#
# Authorship: proofs 2 and 3 are Master Mode #1's amendment on Manager A's proposal,
# implemented by Master Agent B under the 1.21.1/.gradle-build-lock protocol.

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# REFUSE TO START IF A PREVIOUS RUN LEFT THE TREE MUTATED.
#
# The EXIT/INT/TERM trap below restores everything — unless the process is killed
# hard. SIGKILL runs no trap, and an outer `timeout` or a CI step limit will deliver
# one: on 2026-07-29 a 10-minute cap killed a run mid-Forge and left
# NexusWorldGameTestsHolder.java carrying a commented-out @GameTestHolder. The tree
# looked fine to a casual glance and would have made the NEXT run measure a loader
# that was already sabotaged — a mutation harness poisoning the baseline it exists to
# test against. Checked here because a clean start is a precondition, not a courtesy.
if git -C "$ROOT" status --porcelain 2>/dev/null | grep -q .; then
  # tools/ is excluded because this script and its siblings contain the marker string
  # as literal text — scanning them reports the scanner itself, which is a false
  # positive of exactly the kind this file keeps finding elsewhere.
  leftover=$(git -C "$ROOT" diff --name-only 2>/dev/null | grep -v '^tools/' \
             | xargs -r grep -ln "MUTATED" 2>/dev/null)
  if [ -n "$leftover" ]; then
    echo "REFUSING: the working tree still carries MUTATED markers from an earlier run."
    echo "$leftover" | sed 's/^/  /'
    echo "  Restore them first:  git -C $ROOT checkout -- <file>"
    exit 2
  fi
  echo "note: working tree is dirty (no MUTATED markers). Proceeding, but a mutation"
  echo "      run against uncommitted changes proves things about a state nobody else has."
fi
BACKUP="$(mktemp -d)"
LOADERS="${1:-neoforge fabric forge}"
FAILURES=0

restore() {
  for f in "$BACKUP"/*.bak; do
    [ -e "$f" ] || continue
    target="$(cat "${f%.bak}.path")"
    cp "$f" "$target"
    if cmp -s "$f" "$target"; then
      echo "   restored byte-identical: ${target#"$ROOT"/}"
    else
      echo "   *** RESTORE MISMATCH: ${target#"$ROOT"/} — CHECK THIS FILE ***"
    fi
  done
  rm -rf "$BACKUP"
}
trap restore EXIT INT TERM

back_up() {
  local path="$1" key
  key="$(echo "$path" | md5sum | cut -c1-8)"
  cp "$path" "$BACKUP/$key.bak"
  echo "$path" > "$BACKUP/$key.path"
}

# Severs registration without touching the tests themselves, so what is being proven
# is "the guard notices an empty run", not "the guard notices a compile error".
sever() {
  case "$1" in
    neoforge|forge)
      # Sever @GameTestHolder on the holders, NOT the explicit event.register call.
      #
      # This distinction cost a false alarm on 2026-07-29. The first version of this
      # script commented out event.register(...) and reported that the guard "failed to
      # fire" on both loaders. It had not: the holders carry @GameTestHolder, which the
      # loader discovers by annotation scan independently of any explicit call, so all
      # 12 tests still registered and the guard passed a run that genuinely had 12
      # tests. The mutation had not created the state it claimed to — the same mistake
      # this repo keeps finding, in the tool built to find it.
      #
      # The annotation is the right cut because it is what actually shipped broken:
      # without it the namespace falls back to "minecraft", the enabledGameTestNamespaces
      # filter drops every test, and the run executes nothing while exiting 0.
      local found=0
      for holder in "$ROOT/$1"/src/main/java/com/mwtstudios/nexuscore/gametest/*Holder.java; do
        [ -f "$holder" ] || continue
        back_up "$holder"
        sed -i 's/^@GameTestHolder(/\/\/ MUTATED @GameTestHolder(/' "$holder"
        found=1
      done
      [ "$found" = 1 ] || { echo "   no holder classes for $1 — skipping"; return 1; }
      ;;
    fabric)
      local mod="$ROOT/fabric/src/main/resources/fabric.mod.json"
      [ -f "$mod" ] || { echo "   no fabric.mod.json — skipping"; return 1; }
      back_up "$mod"
      python3 - "$mod" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, encoding='utf-8') as fh:
    data = json.load(fh)
# Empty the entrypoint list rather than deleting the key: a missing key and an empty
# one are different mistakes, and the empty one is the quieter of the two.
if 'entrypoints' in data and 'fabric-gametest' in data['entrypoints']:
    data['entrypoints']['fabric-gametest'] = []
with open(path, 'w', encoding='utf-8') as fh:
    json.dump(data, fh, indent=2)
    fh.write('\n')
PY
      ;;
    *) echo "unknown loader: $1" >&2; return 1 ;;
  esac
}

# Severs ONLY the explicit event.register(...) shim, leaving @GameTestHolder intact.
# The mirror image of sever(): this must NOT change the outcome, because the annotation
# scan is supposed to cover the same ground on its own. neoforge and forge only.
sever_shim_only() {
  local reg="$ROOT/$1/src/main/java/com/mwtstudios/nexuscore/gametest/NexusGameTestRegistration.java"
  [ -f "$reg" ] || { echo "   no registration shim for $1 — skipping"; return 1; }
  back_up "$reg"
  sed -i 's|^\( *\)event\.register(|\1// MUTATED: event.register(|' "$reg"
  grep -q "// MUTATED: event.register(" "$reg" || {
    echo "   *** mutation did not apply to $1 — the shim's shape changed; fix this script"
    return 1
  }
}

# Inverts one assertion in a shared test so exactly one test FAILS while all 12 still
# register and run. Cuts at the condition, not at succeed()/fail(), so the test still
# executes its body — the point is a failing execution, not an absent one.
mutate_assertion() {
  local t="$ROOT/common/src/main/java/com/mwtstudios/nexuscore/gametest/NexusGameTests.java"
  [ -f "$t" ] || { echo "   no shared test file — skipping"; return 1; }
  back_up "$t"
  sed -i '0,/helper\.assertTrue(services() != null,/s//helper.assertTrue(services() == null,/' "$t"
  grep -q "helper\.assertTrue(services() == null," "$t" || {
    echo "   *** mutation did not apply — harnessRuns' assertion changed shape; fix this script"
    return 1
  }
}

for loader in $LOADERS; do
  echo
  echo "=== $loader: severing registration, expecting the guard to go RED"
  if ! sever "$loader"; then continue; fi

  if "$ROOT/tools/verify-gametests.sh" "$loader" > /dev/null 2>&1; then
    echo "   *** NOT DETECTED — the guard PASSED a run that registered nothing."
    echo "       That is the defect the guard exists for, present in the guard itself."
    FAILURES=$((FAILURES + 1))
  else
    echo "   detected: guard failed the empty run, as it must"
  fi

  restore
  trap restore EXIT INT TERM
  BACKUP="$(mktemp -d)"
done

# ---- PROOF 2: the shim is redundant, and the redundancy is REAL -------------------
for loader in $LOADERS; do
  case "$loader" in fabric) continue ;; esac   # no shim to sever
  echo
  echo "=== $loader: severing the SHIM only, expecting GREEN at exactly 12"
  if ! sever_shim_only "$loader"; then
    restore; trap restore EXIT INT TERM; BACKUP="$(mktemp -d)"; FAILURES=$((FAILURES + 1)); continue
  fi

  out="$("$ROOT/tools/verify-gametests.sh" "$loader" --expect 12 2>&1)"; rc=$?
  if [ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q "executed 12 test(s)"; then
    echo "   redundancy holds: annotation scan alone executed 12"
  else
    echo "   *** REDUNDANCY IS NOT REAL — with the shim severed, $loader did not run 12."
    echo "       The belt-and-braces comment in NexusGameTestRegistration would be false,"
    echo "       and one of the two registration paths is load-bearing after all. exit=$rc"
    printf '%s\n' "$out" | tail -6 | sed 's/^/       /'
    FAILURES=$((FAILURES + 1))
  fi

  restore
  trap restore EXIT INT TERM
  BACKUP="$(mktemp -d)"
done

# ---- PROOF 3: the FAILURE branch fires, and is distinguishable from the zero branch
for loader in $LOADERS; do
  echo
  echo "=== $loader: inverting one assertion, expecting RED naming a FAILURE"
  if ! mutate_assertion; then
    restore; trap restore EXIT INT TERM; BACKUP="$(mktemp -d)"; FAILURES=$((FAILURES + 1)); continue
  fi

  out="$("$ROOT/tools/verify-gametests.sh" "$loader" --expect 12 2>&1)"; rc=$?
  if [ "$rc" -ne 0 ] && printf '%s' "$out" | grep -qE "FAIL: [0-9]+ test\(s\) failed"; then
    echo "   detected: $(printf '%s' "$out" | grep -oE 'FAIL: [0-9]+ test\(s\) failed on [a-z]+' | head -1)"
    echo "   took the FAILURE branch, not the zero-test branch — which is the whole point:"
    echo "   the count the guard trusts describes tests that EXECUTED and were judged."
  elif [ "$rc" -ne 0 ]; then
    echo "   *** RED, but NOT on the failure branch. The guard cannot tell a failing run"
    echo "       from an empty one, which is a defect in the guard itself:"
    printf '%s\n' "$out" | head -6 | sed 's/^/       /'
    FAILURES=$((FAILURES + 1))
  else
    echo "   *** NOT DETECTED — a genuinely failing test passed the guard. The gate is"
    echo "       reporting green on a suite that did not hold."
    FAILURES=$((FAILURES + 1))
  fi

  restore
  trap restore EXIT INT TERM
  BACKUP="$(mktemp -d)"
done

echo
if [ "$FAILURES" -gt 0 ]; then
  echo "RESULT: $FAILURES proof(s) did not hold — the guard does not constrain what it claims"
  exit 1
fi
echo "RESULT: all three proofs held — empty runs refused, redundancy real, failures named"
