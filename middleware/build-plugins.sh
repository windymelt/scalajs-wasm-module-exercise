#!/usr/bin/env bash
# プラグインをビルドして middleware/host/plugins/ に置く
set -euo pipefail
cd "$(dirname "$0")/.."
ids=(waf ratelimit auth security-headers signature evil greedy)
sbt -batch $(printf 'mw-%s/fullLinkJS ' "${ids[@]}")
mkdir -p middleware/host/plugins
for id in "${ids[@]}"; do
  cp "middleware/plugins/$id/target/scala-2.13/mw-$id-opt/main.wasm" "middleware/host/plugins/$id.wasm"
done
ls -la middleware/host/plugins
