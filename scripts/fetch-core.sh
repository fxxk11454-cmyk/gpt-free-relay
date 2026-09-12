#!/bin/bash
# 下载代理核心到 jniLibs。
#
# 仓库刻意不包含第三方核心二进制（体积约 34MB，且有独立许可证），
# 由使用者自行获取。Xray-core 使用 MPL-2.0 许可证。
set -eu

VERSION="${XRAY_VERSION:-v26.3.27}"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "==> 下载 Xray-core ${VERSION} (android/arm64-v8a)"
URL="https://github.com/XTLS/Xray-core/releases/download/${VERSION}/Xray-android-arm64-v8a.zip"
curl -fL --retry 3 -o "$TMP/xray.zip" "$URL"

echo "==> 解压"
mkdir -p "$TMP/out"
python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$TMP/xray.zip" "$TMP/out"

mkdir -p "$DEST"
cp "$TMP/out/xray" "$DEST/libxray.so"
chmod 644 "$DEST/libxray.so"

echo "==> 完成: $DEST/libxray.so"
ls -la "$DEST/libxray.so"
