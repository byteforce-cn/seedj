#!/usr/bin/env bash
# 依赖漏洞扫描（OWASP dependency-check）。
#
# 这是 CI / 发布专用门禁：需要在线 Maven 仓库与 NVD 数据（首次下载较慢）。
# 本地离线环境调用会显式失败，而不是假装“扫描通过”。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

MAVEN_FLAGS="${MAVEN_FLAGS:--o}"
FAIL_ON_CVSS="${FAIL_ON_CVSS:-7}"

if [[ "$MAVEN_FLAGS" == *-o* ]]; then
    echo "dependency-check 需要在线仓库与 NVD 数据；请用 MAVEN_FLAGS=\"\" 运行" >&2
    exit 2
fi

./mvnw $MAVEN_FLAGS -B org.owasp:dependency-check-maven:check \
    -DfailBuildOnCVSS="$FAIL_ON_CVSS" \
    -DskipTests \
    -Dspotbugs.skip=true \
    -Djacoco.skip=true

echo "dependency-check: OK（failBuildOnCVSS=${FAIL_ON_CVSS}）"
