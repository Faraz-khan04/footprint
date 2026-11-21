#!/usr/bin/env bash
# Type-checks the app's Kotlin without Android Studio: real Android API jar + stand-ins for the few
# AndroidX/coroutines classes the app uses (their signatures mirror the real libraries).
# Checks both editions: "full" (with the Reels service) and "store" (main code only, REELS = false).
# Needs: kotlinc on PATH (or KOTLINC=...), and ANDROID_JAR pointing at an android.jar (API 35+).
# Pass/fail comes from kotlinc's exit code. The full compiler output is always printed, never filtered.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/dev/farazkhan/footprint"
FULL="$HERE/../../app/src/full/java/dev/farazkhan/footprint"   # the "full" edition's extra code
KOTLINC="${KOTLINC:-kotlinc}"
: "${ANDROID_JAR:?set ANDROID_JAR to an android.jar, e.g. \$ANDROID_HOME/platforms/android-36/android.jar}"
STUBS=("$HERE"/Stubs.kt "$HERE"/Work.kt "$HERE"/Core.kt "$HERE"/Coroutines.kt "$HERE"/sync/Mutex.kt "$HERE"/R.kt "$HERE"/gms/*.kt)
TMP="$(mktemp -d)"
sed 's/REELS = true/REELS = false/' "$HERE"/BuildConfig.kt > "$TMP"/BuildConfigStore.kt

fail=0
run() {   # $1 = edition name, rest = files
  local name="$1"; shift
  local log="$TMP/$name.log"
  "$KOTLINC" -jvm-target 17 -cp "$ANDROID_JAR" "${STUBS[@]}" "$@" -d "$TMP/$name.jar" >"$log" 2>&1
  local status=$?
  echo "---- $name edition"
  cat "$log"
  local warnings; warnings=$(grep -c "warning:" "$log" || true)
  if [ "$status" -ne 0 ]; then
    echo "Kotlin type-check FAILED for $name (kotlinc exit $status, $(grep -c "error:" "$log" || true) error lines)"
    fail=1
  else
    echo "Kotlin type-check passed for $name (kotlinc exit 0, $warnings warnings)"
  fi
}
run full "$HERE"/BuildConfig.kt "$SRC"/*.kt "$FULL"/*.kt
run store "$TMP"/BuildConfigStore.kt "$SRC"/*.kt
rm -rf "$TMP"
exit $fail
