# Seedj

面向业务系统的 Java 21 / Spring Boot 多模块基础设施：**契约层零 Spring**，内存与
PostgreSQL 适配器并列，starter 负责装配，宿主只做组合。默认写路径是双时间轴命令，
事件溯源是显式的第二选择。

## 快速开始

```bash
# 本地跑 HR（内存实现，不需要数据库）
./mvnw -pl seedj-server/hr -am spring-boot:run \
  -Dspring-boot.run.profiles=local

curl -s -X POST localhost:8080/commands/hr.employee.create \
  -H 'Content-Type: application/json' \
  -d '{"type":"hr.employee.create",
       "idempotency_key":"demo-1",
       "payload":{"name":"Alice","hire_date":"2026-01-05",
                  "employment_type":"FULL_TIME","work_location":"SH",
                  "org_unit_id":"D01"}}'
```

15 分钟 quickstart 见独立文档仓（与源码仓分开版本控制）。

## 业务开发最小路径

- **加字段**：在 `@SeedjObject` record 上声明；命令 record 的同名组件自动映射到 schema，
  事件载荷与命令记录由运行时自动剔除 `writeOnly` 字段；`view = true` 时读视图也由生成器
  产出，不再手写第二份字段列表；迁移候选审阅后提交。
- **加命令**：command record + handler + `ObjectModules.command(...)`；权限、`creates()`、
  事件类型与需要脱敏的命令专用字段都在声明点收口，运行时按声明失败关闭。
- **加查询**：`.query(...)` 直接引用生成的视图；分页、过滤、排序与时点读取由模板提供。
- **加测试**：业务规则用 `CommandHarness`（内存存储 + 固定时钟 + caller），不启 Spring；
  `@SpringBootTest` 只留给 HTTP、安全与数据库契约。

## 模块

```text
core                     契约：错误码、时间轴、命令、IAM、工作流、SPI、@SeedjObject 注解
seedj-codegen            构建期注解处理器：schema / OpenAPI / 迁移候选 / 可选读视图（不进运行时）
seedj-infra-local        内存 / 文件 / BouncyCastle 适配器
seedj-workflow-local     workflow SPI 的本地内存实现（库模块，不启动 server）
seedj-jdbc-postgresql    PostgreSQL 适配器、DDL、版本化迁移
seedj-biz-starter        业务装配：本地开发认证 / 命令 / 查询 / 审计 / 事件总线
seedj-iam-starter        IAM 装配：OIDC / SCIM / RBAC / ABAC
seedj-server/{hr,iam,im} 可运行宿主
seedj-it                 契约对照、并发与端到端集成测试
```

依赖方向：`server → starter → infra/jdbc → core`；实现包在
`cn.byteforce.seedj.infra.*`，装配包在 `cn.byteforce.seedj.starter.*`，契约包在
`cn.byteforce.seedj.<cap>`。模块与包拓扑由 ADR-0008、ADR-0009 记录（独立文档仓）。

## 构建与门禁

本地与 CI 使用同一套脚本：

```bash
bash config/ci/verify.sh        # 全量测试 + 覆盖率 + ArchUnit + 格式
bash config/ci/conformance.sh   # 规范兼容性（需规范仓库）
bash config/ci/release.sh      # 发布门禁：SBOM + API 兼容性
```

脚本默认离线（`MAVEN_FLAGS=-o`），CI 设置 `MAVEN_FLAGS=""`。SpotBugs 在整改期为
advisory（显式 WARN，不静默跳过）；发布 tag 的 job 已按阻断模式运行。

## 文档

开发过程文档（设计、计划、评估、验收记录）、ADR 与使用指南统一放在独立文档仓，
与源码仓分开版本控制；本仓库只保留代码、构建脚本与 README。机器可读的契约
（OpenAPI、JSON Schema、baseline）在 `config/conformance/`。

版本对应关系：

| 源码 | 文档仓 | 说明 |
|---|---|---|
| `1.0.0-SNAPSHOT`（main） | main | 开发中；ADR 与评估为快照 |
| `v1.0.0`（未来 tag） | 同名 tag | 发布时同步打 tag；文档首页标注兼容版本 |

canonical URL 在首次发布、文档仓建立远端后补入本段。

## 认知负担门禁

`config/cognitive-load-baseline.properties` 记录模板预算、概念预算、测试切片比例、变更放大与
public API 上限；`seedj-it` 的四个门禁测试在 CI 默认执行，只允许收紧。

## 兼容性

1.0.0 尚未发布。线格式 `/commands/{type}`、`/objects/{type}/{id}` 与错误信封
`{code,message,details}` 保持稳定；Java 包名与类型在 1.0.0 前允许破坏性调整，
每次调整都记录在 ADR 与变更清单（独立文档仓）。
