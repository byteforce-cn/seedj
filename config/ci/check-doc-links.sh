#!/usr/bin/env bash
# 文档链接门禁入口（本地与 CI 共用）。
#
# 判定逻辑只有一份：seedj-it 的 DocLinkTest（仓库内相对链接存在性检查，不联网）。
# 本脚本只负责定位仓库根并调用 wrapper，避免 shell 与 Java 各自维护一套链接解析规则。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

if [[ ! -x ./mvnw ]]; then
    echo "缺少 Maven Wrapper：./mvnw" >&2
    exit 1
fi

exec ./mvnw -B -pl seedj-it -am test \
    -Dtest=DocLinkTest \
    -Dsurefire.failIfNoSpecifiedTests=false "$@"
