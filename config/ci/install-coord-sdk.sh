#!/usr/bin/env bash
# 安装 seedj BOM 固定的 coord-java-sdk。
#
# coord 的 0.4.2+ 没有发布到 Maven Central（Central 停在 0.4.1）。CI 用
# COORD_SOURCE（指向 coord 仓库工作副本）现场安装；本地要么同样设置它，
# 要么已经手工 install 过同版本。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERSION="$(sed -n 's:.*<coord-java-sdk.version>\(.*\)</coord-java-sdk.version>.*:\1:p' "$ROOT/bom/pom.xml" | head -n1)"
if [[ -z "$VERSION" ]]; then
    echo "无法从 bom/pom.xml 读取 coord-java-sdk.version" >&2
    exit 1
fi

ARTIFACT="$HOME/.m2/repository/cn/byteforce/coord-java-sdk/$VERSION/coord-java-sdk-$VERSION.jar"
if [[ -f "$ARTIFACT" ]]; then
    echo "coord-java-sdk $VERSION: already installed"
    exit 0
fi

if [[ -z "${COORD_SOURCE:-}" || ! -f "$COORD_SOURCE/coord-java-sdk/pom.xml" ]]; then
    echo "coord-java-sdk $VERSION 不在本地仓库，且未设置 COORD_SOURCE（指向 coord 仓库根）" >&2
    exit 1
fi

mvn -B -q -f "$COORD_SOURCE/coord-java-sdk/pom.xml" -DskipTests install
echo "coord-java-sdk $VERSION: installed from $COORD_SOURCE"
