#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "$0")/../.." && pwd)"
WORK="${TAVERN_APK_WORK:-${ROOT}/output/android-apk-020}"
VERSION=v0.2.0-rc2
COMMIT=8362bba747fcf84c19e436c3dbb594a0fc388a67
mkdir -p "$WORK"
if [ ! -d "$WORK/dsha/.git" ]; then
  git clone --depth 1 --branch "$VERSION" https://github.com/DSH-APP/DSHA.git "$WORK/dsha"
fi
[ "$(git -C "$WORK/dsha" rev-parse HEAD)" = "$COMMIT" ] || { echo 'DSHA 版本不匹配' >&2; exit 1; }
# 官方 DSHA 只是构建输入，和可安装的酒馆交付包分开放，避免点错。
APK="$WORK/inputs/dsha-0.2.0-rc2.apk"
if [ ! -f "$APK" ]; then
  curl -fL --retry 2 "https://github.com/DSH-APP/DSHA/releases/download/${VERSION}/dsha-0.2.0-rc2.apk" -o "$APK.part"
  mv "$APK.part" "$APK"
fi
: "${JAVA_HOME:?请设置 JDK 17 或更新版本的 JAVA_HOME}"
: "${ANDROID_HOME:?请设置 Android SDK 的 ANDROID_HOME}"
BUILD="$(mktemp -d "${WORK}/build.XXXXXX")"
rmdir "$BUILD"
npm ci --prefix "$WORK/dsha/tools/web-compat" --ignore-scripts --no-audit --no-fund
# 首装离线包：与即将发布的源码同一个提交，首装才不必联网下载依赖。
OFFLINE_COMMIT="$(git -C "$ROOT" rev-parse HEAD)"
OFFLINE_PKG="${TAVERN_OFFLINE_PKG:-$WORK/tavern-offline-${OFFLINE_COMMIT:0:12}.tar.gz}"
if [ "${TAVERN_APK_OFFLINE:-1}" = 0 ]; then
  echo 'TAVERN_APK_OFFLINE=0：生成本次安装包不带依赖（首装回到联网下载）。' >&2
  OFFLINE_ARG=()
else
  if [ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ]; then
    echo '警告：工作区有未提交改动，离线包只含已提交内容。' >&2
  fi
  bash "$ROOT/android/apk/build-offline-bundle.sh" "$OFFLINE_COMMIT" "$OFFLINE_PKG"
  OFFLINE_ARG=(--offline "$OFFLINE_PKG")
fi
python3 "$ROOT/android/apk/prepare.py" --source "$WORK/dsha" --apk "$APK" --output "$BUILD" "${OFFLINE_ARG[@]}"
node "$ROOT/android/apk/build-web-compat.mjs" "$WORK/dsha" "$BUILD/app/src/tavern/assets"
cd "$BUILD"
MODE="${TAVERN_APK_MODE:-Debug}"
# AGP 会在 user.home/.android 建调试密钥；沙箱里 $HOME 只读，换到构建目录。
# 固定 keystore（仓库内 android/apk/keys/cyber-terminal.jks）：保证每次 CI 产物签名一致，
# 升级安装不再 INSTALL_FAILED_UPDATE_INCOMPATIBLE。
USER_HOME="$WORK/.user-home"
mkdir -p "$USER_HOME/.android"
if [ -f "$ROOT/android/apk/keys/cyber-terminal.jks" ]; then
  cp -f "$ROOT/android/apk/keys/cyber-terminal.jks" "$USER_HOME/.android/debug.keystore"
elif [ -f "$HOME/.android/debug.keystore" ]; then
  cp -f "$HOME/.android/debug.keystore" "$USER_HOME/.android/"
fi
case "$MODE" in
  Debug)
    TASKS=(":app:assembleStandardDebug")
    [ "${TAVERN_APK_SKIP_TESTS:-0}" = 1 ] || TASKS+=(":app:testStandardDebugUnitTest")
    ./gradlew "${TASKS[@]}" --console=plain \
      --dependency-verification=lenient -Duser.home="$USER_HOME"
    SOURCE="$BUILD/app/build/outputs/apk/standard/debug/app-standard-debug.apk"
    ;;
  Release)
    : "${DSHA_KEYSTORE:?发布包必须指定独立的酒馆签名密钥 DSHA_KEYSTORE}"
    : "${DSHA_KEYSTORE_PASSWORD:?缺少 DSHA_KEYSTORE_PASSWORD}"
    : "${DSHA_KEY_ALIAS:?缺少 DSHA_KEY_ALIAS}"
    : "${DSHA_KEY_PASSWORD:?缺少 DSHA_KEY_PASSWORD}"
    # 上游的 verifyPublishCertificate 只认 DSHA 历史 E7E3 证书；酒馆包先用未签名变体
    # 打包，再用自己的长期密钥签名，避免伪造或改动上游的证书校验。
    TASKS=(":app:assembleStandardRelease")
    [ "${TAVERN_APK_SKIP_TESTS:-0}" = 1 ] || TASKS+=(":app:testStandardReleaseUnitTest")
    ./gradlew -Pdsha.unsignedPackage=true "${TASKS[@]}" --console=plain \
      --dependency-verification=lenient -Duser.home="$USER_HOME"
    UNSIGNED="$BUILD/app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk"
    [ -f "$UNSIGNED" ] || { echo "找不到未签名产物：$UNSIGNED" >&2; exit 1; }
    TOOLS="$ANDROID_HOME/build-tools/37.0.0"
    ALIGNED="$BUILD/app-standard-release-aligned.apk"
    SIGNED="$BUILD/app-standard-release-signed.apk"
    "$TOOLS/zipalign" -p -f 4 "$UNSIGNED" "$ALIGNED"
    "$TOOLS/apksigner" sign --ks "$DSHA_KEYSTORE" --ks-pass env:DSHA_KEYSTORE_PASSWORD \
      --ks-key-alias "$DSHA_KEY_ALIAS" --key-pass env:DSHA_KEY_PASSWORD \
      --out "$SIGNED" "$ALIGNED"
    "$TOOLS/apksigner" verify --print-certs "$SIGNED" >/dev/null
    SOURCE="$SIGNED"
    ;;
  *) echo 'TAVERN_APK_MODE 只能为 Debug 或 Release' >&2; exit 1 ;;
esac
python3 - "$SOURCE" "$WORK" "$MODE" <<'PY'
import hashlib, pathlib, shutil, sys
source, work, mode = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]), sys.argv[3].lower()
if not source.is_file():
    raise SystemExit(f'找不到构建产物：{source}')
target = work / f'dsh-tavern-android-{mode}.apk'
shutil.copyfile(source, target)
target.with_suffix('.apk.sha256').write_text(hashlib.sha256(target.read_bytes()).hexdigest() + '  ' + target.name + '\n')
print(target)
PY
