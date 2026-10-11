# seedj 支持政策（Support Policy）

> 面向 seedj 使用者的支持窗口说明（随 v1 首发（1.0.0）生效；不含商业 SLA）。

## 版本支持

| 项 | 口径 |
| --- | --- |
| 支持范围 | 当前 MINOR 线（1.x 最新）+ 上一 MINOR 线的**安全修复**回补 |
| 修复窗口 | 当前线按需发 PATCH；安全类缺陷优先 |
| EOL | 新 MAJOR 发布后，上一 MAJOR 线进入 **6 个月维护窗口**（仅安全修复），到期 EOL |
| 升级窗口 | 业务方建议跟随 PATCH 线升级；MAJOR 升级提供 `docs/upgrade-x.y.md` |

## 技术基线（1.x）

| 项 | 基线 |
| --- | --- |
| JDK | 21（构建与运行） |
| Spring Boot | 4.1.x（版本由 BOM 统一管理） |
| JSON | Jackson 3.x（`tools.jackson`） |
| 数据访问 | MyBatis-Plus 3.5.x（仅 `starter-data-mybatis`） |
| 分布式 | coord-java-sdk 0.2.x（coord 家族） |

平台级升级（JDK / Boot 大版本）统一走 MAJOR 或经评估公告的 MINOR，并同步更新本文件。

## 获取支持

- 缺陷 / 需求：仓库 Issues；
- **安全漏洞**：勿开公开 Issue；按 [`SECURITY.md`](../SECURITY.md) 走 GitHub 私密安全公告或联系维护者安排私密渠道；
- 变更感知：[`../CHANGELOG.md`](../CHANGELOG.md)（Keep a Changelog + SemVer）、
  [`assessment/`](./assessment/README.md)（评估快照）、[`ROADMAP.md`](./ROADMAP.md)（里程碑）。
