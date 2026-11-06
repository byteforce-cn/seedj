#!/usr/bin/env bash
# 发布门禁：SBOM 随制品归档；1.0.0 基线发布后启用 API 兼容性比较。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

MAVEN_FLAGS="${MAVEN_FLAGS--o}"

./mvnw $MAVEN_FLAGS -B -Psbom -DskipTests package
if [[ ! -f target/seedj-sbom.json ]]; then
    echo "SBOM 未生成：cyclonedx 在离线模式（-o）会静默跳过；请用 MAVEN_FLAGS=\"\" 重新运行" >&2
    exit 1
fi
bash config/ci/check-compat.sh

echo "release: OK（API 兼容门禁由 check-compat.sh 执行；基线缺失即失败）"
