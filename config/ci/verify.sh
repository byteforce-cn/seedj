#!/usr/bin/env bash
# 主门禁：本地与 CI 使用同一套命令。
#
# MAVEN_FLAGS 默认离线；CI 设置 MAVEN_FLAGS="" 走在线仓库。
# SpotBugs 在 CI 与本地默认阻断；仅排查历史问题时用 SPOTBUGS_FAIL_ON_ERROR=false
# 临时降为 advisory（会打印显式 WARN，不允许静默跳过）。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

MAVEN_FLAGS="${MAVEN_FLAGS--o}"
SPOTBUGS_FAIL_ON_ERROR="${SPOTBUGS_FAIL_ON_ERROR:-true}"

if [[ "$SPOTBUGS_FAIL_ON_ERROR" != "true" ]]; then
    echo "WARN: SpotBugs advisory（-Dspotbugs.failOnError=false）仅用于本地排查；CI 默认阻断" >&2
fi

./mvnw $MAVEN_FLAGS -B -Panalysis verify -Dspotbugs.failOnError="$SPOTBUGS_FAIL_ON_ERROR"
bash config/ci/check-coverage.sh
./mvnw $MAVEN_FLAGS -B spotless:check

# 授权负例是默认切片：SoD/ABAC 与字段 masking 必须在命令/读路径上固定。
./mvnw $MAVEN_FLAGS -B -pl seedj-it -am test \
    -Dtest=CommandAuthorizationIT,FieldMaskingIT \
    -Dsurefire.failIfNoSpecifiedTests=false

# eis 固定版本工作副本存在时，契约对拍是硬门禁；本地没有 EIS_ROOT 时由测试自身跳过。
if [[ -n "${EIS_ROOT:-}" ]]; then
    ./mvnw $MAVEN_FLAGS -B -pl seedj-it -am test \
        -Dtest=EisContractAlignmentTest -Dsurefire.failIfNoSpecifiedTests=false \
        -Deis.root="$EIS_ROOT"
fi

echo "verify: OK"
