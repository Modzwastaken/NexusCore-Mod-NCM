#!/usr/bin/env bash
#
# Proves the storage tests fail when JsonStore's read and move protocols are broken.
#
# Companion to mutate-journal.sh, same shape and same reasoning: a green suite says nothing on its
# own, so each fix is un-fixed on purpose and a named test must notice.
#
#   ./tools/mutate-storage.sh          run every mutation
#   ./tools/mutate-storage.sh 2        run one
#
# WHAT CANNOT BE MUTATED HERE, and why it matters more than what can.
#
# The atomic-move fallback cannot be tested by removing it. Reaching that branch needs a filesystem
# that refuses ATOMIC_MOVE for a same-directory rename, and there is no portable way to produce one
# — which is exactly how a silent fallback survived from M2 to 1.1.4 with §11.1-R4 quietly false
# wherever it fired. The same-directory PRECONDITION is the testable proxy: it is the property that
# makes the move a rename, and a rename is the only thing a filesystem does atomically. Mutation 2
# covers that. The refusal itself is argued, not demonstrated.
#
# forceDirectory's Windows no-op cannot be mutated either — this suite does not run on Windows. It
# is ruled as a named platform limitation on the published defect list rather than tested.

set -uo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SRC="$ROOT/common/src/main/java/com/mwtstudios/nexuscore/storage/JsonStore.java"
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

apply_mutation() {
    python3 - "$SRC" "$1" <<'PY'
import sys, pathlib
path, which = pathlib.Path(sys.argv[1]), sys.argv[2]
s = path.read_text()

if which == "1":
    # Put IOException back in the quarantining catch: a failure to READ is treated as bad content
    # again, so an intact file gets renamed out from under its operator.
    old = "        } catch (IOException | JsonIOException e) {"
    new = "        } catch (JsonIOException e) {"
    if old not in s:
        sys.exit("anchor for mutation 1 not found")
    s = s.replace(old, new, 1)
    old2 = "        } catch (JsonParseException | IllegalStateException e) {"
    new2 = "        } catch (IOException | JsonParseException | IllegalStateException e) {"
    if old2 not in s:
        sys.exit("second anchor for mutation 1 not found")
    s = s.replace(old2, new2, 1)
elif which == "2":
    # Drop the same-directory precondition, so a cross-directory move is attempted instead of refused.
    start = s.index("        if (!Objects.equals(from.getParent(), to.getParent())) {")
    end = s.index("        try {\n            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE", start)
    s = s[:start] + s[end:]
elif which == "3":
    # Move the temp cleanup back inside the IOException catch, where it was until 1.1.4 — so a
    # serialisation failure that is not an IOException leaves its scratch behind again.
    start = s.index("        } finally {\n            // Unconditional, and this is the half")
    end = s.index("            deleteQuietly(temp);\n        }\n    }", start) + len("            deleteQuietly(temp);\n        }\n    }")
    s = s[:start] + "        }\n    }" + s[end:]
    marker = '            throw new StorageException("could not write " + file + " (" + e.getClass().getSimpleName()'
    if marker not in s:
        sys.exit("could not re-add cleanup to the catch")
    s = s.replace(marker, "            deleteQuietly(temp);\n" + marker, 1)
else:
    sys.exit(f"unknown mutation {which}")

path.write_text(s)
PY
}

describe() {
    case "$1" in
        1) echo "IOException back in the quarantining catch — an unreadable file is 'corrupt' again" ;;
        2) echo "same-directory precondition removed — a cross-directory move is attempted" ;;
        3) echo "temp cleanup back inside catch(IOException) — non-IO failures leak scratch again" ;;
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

    local output failures
    output="$(cd "$GRADLE_DIR" && ./gradlew --offline --console=plain test --tests '*StorageTest*' 2>&1)"
    failures="$(printf '%s' "$output" | grep -cE '^StorageTest > .* FAILED')"

    if [ "$failures" -eq 0 ]; then
        echo "  *** NOT DETECTED *** the suite passed against broken code. This mutation is a real gap."
        printf '%s\n' "$output" | grep -E '^BUILD' | sed 's/^/  /'
        return 1
    fi

    echo "  detected: $failures test(s) failed"
    printf '%s' "$output" | grep -E '^StorageTest > .* FAILED' | sed 's/^StorageTest > /    - /;s/ FAILED$//'
    return 0
}

echo "Storage mutation testing — each mutation must be caught by at least one test."
echo "Source: $SRC"

mutations=("$@")
if [ "${#mutations[@]}" -eq 0 ]; then
    mutations=(1 2 3)
fi

undetected=0
for id in "${mutations[@]}"; do
    run_one "$id" || undetected=$((undetected + 1))
done

echo
if [ "$undetected" -gt 0 ]; then
    echo "RESULT: $undetected mutation(s) survived. The suite does not constrain JsonStore as claimed."
    exit 1
fi
echo "RESULT: every mutation was detected."
echo "Reminder: the atomic-move refusal and the Windows dirent no-op are NOT covered here. See the header."
