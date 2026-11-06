#!/usr/bin/env bash
# 发布门禁：SBOM 随制品归档；1.0.0 基线发布后启用 API 兼容性比较。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

MAVEN_FLAGS="${MAVEN_FLAGS:--o}"

./mvnw $MAVEN_FLAGS -B -Psbom -DskipTests package
./mvnw $MAVEN_FLAGS -B -Pcompat -DskipTests verify

echo "release: OK（API 兼容性需要已发布的 1.0.0 基线）"
