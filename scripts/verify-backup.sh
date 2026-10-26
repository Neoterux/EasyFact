#!/usr/bin/env bash
#
# Copyright (C) 2026 the EasyFact contributors
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License
# along with this program.  If not, see <https://www.gnu.org/licenses/>.
#
# On-device proof that a cloud-backup restore of this app carries no application data.
#
# Gradle can assert the rule files are default-deny; only a device can show the platform
# honours them. The flow is the official one (bmgr with the local transport): write marker
# files into every domain, back up, uninstall, reinstall to trigger restore-at-install, and
# list what came back.
#
# A POSITIVE CONTROL RUNS FIRST. It builds the same app from a throwaway git worktree whose
# copy of the extraction rules includes everything, and requires the markers to come back.
# If they do not, the harness is broken and the real case below it would be meaningless: a
# transport that silently restores nothing would otherwise make default-deny look like a pass.
#
# Usage: scripts/verify-backup.sh --serial <emulator-serial>
#
# The serial is mandatory and must be an emulator. Use a throwaway AVD, never one you have open
# for other work: the script uninstalls and reinstalls the app and rewrites backup state.

set -u
set -o pipefail

usage() {
  echo "usage: scripts/verify-backup.sh --serial <emulator-serial>" >&2
}

die() {
  echo "ERROR: $*" >&2
  exit 1
}

SERIAL=""
while [ $# -gt 0 ]; do
  case "$1" in
    --serial)
      [ $# -ge 2 ] || { usage; echo "refusing: --serial is required" >&2; exit 2; }
      SERIAL="$2"
      shift 2
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage
      echo "unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

# Never fall back to adb's default device: that is how an unattended run ends up driving the
# working emulator or a real phone.
if [ -z "$SERIAL" ]; then
  usage
  echo "refusing: --serial is required (this script never uses adb's default device)" >&2
  exit 2
fi
case "$SERIAL" in
  emulator-[0-9]*) ;;
  *)
    echo "refusing: '$SERIAL' is not an emulator serial (expected emulator-<port>); physical devices are never targeted" >&2
    exit 2
    ;;
esac

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd) || die "cannot resolve the repository root"
cd "$REPO_ROOT" || die "cannot enter $REPO_ROOT"

# --- Tooling --------------------------------------------------------------------------------

find_adb() {
  if [ -n "${ADB:-}" ] && [ -x "$ADB" ]; then echo "$ADB"; return 0; fi
  if command -v adb >/dev/null 2>&1; then command -v adb; return 0; fi
  local sdk
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" \
    "$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" 2>/dev/null | head -n 1)"; do
    if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then echo "$sdk/platform-tools/adb"; return 0; fi
  done
  return 1
}

ADB_BIN=$(find_adb) || die "adb not found: put it on PATH, set ADB, or set ANDROID_HOME / sdk.dir"
[ -x "$REPO_ROOT/gradlew" ] || die "Gradle wrapper not found or not executable at $REPO_ROOT/gradlew"
command -v git >/dev/null 2>&1 || die "git not found"

# A usable JDK is needed by the wrapper. Fall back to the JDK bundled with Android Studio.
if [ -z "${JAVA_HOME:-}" ]; then
  for candidate in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$HOME/android-studio/jbr"; do
    if [ -x "$candidate/bin/java" ]; then JAVA_HOME="$candidate"; export JAVA_HOME; break; fi
  done
fi

adbx() { "$ADB_BIN" -s "$SERIAL" "$@"; }

APP_ID=$(sed -n 's/^[[:space:]]*applicationId[[:space:]]*=[[:space:]]*"\(.*\)"[[:space:]]*$/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -n 1)
[ -n "$APP_ID" ] || die "could not read applicationId from app/build.gradle.kts"

LOCAL_TRANSPORT="com.android.localtransport/.LocalTransport"

# Marker files written into each backup domain. no_backup/ is excluded by the platform in every
# case, so it is reported separately and must never come back, control or not.
MARKERS="databases/marker.db files/certs/marker-cert.bin files/datastore/marker.pb shared_prefs/marker.xml"
NO_BACKUP_MARKER="no_backup/marker.bin"

# --- Device guards --------------------------------------------------------------------------

state=$(adbx get-state 2>/dev/null || true)
[ "$state" = "device" ] || die "$SERIAL is not an attached, ready device (state: '${state:-none}')"

qemu_flag=$(adbx shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')
boot_qemu=$(adbx shell getprop ro.boot.qemu 2>/dev/null | tr -d '\r')
if [ "$qemu_flag" != "1" ] && [ "$boot_qemu" != "1" ]; then
  echo "refusing: $SERIAL does not report itself as an emulator (ro.kernel.qemu='$qemu_flag', ro.boot.qemu='$boot_qemu')" >&2
  exit 2
fi

boot_done=$(adbx shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
[ "$boot_done" = "1" ] || die "$SERIAL has not finished booting"

# --- Throwaway worktree for the positive control --------------------------------------------

WORK_DIR=$(mktemp -d "${TMPDIR:-/tmp}/verify-backup.XXXXXX") || die "mktemp failed"
CONTROL_TREE="$WORK_DIR/control-tree"

cleanup() {
  # Remove only what this script created. `git worktree remove --force` deletes the checkout
  # including its build output; the now-empty parent is removed with a non-recursive rmdir.
  if [ -d "$CONTROL_TREE" ]; then
    git -C "$REPO_ROOT" worktree remove --force "$CONTROL_TREE" >/dev/null 2>&1
  fi
  git -C "$REPO_ROOT" worktree prune >/dev/null 2>&1
  rmdir "$WORK_DIR" >/dev/null 2>&1
}
trap cleanup EXIT

if [ -n "$(git -C "$REPO_ROOT" status --porcelain --untracked-files=no)" ]; then
  echo "NOTE: the working tree has uncommitted changes to tracked files; they are carried into the control tree so the two builds differ only in the extraction rules."
fi
if [ -n "$(git -C "$REPO_ROOT" status --porcelain | grep '^??' || true)" ]; then
  echo "NOTE: untracked files are not carried into the control tree."
fi

echo "== Building the real APK from the working tree"
"$REPO_ROOT/gradlew" -p "$REPO_ROOT" :app:assembleDebug --console=plain -q || die "real build failed"
REAL_APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$REAL_APK" ] || die "expected APK not found: $REAL_APK"

echo "== Creating the throwaway worktree for the positive control"
git -C "$REPO_ROOT" worktree add --detach "$CONTROL_TREE" HEAD >/dev/null 2>&1 || die "git worktree add failed"
git -C "$REPO_ROOT" diff HEAD --binary | git -C "$CONTROL_TREE" apply --allow-empty - || die "could not carry working-tree changes into the control tree"
if [ -f "$REPO_ROOT/local.properties" ]; then
  command cp -f "$REPO_ROOT/local.properties" "$CONTROL_TREE/local.properties" || die "could not copy local.properties"
fi
# Include everything: empty blocks mean "back up all domains". Only the worktree copy is
# rewritten; the owner's working tree is never patched.
cat > "$CONTROL_TREE/app/src/main/res/xml/data_extraction_rules.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup />
    <device-transfer />
</data-extraction-rules>
XML

echo "== Building the control APK (extraction rules include everything)"
"$CONTROL_TREE/gradlew" -p "$CONTROL_TREE" :app:assembleDebug --console=plain -q || die "control build failed"
CONTROL_APK="$CONTROL_TREE/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$CONTROL_APK" ] || die "expected APK not found: $CONTROL_APK"

# --- The backup / restore cycle -------------------------------------------------------------

app_files() {
  adbx shell "run-as $APP_ID find . -type f" 2>/dev/null | tr -d '\r' | sed 's|^\./||'
}

count_markers() {
  # Prints how many of the listed marker paths appear in the file listing on stdin.
  local listing found=0 marker
  listing=$(cat)
  for marker in $MARKERS; do
    if printf '%s\n' "$listing" | grep -Fx "$marker" >/dev/null; then found=$((found + 1)); fi
  done
  echo "$found"
}

# Runs one full cycle with the given APK and sets LAST_LISTING / LAST_RESTORED / LAST_NO_BACKUP.
run_cycle() {
  local label="$1" apk="$2" marker out i
  echo "-- [$label] clean slate"
  adbx shell pm uninstall --user 0 "$APP_ID" >/dev/null 2>&1
  adbx shell bmgr wipe "$LOCAL_TRANSPORT" "$APP_ID" 2>&1 | sed 's/^/   wipe: /'

  echo "-- [$label] install and write markers"
  out=$(adbx install -r -t "$apk" 2>&1 | tr -d '\r' | tail -n 1)
  [ "$out" = "Success" ] || die "[$label] install failed: $out"
  adbx shell "run-as $APP_ID sh -c 'mkdir -p databases files/certs files/datastore shared_prefs no_backup && echo synthetic > databases/marker.db && echo synthetic > files/certs/marker-cert.bin && echo synthetic > files/datastore/marker.pb && echo synthetic > shared_prefs/marker.xml && echo synthetic > $NO_BACKUP_MARKER'" \
    || die "[$label] could not write markers (is this a debuggable build?)"
  for marker in $MARKERS $NO_BACKUP_MARKER; do
    app_files | grep -Fx "$marker" >/dev/null || die "[$label] marker $marker was not written"
  done

  # A freshly installed app that has never been launched is in the "stopped" state, and the
  # backup manager then answers "Backup is not allowed" regardless of the rules. This app has no
  # launcher activity to start, so clear the state explicitly; otherwise default-deny would look
  # like a pass for the wrong reason.
  adbx shell cmd package unstop --user 0 "$APP_ID" 2>&1 | tr -d '\r' | sed 's/^/   unstop: /'

  echo "-- [$label] bmgr backupnow"
  adbx shell bmgr backupnow "$APP_ID" 2>&1 | tr -d '\r' | sed 's/^/   backupnow: /'

  echo "-- [$label] uninstall, reinstall (restore-at-install)"
  adbx shell pm uninstall --user 0 "$APP_ID" >/dev/null 2>&1 || die "[$label] uninstall failed"
  out=$(adbx install -r -t "$apk" 2>&1 | tr -d '\r' | tail -n 1)
  [ "$out" = "Success" ] || die "[$label] reinstall failed: $out"

  # Restore runs asynchronously after install. Poll for the markers, but never stop early on
  # "nothing found": an empty listing is exactly what the real case expects, so it is read only
  # after the full wait.
  i=0
  while [ "$i" -lt 15 ]; do
    sleep 2
    if [ "$(app_files | count_markers)" -ge 4 ]; then break; fi
    i=$((i + 1))
  done

  LAST_LISTING=$(app_files)
  LAST_RESTORED=$(printf '%s\n' "$LAST_LISTING" | count_markers)
  if printf '%s\n' "$LAST_LISTING" | grep -Fx "$NO_BACKUP_MARKER" >/dev/null; then LAST_NO_BACKUP=yes; else LAST_NO_BACKUP=no; fi
  echo "-- [$label] files present after reinstall:"
  if [ -n "$LAST_LISTING" ]; then printf '%s\n' "$LAST_LISTING" | sed 's/^/   /'; else echo "   (none)"; fi
}

print_limits() {
  cat <<'LIMITS'

What this run does NOT prove (stated plainly, not as a footnote):
  1. Device-to-device transfer is UNVERIFIED. The research restore did not materialise even under
     an include-all positive control, on API 37 only. There is no known automated or reliable
     manual proof; what is delivered for that path is the structural assertion
     (verify<Variant>BackupRules) and nothing more.
  2. Only API 37 was measured. Behaviour on API 31 through 36 and on manufacturer builds is
     assumed, not shown. One smoke run per supported major version is owed before the first release.
  3. Phase 1 has no Room database and no certificate directory yet. "A restore carries neither the
     Room database nor the certificate directory" is proven here structurally (default-deny covers
     every future path) and with marker files standing in for the real data. The phase that
     creates the real data is where this claim is exercised end to end.
LIMITS
}

echo "== Selecting the local backup transport on $SERIAL"
adbx shell bmgr enable true 2>&1 | tr -d '\r' | sed 's/^/   enable: /'
transport_out=$(adbx shell bmgr transport "$LOCAL_TRANSPORT" 2>&1 | tr -d '\r')
echo "   transport: $transport_out"
case "$transport_out" in
  *"Selected transport"*) ;;
  *) die "could not select $LOCAL_TRANSPORT; this image has no local transport, so no result below could be trusted" ;;
esac

echo
echo "=== POSITIVE CONTROL (extraction rules include everything; markers MUST come back) ==="
run_cycle "control" "$CONTROL_APK"
CONTROL_RESTORED=$LAST_RESTORED
CONTROL_NO_BACKUP=$LAST_NO_BACKUP
echo "-- control restored $CONTROL_RESTORED of 4 domain markers; no_backup marker restored: $CONTROL_NO_BACKUP"
if [ "$CONTROL_RESTORED" -lt 4 ]; then
  echo "HARNESS BROKEN: the positive control restored $CONTROL_RESTORED of 4 markers. The real case's" >&2
  echo "result is therefore meaningless and is not reported." >&2
  print_limits >&2
  adbx shell pm uninstall --user 0 "$APP_ID" >/dev/null 2>&1
  exit 1
fi
if [ "$CONTROL_NO_BACKUP" = "yes" ]; then
  echo "HARNESS BROKEN: the platform-excluded no_backup marker came back, so the restore is not behaving as documented." >&2
  print_limits >&2
  adbx shell pm uninstall --user 0 "$APP_ID" >/dev/null 2>&1
  exit 1
fi
echo "-- control OK: the harness restores data when the rules allow it."

echo
echo "=== REAL CASE (shipped default-deny rules; NO marker may come back) ==="
run_cycle "real" "$REAL_APK"
REAL_RESTORED=$LAST_RESTORED
REAL_NO_BACKUP=$LAST_NO_BACKUP
adbx shell pm uninstall --user 0 "$APP_ID" >/dev/null 2>&1
adbx shell bmgr wipe "$LOCAL_TRANSPORT" "$APP_ID" >/dev/null 2>&1

echo
if [ "$REAL_RESTORED" -eq 0 ] && [ "$REAL_NO_BACKUP" = "no" ]; then
  echo "RESULT: PASS - the control restored 4 of 4 markers and the shipped default-deny rules restored none."
  print_limits
  exit 0
fi
echo "RESULT: FAIL - the shipped rules let $REAL_RESTORED of 4 markers (no_backup restored: $REAL_NO_BACKUP) come back." >&2
print_limits >&2
exit 1
