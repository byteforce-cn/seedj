## 变更说明

<!-- 这个 PR 做了什么？为什么？-->

## 变更类型

- [ ] fix（缺陷修复，PATCH）
- [ ] feat（向后兼容新增，MINOR）
- [ ] breaking change（破坏性变更，MAJOR；需先提 ADR 并写明迁移方式）
- [ ] refactor / docs / chore / test

## 检查清单

- [ ] 本地 `./mvnw -B -T1C verify` 通过（含覆盖率门禁与零测试守卫）
- [ ] 新增/修改的 public 方法均有测试与 Javadoc
- [ ] 涉及对外行为 / 配置变更：已更新 `CHANGELOG.md` 与相关文档（含 ADR）
- [ ] 未引入 `record`；新增依赖已设 `optional`/`provided` 并在 `bom` 登记版本
- [ ] 未提交构建产物（`target/`）、IDE 配置与临时文件

## 关联 Issue

<!-- Closes #123 -->
