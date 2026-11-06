#!/usr/bin/env bash
# API 兼容门禁：先确认基线制品可解析，再执行 japicmp。
#
# japicmp 0.26.3 在基线解析失败时只打印 WARNING、写出空 diff，构建仍然成功——
# 这会把“没有比较”伪装成“兼容”。本脚本显式失败，避免假绿。
#
# 首次发布（还没有任何基线）用 SEEDJ_FIRST_RELEASE=true 显式跳过，并打印横幅；
# 常规发布不设置该变量，基线缺失即失败。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

MAVEN_FLAGS="${MAVEN_FLAGS--o}"
BASELINE="${SEEDJ_COMPAT_BASELINE:-1.0.0}"

if [[ "${SEEDJ_FIRST_RELEASE:-false}" == "true" ]]; then
    echo "WARN: 首次发布，显式跳过 API 兼容比较（没有 ${BASELINE} 基线）" >&2
    exit 0
fi

artifacts=(
    core
    seedj-infra-local
    seedj-jdbc-postgresql
    seedj-iam-starter
    seedj-biz-starter
)

missing=()
for artifact in "${artifacts[@]}"; do
    if ! ./mvnw $MAVEN_FLAGS -B -q dependency:get \
        -Dartifact="cn.byteforce.seedj:${artifact}:${BASELINE}" \
        -Dtransitive=false >/dev/null 2>&1; then
        missing+=("$artifact")
    fi
done

if (( ${#missing[@]} > 0 )); then
    echo "API 兼容基线 ${BASELINE} 缺失：${missing[*]}" >&2
    echo "先发布基线，或用 SEEDJ_COMPAT_BASELINE=<version> 指定可解析的版本；" >&2
    echo "首次发布可显式设置 SEEDJ_FIRST_RELEASE=true。" >&2
    exit 1
fi

./mvnw $MAVEN_FLAGS -B -Pcompat -DskipTests \
    -Dseedj.compat.baseline="$BASELINE" verify

echo "compat: OK（baseline ${BASELINE}）"
