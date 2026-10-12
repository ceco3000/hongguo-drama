#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
TV_JAVA_HOME="${TV_JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
: "${TV_TEST_JSON_JAR:?Set TV_TEST_JSON_JAR to a JVM-compatible org.json jar; test-only, never bundled in APK}"
TV_ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
mkdir -p build/tests
"$TV_JAVA_HOME/bin/javac" --release 8 -Xlint:-options -encoding UTF-8 -cp "$TV_TEST_JSON_JAR:$TV_ANDROID_JAR" -d build/tests \
  app/src/main/java/io/github/jackotom/hongguotv/CatalogClient.java \
  app/src/main/java/io/github/jackotom/hongguotv/PlaybackState.java \
  app/src/main/java/io/github/jackotom/hongguotv/HistoryStore.java tests/*.java
for tv_test in CatalogClientTest PlaybackStateTest HistoryStoreTest; do
    "$TV_JAVA_HOME/bin/java" -cp "build/tests:$TV_TEST_JSON_JAR:$TV_ANDROID_JAR" "io.github.jackotom.hongguotv.$tv_test" "$@"
done
