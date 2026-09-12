#!/bin/bash
# 构建 GPT 中转 APK
set -u
ROOT=/root/rp/gpt-relay
export JAVA_HOME="$ROOT/.toolchain/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$ROOT/sdk"
export ANDROID_SDK_ROOT="$ROOT/sdk"

echo "JAVA : $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo "SDK  : $ANDROID_HOME"
ls "$ANDROID_HOME/platforms" 2>/dev/null
ls "$ANDROID_HOME/build-tools" 2>/dev/null
echo "核心 : $(ls -la "$ROOT/app/src/main/jniLibs/arm64-v8a/libsingbox.so" 2>/dev/null | awk '{print $5}') bytes"
echo

cd "$ROOT"
"$ROOT/.toolchain/gradle/bin/gradle" :app:assembleRelease \
    --no-daemon \
    --stacktrace \
    -Dorg.gradle.jvmargs="-Xmx1280m -XX:MaxMetaspaceSize=512m" \
    2>&1 | tail -60

echo
echo "=== 产物 ==="
find "$ROOT/app/build/outputs" -name '*.apk' -printf '%p  %s bytes\n' 2>/dev/null
