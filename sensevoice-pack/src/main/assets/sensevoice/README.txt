assets/sensevoice/.gitkeep
Keep model.int8.onnx out of git (≈150MB+).

Run from GuardPet/:
  ./scripts/fetch-sensevoice-pack.sh

That downloads:
1. libs/sherpa-onnx-1.13.8.aar
2. sensevoice-pack/src/main/assets/sensevoice/model.int8.onnx
3. sensevoice-pack/src/main/assets/sensevoice/tokens.txt

Upstream model:
https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2

Then:
  ./gradlew :sensevoice-pack:assembleDebug
  adb install -r sensevoice-pack/build/outputs/apk/debug/sensevoice-pack-debug.apk
