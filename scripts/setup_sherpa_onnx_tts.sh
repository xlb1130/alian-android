#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSET_TARGET_DIR="$PROJECT_ROOT/app/src/main/assets/sherpa-onnx-tts"
JNI_TARGET_ROOT="$PROJECT_ROOT/app/src/main/jniLibs"

MODEL_NAME="matcha-icefall-zh-baker"
SHERPA_ROOT=""
SKIP_MODEL=0
SKIP_LIBS=0
PREBUILT_SHERPA_VERSION="1.12.28"
TMP_MODEL_DIR=""
PRUNE_TN_ASSETS=0

cleanup() {
  if [[ -n "${TMP_MODEL_DIR:-}" && -d "${TMP_MODEL_DIR:-}" ]]; then
    rm -rf "$TMP_MODEL_DIR"
  fi
}

trap cleanup EXIT

usage() {
  cat <<EOF
Usage:
  $(basename "$0") --sherpa-root /path/to/sherpa-onnx [--model MODEL_NAME]
  $(basename "$0") [--model MODEL_NAME]
  $(basename "$0") --skip-libs [--model MODEL_NAME]
  $(basename "$0") --sherpa-root /path/to/sherpa-onnx --skip-model

Options:
  --sherpa-root PATH   sherpa-onnx 源码根目录（包含 build-android-* 产物）。不传则自动下载预编译 AAR 提取 so
  --model NAME         下载并安装的模型名，默认: ${MODEL_NAME}
  --skip-model         跳过模型下载与安装
  --skip-libs          跳过 native so 拷贝
  --keep-tn-assets     保留模型包中的 TN/字典资源（默认保留）
  --prune-tn-assets    清理模型包中的 rule.far/*.fst（减少 APK 体积，可能影响部分模型发音质量）
  -h, --help           查看帮助

示例:
  $(basename "$0") --sherpa-root ~/dev/sherpa-onnx --model matcha-icefall-zh-baker
  $(basename "$0") --model matcha-icefall-zh-baker
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sherpa-root)
      SHERPA_ROOT="${2:-}"
      shift 2
      ;;
    --model)
      MODEL_NAME="${2:-}"
      shift 2
      ;;
    --skip-model)
      SKIP_MODEL=1
      shift
      ;;
    --skip-libs)
      SKIP_LIBS=1
      shift
      ;;
    --keep-tn-assets)
      PRUNE_TN_ASSETS=0
      shift
      ;;
    --prune-tn-assets)
      PRUNE_TN_ASSETS=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -n "$SHERPA_ROOT" ]]; then
  SHERPA_ROOT="$(cd "$SHERPA_ROOT" && pwd)"
fi

download_with_retry() {
  local url="$1"
  local out="$2"
  local -a curl_args=(
    --fail
    --location
    --continue-at -
    --retry 8
    --retry-all-errors
    --retry-delay 2
    --connect-timeout 20
    --speed-time 120
    --speed-limit 64
  )

  if curl "${curl_args[@]}" "$url" -o "$out"; then
    return 0
  fi

  echo "  - Download failed with default HTTP mode, retry with HTTP/1.1"
  curl --http1.1 "${curl_args[@]}" "$url" -o "$out"
}

copy_native_libs_from_source() {
  echo "[1/2] Copy sherpa-onnx native libs..."
  local -a src_arches=("arm64-v8a" "armv7-eabi" "x86" "x86-64")
  local -a dst_arches=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")

  for i in "${!src_arches[@]}"; do
    local src_arch="${src_arches[$i]}"
    local dst_arch="${dst_arches[$i]}"
    local src_dir="$SHERPA_ROOT/build-android-${src_arch}/install/lib"
    local dst_dir="$JNI_TARGET_ROOT/$dst_arch"

    if [[ ! -d "$src_dir" ]]; then
      echo "  - Skip $dst_arch: source dir not found: $src_dir"
      continue
    fi

    mkdir -p "$dst_dir"

    local copied=0
    shopt -s nullglob
    for so in "$src_dir"/*.so; do
      cp -f "$so" "$dst_dir/"
      copied=$((copied + 1))
    done
    shopt -u nullglob

    if [[ $copied -eq 0 ]]; then
      echo "  - Skip $dst_arch: no .so found in $src_dir"
    else
      echo "  - Copied $copied libs -> $dst_dir"
    fi
  done
}

copy_native_libs_from_prebuilt_aar() {
  echo "[1/2] Download prebuilt sherpa-onnx native libs from official release..."
  local tmp_dir
  tmp_dir="$(mktemp -d)"
  local aar_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${PREBUILT_SHERPA_VERSION}/sherpa-onnx-${PREBUILT_SHERPA_VERSION}.aar"
  local aar_file="$tmp_dir/sherpa-onnx.aar"
  local aar_unzip_dir="$tmp_dir/aar"

  echo "  - Download: $aar_url"
  download_with_retry "$aar_url" "$aar_file"
  unzip -q "$aar_file" -d "$aar_unzip_dir"

  local -a arches=("arm64-v8a" "armeabi-v7a" "x86" "x86_64")
  local -a libs=("libsherpa-onnx-jni.so" "libonnxruntime.so")
  for arch in "${arches[@]}"; do
    local dst_dir="$JNI_TARGET_ROOT/$arch"
    mkdir -p "$dst_dir"
    for lib in "${libs[@]}"; do
      local src="$aar_unzip_dir/jni/$arch/$lib"
      if [[ ! -f "$src" ]]; then
        echo "  - Skip $arch/$lib: not found in aar"
        continue
      fi
      cp -f "$src" "$dst_dir/"
      echo "  - Copied $lib -> $dst_dir"
    done
  done

  rm -rf "$tmp_dir"
}

install_model() {
  echo "[2/2] Download and install model: $MODEL_NAME"
  TMP_MODEL_DIR="$(mktemp -d)"

  local url="https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/${MODEL_NAME}.tar.bz2"
  local tar_file="$TMP_MODEL_DIR/model.tar.bz2"

  echo "  - Download: $url"
  download_with_retry "$url" "$tar_file"

  tar -xjf "$tar_file" -C "$TMP_MODEL_DIR"

  local src_dir="$TMP_MODEL_DIR/$MODEL_NAME"
  if [[ ! -d "$src_dir" ]]; then
    src_dir="$(find "$TMP_MODEL_DIR" -mindepth 1 -maxdepth 1 -type d | head -n 1 || true)"
  fi
  if [[ -z "$src_dir" || ! -d "$src_dir" ]]; then
    echo "Error: cannot find extracted model directory." >&2
    exit 1
  fi

  rm -rf "$ASSET_TARGET_DIR"
  mkdir -p "$ASSET_TARGET_DIR"
  cp -R "$src_dir"/. "$ASSET_TARGET_DIR"/

  echo "  - Installed model into: $ASSET_TARGET_DIR"
  if [[ ! -f "$ASSET_TARGET_DIR/tokens.txt" ]]; then
    echo "  - Warning: tokens.txt not found. Please verify the model package."
  fi

  if [[ $PRUNE_TN_ASSETS -eq 1 ]]; then
    echo "  - Prune optional TN assets (rule.far/*.fst) to reduce APK size"
    rm -f "$ASSET_TARGET_DIR/rule.far"
    rm -f "$ASSET_TARGET_DIR"/*.fst
  fi

  rm -rf "$TMP_MODEL_DIR"
  TMP_MODEL_DIR=""
}

echo "Project root: $PROJECT_ROOT"
if [[ -n "$SHERPA_ROOT" ]]; then
  echo "Sherpa root: $SHERPA_ROOT"
fi

if [[ $SKIP_LIBS -eq 0 ]]; then
  if [[ -n "$SHERPA_ROOT" ]]; then
    copy_native_libs_from_source
  else
    copy_native_libs_from_prebuilt_aar
  fi
else
  echo "[1/2] Skip native libs copy (--skip-libs)"
fi

if [[ $SKIP_MODEL -eq 0 ]]; then
  install_model
else
  echo "[2/2] Skip model install (--skip-model)"
fi

echo "Done."
echo "Next: ./gradlew :app:compileDebugKotlin"
