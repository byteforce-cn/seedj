#!/usr/bin/env bash
# 兼容性门禁负例自检：字段删除必须 MAJOR，可选新增必须 MINOR。
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
spec_root="${1:-$repo_root/../bf/eis}"
checker="$spec_root/tools/compatibility-checker"

if [[ ! -f "$checker" ]]; then
  echo "ERROR: compatibility-checker not found at $checker" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/old.json" <<'JSON'
{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"}},"required":["a","b"]}
JSON

cat > "$tmp/major.json" <<'JSON'
{"type":"object","properties":{"b":{"type":"integer"}},"required":["b"]}
JSON

cat > "$tmp/minor.json" <<'JSON'
{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"integer"},"c":{"type":"string"}},"required":["a","b"]}
JSON

if python3 "$checker" --old "$tmp/old.json" --new "$tmp/major.json" --fail-on MAJOR >/dev/null 2>&1; then
  echo "ERROR: gate did not fail on a MAJOR change" >&2
  exit 1
fi
if ! python3 "$checker" --old "$tmp/old.json" --new "$tmp/minor.json" --fail-on MAJOR >/dev/null 2>&1; then
  echo "ERROR: gate failed on a MINOR change" >&2
  exit 1
fi

echo "gate self-test: MAJOR blocked, MINOR allowed"
