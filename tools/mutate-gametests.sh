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

set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
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

echo
if [ "$FAILURES" -gt 0 ]; then
  echo "RESULT: $FAILURES loader(s) NOT detected — the guard does not constrain them"
  exit 1
fi
echo "RESULT: every loader's empty run was refused"
