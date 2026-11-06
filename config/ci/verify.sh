#!/usr/bin/env bash
# 主门禁：本地与 CI 使用同一套命令。
#
# MAVEN_FLAGS 默认离线；CI 设置 MAVEN_FLAGS="" 走在线仓库。
# SpotBugs 在 M0–M3 处于 advisory 状态（仓库存在既有 Medium 发现，T4.3 逐项修复/排除后
# 由 CI 设置 SPOTBUGS_FAIL_ON_ERROR=true 转为阻断）。advisory 期间会打印显式 WARN，
# 不允许静默跳过。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

MAVEN_FLAGS="${MAVEN_FLAGS:--o}"
SPOTBUGS_FAIL_ON_ERROR="${SPOTBUGS_FAIL_ON_ERROR:-false}"

if [[ "$SPOTBUGS_FAIL_ON_ERROR" != "true" ]]; then
    echo "WARN: SpotBugs 处于 advisory（-Dspotbugs.failOnError=false）；T4.3 修复既有发现后必须改为阻断" >&2
fi

./mvnw $MAVEN_FLAGS -B -Panalysis verify -Dspotbugs.failOnError="$SPOTBUGS_FAIL_ON_ERROR"
bash config/ci/check-doc-links.sh
bash config/ci/check-coverage.sh
./mvnw $MAVEN_FLAGS -B spotless:check

echo "verify: OK"
