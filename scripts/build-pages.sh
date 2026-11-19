#!/usr/bin/env bash
# ブラウザ用の .wasm をビルドして pages/ に置く
set -euo pipefail
cd "$(dirname "$0")/.."
sbt -batch web/fullLinkJS scheme/fullLinkJS audio/fullLinkJS
cp web/target/scala-2.13/sjs-wasm-web-opt/main.wasm pages/renderer/main.wasm
cp scheme/target/scala-2.13/sjs-wasm-scheme-opt/main.wasm pages/scheme/main.wasm
cp audio/target/scala-2.13/sjs-wasm-audio-opt/main.wasm pages/convolver/main.wasm
echo "done. serve with: python3 -m http.server -d pages 8000"
