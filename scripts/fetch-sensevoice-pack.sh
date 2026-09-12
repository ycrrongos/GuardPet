#!/usr/bin/env bash
# Download sherpa-onnx AAR + SenseVoice int8 model assets for 守伴.
# Usage: from GuardPet/  →  ./scripts/fetch-sensevoice-pack.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIBS="$ROOT/libs"
ASSETS="$ROOT/sensevoice-pack/src/main/assets/sensevoice"
TMP="${TMPDIR:-/tmp}/guardpet-sensevoice-fetch"
AAR_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2"
MODEL_DIR_NAME="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17"
MIN_MODEL_BYTES=$((50 * 1024 * 1024))

mkdir -p "$LIBS" "$ASSETS" "$TMP"

filesize() {
  stat -c%s "$1" 2>/dev/null || stat -f%z "$1"
}

download() {
  local url="$1" out="$2" min_bytes="${3:-1000000}"
  if [[ -f "$out" ]] && [[ $(filesize "$out") -ge $min_bytes ]]; then
    echo "Already present: $out ($(filesize "$out") bytes)"
    return 0
  fi
  echo "Downloading $url → $out"
  if command -v aria2c >/dev/null 2>&1; then
    aria2c -c -x 8 -s 8 -k 1M -d "$(dirname "$out")" -o "$(basename "$out")" "$url"
  else
    curl -L --retry 5 --retry-delay 3 --connect-timeout 30 -o "$out" "$url"
  fi
}

download "$AAR_URL" "$LIBS/sherpa-onnx-1.13.8.aar" 10000000

MODEL_OK=0
if [[ -f "$ASSETS/model.int8.onnx" ]] && [[ $(filesize "$ASSETS/model.int8.onnx") -ge $MIN_MODEL_BYTES ]] \
  && [[ -f "$ASSETS/tokens.txt" ]]; then
  MODEL_OK=1
  echo "Model already present: $ASSETS/model.int8.onnx"
fi

if [[ "$MODEL_OK" -eq 0 ]]; then
  TARBALL="$TMP/sensevoice.tar.bz2"
  # Corrupt partials from resume are common; prefer a clean fetch when verifying fails.
  if [[ -f "$TARBALL" ]]; then
    if ! tar -tjf "$TARBALL" >/dev/null 2>&1; then
      echo "Removing corrupt tarball"
      rm -f "$TARBALL"
    fi
  fi
  download "$MODEL_URL" "$TARBALL" 100000000
  echo "Verifying / extracting model into $ASSETS"
  tar -tjf "$TARBALL" >/dev/null
  rm -rf "$TMP/$MODEL_DIR_NAME"
  tar -xjf "$TARBALL" -C "$TMP"
  cp -f "$TMP/$MODEL_DIR_NAME/model.int8.onnx" "$ASSETS/model.int8.onnx"
  cp -f "$TMP/$MODEL_DIR_NAME/tokens.txt" "$ASSETS/tokens.txt"
fi

MODEL_SIZE=$(filesize "$ASSETS/model.int8.onnx")
if [[ "$MODEL_SIZE" -lt $MIN_MODEL_BYTES ]]; then
  echo "ERROR: model.int8.onnx too small ($MODEL_SIZE). Delete it and re-run." >&2
  exit 1
fi

ls -lah "$LIBS/sherpa-onnx-1.13.8.aar" "$ASSETS/model.int8.onnx" "$ASSETS/tokens.txt"
echo "Done. Build with: ./gradlew :app:assembleDebug :sensevoice-pack:assembleDebug"
