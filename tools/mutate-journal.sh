#!/usr/bin/env bash
#
# Proves the journal's tests fail when the journal is broken.
#
# A passing suite says nothing on its own: a test that asserts a torn state gets repaired is
# equally green against code that never tore it. This script breaks the write-ahead journal in
# four specific, individually meaningful ways and asserts that a named test notices each one. It
# exists because "proven to fail two ways" was previously a claim in a changelog with nothing in
# the repository that reproduced it — a review correctly refused to accept that, and was right to.
#
#   ./tools/mutate-journal.sh          run every mutation
#   ./tools/mutate-journal.sh 2        run one
#
# The source file is restored from a backup and compared byte-for-byte afterwards, including on
# interrupt. If the comparison ever fails the script says so loudly rather than exiting green.
#
# WHAT THIS CANNOT PROVE — the evidence ceiling (§20.4).
# Crashes here are simulated in-process by throwing at an injected point. That proves the ORDER of
# operations and that recovery repairs what a stopped process leaves. It does not prove DURABILITY:
# deleting any force() or forceDirectory() call would leave all these tests green, because nothing
# here loses power. The fsync calls in JournalService are argued for, not demonstrated. Treating a
# green run as evidence of crash-safety under real power loss would overstate it.

set -uo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SRC="$ROOT/common/src/main/java/com/mwtstudios/nexuscore/storage/JournalService.java"
readonly BACKUP="$(mktemp)"
readonly GRADLE_DIR="$ROOT/neoforge"

cp "$SRC" "$BACKUP"

restore() {
    cp "$BACKUP" "$SRC"
    if ! cmp -s "$BACKUP" "$SRC"; then
        echo "FATAL: $SRC was not restored. Recover it from $BACKUP before doing anything else." >&2
        exit 2
    fi
    rm -f "$BACKUP"
}
trap restore EXIT INT TERM

# mutate <python-expression-file> — rewrites SRC, returns non-zero if the anchor is missing.
apply_mutation() {
    python3 - "$SRC" "$1" <<'PY'
import sys, pathlib
path, which = pathlib.Path(sys.argv[1]), sys.argv[2]
s = path.read_text()

if which == "1":
    # Remove the write-ahead property itself: apply first, journal the record afterwards.
    old = "        store.write(recordName(id), record);\n\n        try {\n            apply(record);"
    new = "        try {\n            apply(record);\n            store.write(recordName(id), record);"
elif which == "2":
    # Remove the already-applied skip, so replay is no longer idempotent.
    old = "            if (!Files.isRegularFile(staging)) {\n                continue;\n            }\n"
    new = ""
elif which == "3":
    # Restore the quarantining read: an unreadable record is renamed out of the *.json listing
    # before the refusal, so the next start sees no pending work.
    old = "                    records.add(parseRecord(file));"
    new = "                    records.add(store.read(JOURNAL_DIRECTORY + \"/\" + name, Record.class, Record::new));"
elif which == "4":
    # Remove the post-loop injection point, making the final crash window unreachable again.
    old = "        beforeEachEntry.accept(record.entries.size());"
    new = ""
else:
    sys.exit(f"unknown mutation {which}")

if old not in s:
    sys.exit(f"anchor for mutation {which} not found; the source has moved on and this script needs updating")
path.write_text(s.replace(old, new, 1))
PY
}

describe() {
    case "$1" in
        1) echo "record written AFTER apply — the write-ahead property removed" ;;
        2) echo "already-applied skip removed — replay no longer idempotent" ;;
        3) echo "quarantining read restored — refusal self-destructs on restart" ;;
        4) echo "post-loop injection point removed — final crash window unreachable" ;;
    esac
}

run_one() {
    local id="$1"
    echo
    echo "=== mutation $id: $(describe "$id") ==="

    cp "$BACKUP" "$SRC"
    if ! apply_mutation "$id"; then
        echo "  SKIPPED — anchor missing"
        return 1
    fi

    # --rerun-tasks is load-bearing. Gradle can serve :test from cache for mutated source,
    # and a cached PASS against broken code is a mutation harness reporting a gap that is not
    # there — or worse, a green it did not earn. Reported by the Master Mode session after it
    # lost a whole mutation round to exactly this.
    local output failures
    output="$(cd "$GRADLE_DIR" && ./gradlew --offline --console=plain --rerun-tasks test --tests '*JournalTest*' 2>&1)"
    failures="$(printf '%s' "$output" | grep -cE '^JournalTest > .* FAILED')"

    if [ "$failures" -eq 0 ]; then
        echo "  *** NOT DETECTED *** the suite passed against broken code. This mutation is a real gap."
        printf '%s\n' "$output" | grep -E '^BUILD' | sed 's/^/  /'
        return 1
    fi

    echo "  detected: $failures test(s) failed"
    printf '%s' "$output" | grep -E '^JournalTest > .* FAILED' | sed 's/^JournalTest > /    - /;s/ FAILED$//'
    return 0
}

echo "Journal mutation testing — each mutation must be caught by at least one test."
echo "Source: $SRC"

mutations=("$@")
if [ "${#mutations[@]}" -eq 0 ]; then
    mutations=(1 2 3 4)
fi

undetected=0
for id in "${mutations[@]}"; do
    run_one "$id" || undetected=$((undetected + 1))
done

echo
if [ "$undetected" -gt 0 ]; then
    echo "RESULT: $undetected mutation(s) survived. The suite does not constrain the journal as claimed."
    exit 1
fi
echo "RESULT: every mutation was detected."
echo "Reminder: this proves ordering and recovery, NOT durability. See the header."
