# 安全政策（Security Policy）

## 支持版本

| 版本 | 支持状态 |
| --- | --- |
| 1.x 最新 minor | ✅ 安全修复 |
| 更早版本 | ❌ 建议升级至受支持版本 |

## 报告漏洞

**请勿通过公开 Issue 报告安全漏洞。**

1. **首选**：使用仓库的 GitHub 私密安全公告
   （仓库 **Security** 页 → *Report a vulnerability*，
   参考 [GitHub 官方说明](https://docs.github.com/code-security/security-advisories/guidance-on-reporting-and-writing/privately-reporting-a-security-vulnerability)）；
2. 若无法使用 GitHub，请联系任一维护者安排私密渠道，**不要在公开渠道披露细节**。

报告中请尽量包含：

- 受影响模块与版本；
- 复现步骤与最小 PoC；
- 影响评估（可利用性、影响面）与可能的修复建议。

## 处理流程

1. 确认与分级（尽力在 48 小时内完成首次响应）；
2. 在私密环境完成修复与验证，涉及对外契约的变更按 `docs/api-compatibility.md` 流程处理；
3. 发布修复版本，并同步发布安全公告（致谢报告者，除非其要求匿名）。
