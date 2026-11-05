#!/usr/bin/env bash
# 契约兼容性门禁：负例自检 + baseline/current Schema 逐对判定。
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
spec_root="${1:-$repo_root/../bf/eis}"
checker="$spec_root/tools/compatibility-checker"

if [[ ! -f "$checker" ]]; then
  echo "ERROR: compatibility-checker not found at $checker" >&2
  echo "Set -Dspec.root=<path-to-spec-repo> or provide the spec repository." >&2
  exit 1
fi

bash "$script_dir/gate-selftest.sh" "$spec_root"

shopt -s nullglob
checked=0
for baseline in "$repo_root"/docs/conformance/baseline/*.schema.json; do
  name="$(basename "$baseline")"
  current="$repo_root/docs/conformance/schema/$name"
  if [[ ! -f "$current" ]]; then
    echo "ERROR: missing current schema $current" >&2
    exit 1
  fi
  echo "== $name"
  python3 "$checker" --old "$baseline" --new "$current" --fail-on MAJOR
  checked=$((checked + 1))
done

if [[ "$checked" -eq 0 ]]; then
  echo "ERROR: no baseline schemas found" >&2
  exit 1
fi

echo "compatibility gate: $checked schema pair(s) passed (--fail-on MAJOR)"
