#!/usr/bin/env bash
# 规范对齐门禁：seedj1 的契约副本必须与 eis 权威 Schema 逐字节一致。
#
# 用法：
#   EIS_ROOT=../eis bash config/conformance/check-spec-alignment.sh
#
# 退出码：0 对齐；1 漂移或用法错误。CI 与本地使用同一脚本，不允许静默跳过。
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$repo_root"

: "${EIS_ROOT:?EIS_ROOT must point to the eis worktree}"
if [[ ! -d "$EIS_ROOT" ]]; then
    echo "EIS_ROOT is not a directory: $EIS_ROOT" >&2
    exit 1
fi

ref_file="config/conformance/eis-ref.txt"
if [[ ! -f "$ref_file" ]]; then
    echo "missing $ref_file (pin eis by sha=<40 hex> or tag=<name>)" >&2
    exit 1
fi
pin="$(head -n 1 "$ref_file" | tr -d '[:space:]')"
case "$pin" in
    sha=*) pinned="${pin#sha=}" ;;
    tag=*) pinned="" ;;
    *)
        echo "invalid $ref_file: expected sha=<40 hex> or tag=<name>, got '$pin'" >&2
        exit 1
        ;;
esac

if [[ -n "$pinned" ]]; then
    actual="$(git -C "$EIS_ROOT" rev-parse HEAD 2>/dev/null || true)"
    if [[ "$actual" != "$pinned" ]]; then
        echo "eis worktree is at '${actual:-unknown}', but $ref_file pins $pinned" >&2
        echo "checkout the pinned revision or update the pin deliberately" >&2
        exit 1
    fi
fi

fail=0
while IFS=':' read -r eis_dir schema; do
    [[ -z "$eis_dir" ]] && continue
    expected="$EIS_ROOT/$eis_dir/$schema.schema.json"
    actual="config/conformance/schema/$schema.schema.json"
    if [[ ! -f "$expected" ]]; then
        echo "missing eis schema: $expected" >&2
        fail=1
        continue
    fi
    if ! diff -u "$expected" "$actual"; then
        echo "spec drift: $actual differs from $expected" >&2
        fail=1
    fi
done <<'PAIRS'
01-data-contract:object
01-data-contract:command
01-data-contract:event
01-data-contract:view
01-data-contract:temporal
04-authz-contract:proxy
04-authz-contract:principal
04-authz-contract:binding
04-authz-contract:role
PAIRS

# 旧副本 → 当前副本的兼容性：任何 MAJOR 变更必须在评审中显式处理。
checker="$EIS_ROOT/tools/compatibility-checker"
if [[ ! -f "$checker" ]]; then
    echo "missing eis compatibility checker: $checker" >&2
    exit 1
fi
python3 "$checker" \
    --old config/conformance/baseline/object.schema.json \
    --new config/conformance/schema/object.schema.json \
    --fail-on MAJOR

if [[ "$fail" -ne 0 ]]; then
    echo "spec alignment: FAILED" >&2
    exit 1
fi
echo "spec alignment: OK (pin $pin)"
