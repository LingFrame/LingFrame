#!/usr/bin/env bash
# 灵珑（LingFrame）万级 QPS 压测脚本（wrk）
#
# 用法：
#   bash scripts/loadtest/wrk/run-wrk-10k.sh [base_url]
# 默认 base_url=http://localhost:8888
#
# 场景：
#   - 8 线程 / 100 并发 / 60s，压 GET /lingcore/hello
#   - 输出到 stdout（wrk 汇总）+ 保存原始结果到 scripts/loadtest/wrk/results/
#
# 注意：
#   - 万级 QPS 受机器配置影响显著，先跑小并发校准基线再放大（见 README）
#   - 压测期间关闭 dev-mode 更接近生产（见 docs/zh-CN/production-hardening.md）

set -euo pipefail

BASE_URL="${1:-http://localhost:8888}"
OUT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/results"
mkdir -p "${OUT_DIR}"
STAMP="$(date +%Y%m%d-%H%M%S)"

echo "== 灵珑万级 QPS 压测 =="
echo "目标: ${BASE_URL}/lingcore/hello"
echo "线程: 8  并发: 100  时长: 60s"

wrk -t8 -c100 -d60s --latency \
    -s "$(dirname "${BASH_SOURCE[0]}")/hello.lua" \
    "${BASE_URL}" \
    | tee "${OUT_DIR}/hello-10k-${STAMP}.log"

echo "结果已保存: ${OUT_DIR}/hello-10k-${STAMP}.log"
