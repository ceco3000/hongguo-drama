#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/homebrew/share/android-commandlinetools}"
TV_JAVA_HOME="${TV_JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
SDK_TOOLS="$ANDROID_SDK_ROOT/build-tools/36.0.0"
ANDROID_JAR="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
export JAVA_HOME="$TV_JAVA_HOME"
export PATH="$TV_JAVA_HOME/bin:$PATH"
for tool in aapt2 d8 zipalign apksigner; do
    test -x "$SDK_TOOLS/$tool" || { echo "Missing Android build tool: $tool" >&2; exit 1; }
done
test -f "$ANDROID_JAR" || { echo 'Missing android-36 SDK platform' >&2; exit 1; }
mkdir -p build/classes build/dex dist .private
chmod 700 .private
if [ ! -e .private/tv-test.jks ]; then
    umask 077
    python3 -c 'import secrets,pathlib; pathlib.Path(".private/store-password").write_text(secrets.token_urlsafe(36))'
    keytool -genkeypair -keystore .private/tv-test.jks -storepass:file .private/store-password -keypass:file .private/store-password \
        -alias hongguo-tv-test -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Hongguo TV Test, O=Local Test Build' >/dev/null 2>&1
fi
test -f .private/store-password || { echo 'Signing password file missing; preserve the existing keystore.' >&2; exit 1; }
"$SDK_TOOLS/aapt2" compile --dir app/src/main/res -o build/resources.zip
"$SDK_TOOLS/aapt2" link -o build/resources.apk -I "$ANDROID_JAR" --manifest app/src/main/AndroidManifest.xml --auto-add-overlay build/resources.zip
find app/src/main/java -name '*.java' -print > build/sources.txt
javac --release 8 -Xlint:-options -encoding UTF-8 -classpath "$ANDROID_JAR" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
"$SDK_TOOLS/d8" --min-api 21 --lib "$ANDROID_JAR" --output build/dex build/classes.jar
cp build/resources.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes*.dex)
"$SDK_TOOLS/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
TV_APK='dist/红果短剧-TV-1.0-test1.apk'
if [ -e "$TV_APK" ]; then
    mkdir -p build/previous-apks
    cp "$TV_APK" "build/previous-apks/$(date +%Y%m%d-%H%M%S).apk"
fi
"$SDK_TOOLS/apksigner" sign --ks .private/tv-test.jks --ks-key-alias hongguo-tv-test \
    --ks-pass file:.private/store-password --out "$TV_APK" build/aligned.apk
"$SDK_TOOLS/apksigner" verify --verbose "$TV_APK" > build/apk-signature.txt
"$SDK_TOOLS/aapt2" dump badging "$TV_APK" > build/apk-manifest.txt
python3 - <<'PY'
from pathlib import Path
import hashlib, json, zipfile
p = Path('dist/红果短剧-TV-1.0-test1.apk')
m = Path('build/apk-manifest.txt').read_text()
assert "name='io.github.jackotom.hongguotv'" in m
assert "versionCode='1'" in m and "versionName='1.0-tv-test1'" in m
assert "minSdkVersion:'21'" in m
assert 'android.permission.INTERNET' in m
assert "uses-feature: name='android.hardware.touchscreen'" not in m
with zipfile.ZipFile(p) as z:
    assert 'classes.dex' in z.namelist()
    assert z.read('classes.dex') == Path('build/dex/classes.dex').read_bytes(), 'Packaged code differs from compiler output'
    assert not any(n.startswith('lib/') for n in z.namelist()), 'APK should have no ABI-specific libraries'
result = {'apk': p.name, 'bytes': p.stat().st_size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
          'package': 'io.github.jackotom.hongguotv', 'version': '1.0-tv-test1', 'minSdk': 21,
          'signing': 'local dedicated test certificate; apksigner verified', 'deviceTested': False}
Path('build/build-receipt.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
Path('dist/SHA256SUMS.txt').write_text(result['sha256']+'  '+p.name+'\n')
print('APK built and signature verified: '+p.name+' ('+str(result['bytes'])+' bytes)')
PY
