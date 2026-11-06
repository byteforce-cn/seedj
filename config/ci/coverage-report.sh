#!/usr/bin/env bash
# 覆盖率热点报告：解析各模块 jacoco.xml，输出按包/类排序的 missed lines。
#
# 用法：bash config/ci/coverage-report.sh [输出路径]
# 默认写入 <repo>/target/coverage-hotspots.md；报告只读，不修改阈值。
set -euo pipefail

cd "$(dirname "$0")/../.." || {
    echo "无法定位仓库根（config/ci 的上级目录）" >&2
    exit 1
}

OUTPUT="${1:-target/coverage-hotspots.md}"
mkdir -p "$(dirname "$OUTPUT")"

python3 - "$OUTPUT" <<'PY'
import glob
import os
import sys
import xml.etree.ElementTree as ET

output = sys.argv[1]
modules = [
    "core",
    "seedj-infra-local",
    "seedj-jdbc-postgresql",
    "seedj-biz-starter",
    "seedj-iam-starter",
]

def counter(node, name):
    for child in node.findall("counter"):
        if child.get("type") == name:
            return int(child.get("covered")), int(child.get("missed"))
    return 0, 0

def ratio(covered, missed):
    total = covered + missed
    return 0.0 if total == 0 else covered / total

lines = ["# 覆盖率热点", "", "> 由 `config/ci/coverage-report.sh` 生成；用于排优先级，不是门禁。", ""]
lines.append("| 模块 | line | branch |")
lines.append("|---|---|---|")
packages = []
classes = []
for module in modules:
    report = os.path.join(module, "target/site/jacoco/jacoco.xml")
    if not os.path.isfile(report):
        lines.append(f"| {module} | 缺失 | 缺失 |")
        continue
    root = ET.parse(report).getroot()
    lc, lm = counter(root, "LINE")
    bc, bm = counter(root, "BRANCH")
    lines.append(f"| {module} | {ratio(lc, lm):.1%} | {ratio(bc, bm):.1%} |")
    for pkg in root.findall("package"):
        plc, plm = counter(pkg, "LINE")
        pbc, pbm = counter(pkg, "BRANCH")
        if plm:
            packages.append((plm, module, pkg.get("name"), ratio(plc, plm), ratio(pbc, pbm)))
        for clazz in pkg.findall("class"):
            clc, clm = counter(clazz, "LINE")
            if clm:
                classes.append((clm, module, clazz.get("name")))

lines.extend(["", "## 包级热点（按 missed lines 降序）", ""])
lines.append("| missed | 模块 | 包 | line | branch |")
lines.append("|---|---|---|---|---|")
for missed, module, name, line_ratio, branch_ratio in sorted(packages, reverse=True)[:30]:
    lines.append(f"| {missed} | {module} | `{name}` | {line_ratio:.1%} | {branch_ratio:.1%} |")

lines.extend(["", "## 类级热点（按 missed lines 降序，前 30）", ""])
lines.append("| missed | 模块 | 类 |")
lines.append("|---|---|---|")
for missed, module, name in sorted(classes, reverse=True)[:30]:
    lines.append(f"| {missed} | {module} | `{name}` |")

with open(output, "w", encoding="utf-8") as handle:
    handle.write("\n".join(lines) + "\n")
print(f"coverage report: {output}")
PY
