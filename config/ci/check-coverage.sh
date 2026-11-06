#!/usr/bin/env bash
# 模块覆盖率 ratchet 门禁：把每个模块的实测覆盖率与 config/coverage-baseline.json 比较。
#
# - 低于基线超过 0.5 个百分点即失败；
# - 报告缺失（例如只构建了部分模块）打印 WARN 并跳过，用于本地；
#   CI 全量构建不应有缺失，缺失本身就是可见信号；
# - 若安装了 diff-cover，再对变更行执行 90% 门禁（CI 必须安装）。
#
# 用法：bash config/ci/check-coverage.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BASELINE="$ROOT/config/coverage-baseline.json"
TOLERANCE="0.005"

if [ ! -f "$BASELINE" ]; then
    echo "check-coverage: missing $BASELINE" >&2
    exit 2
fi

python3 - "$ROOT" "$BASELINE" "$TOLERANCE" <<'PY'
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(sys.argv[1])
baseline = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
tolerance = float(sys.argv[3])


def ratios(xml_path: Path) -> dict[str, float]:
    counters = ET.parse(xml_path).getroot().findall("counter")
    out: dict[str, float] = {}
    for counter in counters:
        kind = counter.get("type")
        if kind not in ("LINE", "BRANCH"):
            continue
        covered = int(counter.get("covered", "0"))
        missed = int(counter.get("missed", "0"))
        total = covered + missed
        out[kind.lower()] = (covered / total) if total else 0.0
    return out


failures: list[str] = []
missing: list[str] = []
rows: list[tuple[str, float, float, float, float]] = []

for module in sorted(baseline):
    xml_path = root / module / "target/site/jacoco/jacoco.xml"
    if not xml_path.is_file():
        missing.append(module)
        print(f"WARN  {module}: no {xml_path.relative_to(root)} (build the module first)")
        continue

    measured = ratios(xml_path)
    for kind in ("line", "branch"):
        floor = float(baseline[module][kind])
        actual = measured.get(kind, 0.0)
        rows.append((module, floor, actual, floor - actual, 0.0))
        if actual + tolerance < floor:
            failures.append(
                f"{module} {kind}: {actual:.3f} < baseline {floor:.3f} "
                f"(drop {floor - actual:.3f})"
            )

print(f"{'module':<28}{'line floor':>11}{'line':>8}{'branch floor':>14}{'branch':>8}")
for module in sorted(baseline):
    if module in missing:
        continue
    xml_path = root / module / "target/site/jacoco/jacoco.xml"
    measured = ratios(xml_path)
    print(
        f"{module:<28}{baseline[module]['line']:>11.3f}{measured.get('line', 0.0):>8.3f}"
        f"{baseline[module]['branch']:>14.3f}{measured.get('branch', 0.0):>8.3f}"
    )

if failures:
    print("\nCOVERAGE GATE FAILED:", file=sys.stderr)
    for failure in failures:
        print(f"  - {failure}", file=sys.stderr)
    sys.exit(1)

print("\ncoverage ratchet: OK")
PY

# 变更行覆盖：只有安装了 diff-cover 才执行（CI 必须安装）。
if command -v diff-cover >/dev/null 2>&1; then
    base="${DIFF_COVER_BASE:-origin/main}"
    if ! git -C "$ROOT" rev-parse --verify --quiet "$base" >/dev/null; then
        echo "diff-cover: SKIP (base '$base' not found; set DIFF_COVER_BASE)"
    else
        mapfile -t reports < <(find "$ROOT" -path '*/target/site/jacoco/jacoco.xml' -not -path '*/node_modules/*' | sort)
        if [ "${#reports[@]}" -eq 0 ]; then
            echo "diff-cover: SKIP (no jacoco reports)"
        else
            diff-cover "${reports[@]}" --compare-branch="$base" --fail-under=90
        fi
    fi
else
    echo "diff-cover: SKIP (install diff-cover to enforce changed-line coverage; CI must run it)"
fi
