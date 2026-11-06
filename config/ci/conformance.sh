#!/usr/bin/env bash
# 规范兼容性门禁：需要规范仓库（默认相对路径，-Dspec.root=<path> 覆盖）。
# 工具或工件缺席时 POM 显式失败，不静默跳过。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

MAVEN_FLAGS="${MAVEN_FLAGS--o}"
./mvnw $MAVEN_FLAGS -B -Pconformance -DskipTests verify "$@"
