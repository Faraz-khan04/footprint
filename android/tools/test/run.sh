#!/usr/bin/env bash
# JVM tests for the pure logic in Limits.kt, Rules.kt, WidgetFeed.kt, ReelsMatch.kt, WeekMath.kt, FutureYou.kt, NotifyPlan.kt and AlarmGate.kt. Needs kotlinc and ANDROID_JAR, like the type-check.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/dev/farazkhan/footprint"
KOTLINC="${KOTLINC:-kotlinc}"
KOTLINC="$(command -v "$KOTLINC" || echo "$KOTLINC")"   # full path, so the stdlib next to it can be found
: "${ANDROID_JAR:?set ANDROID_JAR to an android.jar (API 35+)}"
OUT="$(mktemp -d)"
"$KOTLINC" -jvm-target 17 -cp "$ANDROID_JAR" "$SRC/Limits.kt" "$SRC/Config.kt" "$SRC/WidgetFeed.kt" "$SRC/Rules.kt" "$SRC/ReelsMatch.kt" "$SRC/WeekMath.kt" "$SRC/FutureYou.kt" "$SRC/NotifyPlan.kt" "$SRC/AlarmGate.kt" "$SRC/StepMerge.kt" "$HERE/LimitsTest.kt" "$HERE/WidgetFeedTest.kt" "$HERE/RulesTest.kt" "$HERE/ReelsMatchTest.kt" "$HERE/FutureYouTest.kt" "$HERE/NotifyPlanTest.kt" "$HERE/AlarmGateTest.kt" "$HERE/StepMergeTest.kt" -d "$OUT/tests.jar" || exit 1
CP="$OUT/tests.jar:$ANDROID_JAR:$(dirname "$KOTLINC")/../lib/kotlin-stdlib.jar"
java -cp "$CP" LimitsTestKt && java -cp "$CP" WidgetFeedTestKt && java -cp "$CP" RulesTestKt && java -cp "$CP" ReelsMatchTestKt && java -cp "$CP" FutureYouTestKt && java -cp "$CP" NotifyPlanTestKt && java -cp "$CP" AlarmGateTestKt && java -cp "$CP" StepMergeTestKt
status=$?
rm -rf "$OUT"
exit $status
