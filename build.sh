#!/usr/bin/env bash
# Builds PadBridge.apk without the Android Gradle plugin, using Ubuntu's packaged tools:
#   sudo apt-get install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23
# plus, from Maven Central, Robolectric's Android 14 framework jar to compile against and
# Shizuku's AIDL classes (deps/shizuku-aidl-13.1.5.jar):
#   https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/
# (The app also builds normally in Android Studio via the Gradle files in this folder.)
set -euo pipefail
cd "$(dirname "$0")"

ANDROID_ALL=${ANDROID_ALL:-deps/android-all-14-robolectric-10818077.jar}
PLATFORM_JAR=${PLATFORM_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
SHIZUKU_AIDL=${SHIZUKU_AIDL:-deps/shizuku-aidl-13.1.5.jar}  # classes.jar of dev.rikka.shizuku:aidl:13.1.5 (Maven Central)
KEYSTORE=${KEYSTORE:-keystore/padbridge.jks}
SRC=app/src/main

# Fetch the two compile-time jars from Maven Central if they aren't here yet.
fetch() { [ -f "$1" ] || { mkdir -p "$(dirname "$1")" && curl -sSfL -o "$1" "$2"; }; }
fetch "$ANDROID_ALL" https://repo1.maven.org/maven2/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
if [ ! -f "$SHIZUKU_AIDL" ]; then
    fetch deps/shizuku-aidl-13.1.5.aar https://repo1.maven.org/maven2/dev/rikka/shizuku/aidl/13.1.5/aidl-13.1.5.aar
    unzip -p deps/shizuku-aidl-13.1.5.aar classes.jar > "$SHIZUKU_AIDL"
fi
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/gen" "$OUT/classes"

# The app shows this version's release notes in Settings > Updates.
mkdir -p "$OUT/assets" && cp release/notes.md "$OUT/assets/release-notes.md"

aapt package -f -m -J "$OUT/gen" -M "$SRC/AndroidManifest.xml" -S "$SRC/res" -A "$OUT/assets" \
    -I "$PLATFORM_JAR" -F "$OUT/base.apk"

javac -nowarn --release 8 -encoding UTF-8 \
    -classpath "$ANDROID_ALL:$SHIZUKU_AIDL" -d "$OUT/classes" \
    $(find "$SRC/java" "$OUT/gen" -name '*.java')

rm -rf "$OUT/aidl" && mkdir -p "$OUT/aidl" && (cd "$OUT/aidl" && unzip -q "../../$SHIZUKU_AIDL" 'moe/*')
dalvik-exchange --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes" "$OUT/aidl"

cp "$OUT/base.apk" "$OUT/unaligned.apk"
(cd "$OUT" && zip -q unaligned.apk classes.dex)
zipalign -f -p 4 "$OUT/unaligned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo "No signing key at $KEYSTORE; creating a new one (APKs signed with it won't update older installs)."
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass padbridge -keypass padbridge \
        -alias padbridge -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=PadBridge, O=PadBridge" >/dev/null
fi
apksigner sign --ks "$KEYSTORE" --ks-pass pass:padbridge --key-pass pass:padbridge \
    --ks-key-alias padbridge --min-sdk-version 31 --out "$OUT/PadBridge.apk" "$OUT/aligned.apk"
apksigner verify "$OUT/PadBridge.apk"
echo "Built $OUT/PadBridge.apk"
