#!/usr/bin/env bash
# build + sign titan-hooks.apk. needs android build-tools (aapt2 d8 zipalign apksigner),
# a platform android.jar, javac, zip.
set -euo pipefail
cd "$(dirname "$0")"

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
JAR=$(ls "$SDK"/platforms/*/android.jar 2>/dev/null | sort -V | tail -1)
[ -x "${BT:-}/aapt2" ] || { echo "need $SDK/build-tools (aapt2 d8 zipalign apksigner)" >&2; exit 1; }
[ -f "${JAR:-}" ] || { echo "need $SDK/platforms/*/android.jar (paru -S android-platform)" >&2; exit 1; }

rm -rf build && mkdir -p build/classes
umask 077
[ -f key.pass ] || head -c 24 /dev/urandom | base64 > key.pass
PW=$(cat key.pass)
[ -f titan.keystore ] || keytool -genkeypair -keystore titan.keystore -alias titan \
  -keyalg RSA -keysize 2048 -validity 10950 -storepass "$PW" -keypass "$PW" \
  -dname "CN=titan-hooks, O=titan-hooks"

javac --release 11 -Xlint:-options -cp "$JAR" -d build/classes $(find src -name '*.java')
"$BT/d8" --release --min-api 30 --lib "$JAR" --output build $(find build/classes -name '*.class')
"$BT/aapt2" link -o build/unsigned.apk --manifest AndroidManifest.xml -I "$JAR"
(cd build && zip -q -j unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
"$BT/apksigner" sign --ks titan.keystore --ks-key-alias titan \
  --ks-pass "pass:$PW" --key-pass "pass:$PW" --out build/titan-hooks.apk build/aligned.apk
echo "built build/titan-hooks.apk"
