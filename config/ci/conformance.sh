#!/usr/bin/env bash
# 规范兼容性门禁：需要规范仓库（默认相对路径，-Dspec.root=<path> 覆盖）。
# 工具或工件缺席时 POM 显式失败，不静默跳过。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

MAVEN_FLAGS="${MAVEN_FLAGS--o}"

# 固定版本的 eis 工作副本存在时，先做逐字节对齐 + 兼容性检查；
# 没有 EIS_ROOT 时保留原有 -Pconformance 行为（由 Maven 侧显式失败）。
if [[ -n "${EIS_ROOT:-}" ]]; then
    EIS_ROOT="$EIS_ROOT" bash config/conformance/check-spec-alignment.sh
fi

./mvnw $MAVEN_FLAGS -B -Pconformance -DskipTests verify "$@"
