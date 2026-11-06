# 合规门禁工件

- `compatibility.rules.yaml`：规范 06 的冻结规则副本；判定权威仍是规范仓库。
- `check-compatibility.sh`：`-Pconformance` 调用入口；先跑负例自检，再逐对比较
  `docs/conformance/baseline/*.schema.json` 与 `docs/conformance/schema/*.schema.json`。
- `gate-selftest.sh`：证明门禁不是假绿——字段删除必须阻断，可选新增必须放行。
- 首次发布前，`baseline/` 与 `schema/` 相同；发布后 `baseline/` 冻结为已发布版本，
  只更新 `schema/`。

运行：

```bash
mvn -Pconformance -DskipTests verify
# 或直接指定规范仓库
bash config/conformance/check-compatibility.sh /path/to/spec
```
