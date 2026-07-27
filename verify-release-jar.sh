#!/usr/bin/env bash
# Verify a packaged release jar at runtime on a real dedicated server.
#
#   ./verify-release-jar.sh fabric|forge|neoforge <version>
#
# Installs the ARCHIVED release jar (the exact published bytes), boots the server,
# and drives the §16 substituted-source check through the console:
#
#   nexus version                                          -> reports the version
#   summon armor_stand                                      -> an entity to borrow
#   execute as <armor stand> run nexus version               -> MUST be refused
#   nexus audit verify                                       -> chain intact
#
# The pre-existing jar is moved aside, never deleted.
set -uo pipefail

LOADER="${1:?loader required: fabric|forge|neoforge}"
VERSION="${2:-1.1.0}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
SRV="$ROOT/${LOADER}server"
JAR="NexusCore-${LOADER}-${VERSION}-1.21.1.jar"
ARCHIVED="$ROOT/archived/v${VERSION}/$JAR"
STAMP="$(date +%H%M%S)"
LOG="$SRV/c-verify-${VERSION}-${STAMP}.log"
FIFO="$SRV/.verify-stdin-$STAMP"

export JAVA_HOME="$HOME/.jdks/ms-21.0.11"
export PATH="$JAVA_HOME/bin:$PATH"

[ -f "$ARCHIVED" ] || { echo "FATAL: archived jar not found: $ARCHIVED"; exit 1; }

echo "=== $LOADER v$VERSION ==="

# --- install the archived bytes, preserving whatever was there -----------------
mkdir -p "$SRV/_superseded-jars"
if [ -f "$SRV/mods/$JAR" ]; then
  if cmp -s "$SRV/mods/$JAR" "$ARCHIVED"; then
    echo "installed jar already matches the archive"
  else
    mv "$SRV/mods/$JAR" "$SRV/_superseded-jars/${JAR%.jar}-superseded-$STAMP.jar"
    echo "moved the previously installed jar to _superseded-jars/ (not deleted)"
  fi
fi
cp "$ARCHIVED" "$SRV/mods/$JAR"

A=$(sha256sum "$ARCHIVED" | cut -d' ' -f1)
B=$(sha256sum "$SRV/mods/$JAR" | cut -d' ' -f1)
[ "$A" = "$B" ] || { echo "FATAL: copy mismatch"; exit 1; }
echo "installed bytes sha256 = ${A:0:16}…  (matches archive)"

# --- boot, drive the console, shut down ---------------------------------------
rm -f "$FIFO"; mkfifo "$FIFO"

pushd "$SRV" >/dev/null
case "$LOADER" in
  fabric) ( java -Xmx2G -jar server.jar nogui < "$FIFO" > "$LOG" 2>&1 ) & ;;
  *)      ( ./run.sh nogui < "$FIFO" > "$LOG" 2>&1 ) & ;;
esac
SRVPID=$!
popd >/dev/null

exec 3>"$FIFO"   # hold the write end open so the server does not see EOF

echo -n "waiting for server ready"
READY=0
for _ in $(seq 1 120); do
  if grep -q 'Done (' "$LOG" 2>/dev/null; then READY=1; break; fi
  if ! kill -0 "$SRVPID" 2>/dev/null; then break; fi
  sleep 2; echo -n .
done
echo

if [ "$READY" != 1 ]; then
  echo "FATAL: server never reached ready state — tail:"
  tail -25 "$LOG"
  exec 3>&-; kill "$SRVPID" 2>/dev/null; rm -f "$FIFO"; exit 1
fi

send () { echo "$1" >&3; sleep "${2:-4}"; }
send "nexus version"
send "summon armor_stand"
send "execute as @e[type=armor_stand,limit=1] run nexus version"
send "nexus audit verify"
send "stop" 2

exec 3>&-
for _ in $(seq 1 40); do kill -0 "$SRVPID" 2>/dev/null || break; sleep 2; done
kill -9 "$SRVPID" 2>/dev/null
rm -f "$FIFO"

# --- adjudicate ---------------------------------------------------------------
echo
echo "--- relevant console output ---"
grep -aE 'NexusCore Administration Framework version|Summoned new Armor Stand|NEXUS »|Audit chain|ERROR|Exception' "$LOG" | tail -20

echo
PASS=0; FAIL=0
check () { if grep -qa "$2" "$LOG"; then echo "  PASS  $1"; PASS=$((PASS+1)); else echo "  FAIL  $1"; FAIL=$((FAIL+1)); fi; }
check "version reported as $VERSION"          "NexusCore Administration Framework version $VERSION"
check "armor stand summoned"                  "Summoned new Armor Stand"
# Fabric leaves the raw § colour codes in the console line, so match only the
# uncoloured span that is common to all three loaders.
check "substituted source REFUSED"            "You do not have permission to do that"
check "audit chain intact"                    "chain intact"
if grep -qaE '\[NexusCore.*(ERROR|FATAL)|Exception in thread' "$LOG"; then
  echo "  FAIL  no NexusCore errors"; FAIL=$((FAIL+1))
else
  echo "  PASS  no NexusCore errors"; PASS=$((PASS+1))
fi

echo
echo "RESULT $LOADER v$VERSION: $PASS passed, $FAIL failed   (log: ${LOG#$ROOT/})"
[ "$FAIL" -eq 0 ]
