#!/usr/bin/env bash
# 使用 Android SDK build-tools 直接构建 ClipBalFix（无需 Gradle）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$SDK" || ! -d "$SDK" ]]; then
  echo "未找到 Android SDK，请设置 ANDROID_HOME" >&2
  exit 1
fi

BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
[[ -n "$BT" ]] || { echo "未找到 build-tools" >&2; exit 1; }

PLATFORM="$(ls -d "$SDK"/platforms/android-*/ 2>/dev/null | sort -V | tail -1)"
ANDROID_JAR="$PLATFORM/android.jar"
[[ -f "$ANDROID_JAR" ]] || { echo "未找到 android.jar" >&2; exit 1; }

API_JAR="$ROOT/libs/libxposed-api-102.jar"
if [[ ! -f "$API_JAR" ]]; then
  echo "下载 libxposed API 102..."
  mkdir -p "$ROOT/libs"
  TMP_AAR="$(mktemp -d)/api.aar"
  curl -fsSL "https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar" -o "$TMP_AAR"
  unzip -p "$TMP_AAR" classes.jar > "$API_JAR"
fi

WORK="$ROOT/build"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex"

echo "==> javac"
find "$ROOT/app/src/main/java" -name '*.java' > "$WORK/sources.txt"
javac -source 8 -target 8 -nowarn -encoding UTF-8 \
  -classpath "$ANDROID_JAR:$API_JAR" \
  -d "$WORK/classes" @"$WORK/sources.txt"

echo "==> d8"
find "$WORK/classes" -name '*.class' > "$WORK/classes.txt"
"${BT}d8" --release --min-api 34 --lib "$ANDROID_JAR" --lib "$API_JAR" \
  --output "$WORK/dex" $(cat "$WORK/classes.txt")

echo "==> aapt package"
# Gradle 工程里 package 由 namespace 提供；aapt1 需要显式 package 属性，构建时生成临时清单
sed 's/<manifest /<manifest package="com.clipbal.fix" android:versionCode="4" android:versionName="2.2" /' \
  "$ROOT/app/src/main/AndroidManifest.xml" > "$WORK/AndroidManifest.xml"
"${BT}aapt" package -f \
  -M "$WORK/AndroidManifest.xml" \
  -S "$ROOT/app/src/main/res" \
  -I "$ANDROID_JAR" \
  -F "$WORK/base.apk"

echo "==> 打包 META-INF/xposed"
cd "$WORK"
cp "$WORK/dex/classes.dex" .
mkdir -p META-INF/xposed
cp "$ROOT"/app/src/main/resources/META-INF/xposed/* META-INF/xposed/
zip -q -u base.apk classes.dex META-INF/xposed/module.prop META-INF/xposed/java_init.list META-INF/xposed/scope.list

echo "==> zipalign"
"${BT}zipalign" -f -p 4 base.apk aligned.apk

echo "==> 签名"
KEYSTORE="${KEYSTORE:-$ROOT/keystore/release.jks}"
if [[ ! -f "$KEYSTORE" ]]; then
  echo "生成测试签名: $KEYSTORE"
  mkdir -p "$(dirname "$KEYSTORE")"
  keytool -genkeypair -keystore "$KEYSTORE" -alias "${KEY_ALIAS:-clipbal}" -keyalg RSA -keysize 2048 \
    -validity 3650 -storepass "${STORE_PASS:-android}" -keypass "${KEY_PASS:-android}" -dname "CN=ClipBalFix"
fi
mkdir -p "$ROOT/dist"
"${BT}apksigner" sign --ks "$KEYSTORE" \
  --ks-pass "pass:${STORE_PASS:-android}" --key-pass "pass:${KEY_PASS:-android}" \
  --out "$ROOT/dist/ClipBalFix-2.2.apk" aligned.apk

echo "完成: $ROOT/dist/ClipBalFix-2.2.apk"
