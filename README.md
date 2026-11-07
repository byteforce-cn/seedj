# Seedj

**写业务代码，不写基础设施。** Seedj 是面向业务系统的 Java 21 / Spring Boot 多模块基座：
命令进、查询出、双时间轴落库，权限、审计、工作流、事件总线开箱可用；内存实现与
PostgreSQL 实现在同一套接口后面，本地开发不需要装数据库。

[![ci](https://github.com/byteforce-cn/seedj/actions/workflows/ci.yml/badge.svg)](https://github.com/byteforce-cn/seedj/actions/workflows/ci.yml)
[![license](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![java](https://img.shields.io/badge/java-21-orange.svg)](https://adoptium.net/)

## 为什么用它

每个业务系统都在重复造同一批东西：命令接口的幂等、审计链、权限判定、时点查询、迁移脚本、
事件发布。Seedj 把这些收进契约层（零 Spring 依赖），业务模块只声明**对象有哪些字段**和
**命令怎么处理**，其余由构建期代码生成与运行时装配完成。

三个直接后果：

- **换存储不改业务代码**：`local` 用内存，`postgres` 用 PostgreSQL，接口不变。
- **时点查询是内置的**：每条记录同时带业务时间与记录时间，`as_of` / `recorded_as_of` 直接可查。
- **权限在声明点收口**：命令、读路径、字段脱敏只写一次，运行时按声明失败关闭。

## 5 分钟跑起来

需要 JDK 21；不需要数据库、不需要 IdP。

```bash
git clone https://github.com/byteforce-cn/seedj.git
cd seedj
./mvnw -pl seedj-server/hr -am spring-boot:run \
  -Dspring-boot.run.profiles=local
```

另开一个终端，建一名员工：

```bash
curl -s -X POST localhost:8080/commands/hr.employee.create \
  -H 'Content-Type: application/json' \
  -d '{
    "type": "hr.employee.create",
    "idempotency_key": "demo-1",
    "payload": {
      "name": "Alice",
      "hire_date": "2026-01-05",
      "employment_type": "full_time",
      "work_location": "SH",
      "org_unit_id": "11111111-1111-1111-1111-111111111111"
    }
  }'
```

```json
{"command_id":"01a1b567-…","status":"accepted","event_ids":["01a1b567-…"],
 "locator":"01a1b567-6c01-7000-aee3-c3ba90239c71","version":1}
```

用返回的 `locator` 读回来（`valid_from` 是业务时间，`recorded_at` 是记录时间）：

```bash
curl -s localhost:8080/objects/employee/01a1b567-6c01-7000-aee3-c3ba90239c71
```

```json
{"object_type":"employee","id":"01a1b567-…","version":1,
 "valid_from":"2026-01-05T00:00:00Z","recorded_at":"2026-11-07T09:46:56Z",
 "data":{"employee_no":"EMP-000001","name":"Alice","status":"pending_onboarding", …}}
```

列表（分页从 1 开始）：

```bash
curl -s "localhost:8080/api/hr/employees?page=1&size=10"
```

`local` profile 的约定：命令与对象用内存实现（重启清空）、固定 dev 调用方
`local-admin`、审计/outbox/eventbus 关闭。需要真实 PostgreSQL 时换 `postgres` profile，
见 [配置说明](docs/guide/configuration.md)。

## 一次业务改动长什么样

### 加字段

字段只有一个来源：`@SeedjObject` record。schema、OpenAPI、迁移候选都由 `seedj-codegen`
从这里生成，不再手写第二份字段列表。

```java
@SeedjObject(objectType = "employee", table = "hr_employee", view = true)
public record HrEmployeeFields(
        @SeedjField(required = true, unique = true, pattern = "^EMP-[0-9]{6,}$")
        String employeeNo,
        @SeedjField(required = true, minLength = 1)
        String name,
        @SeedjField(privacy = Privacy.SENSITIVE, masker = MaskerName.LAST4)
        String contact,
        …) {}
```

加字段 = 加一个组件 → 编译 → 审阅生成的迁移候选 → 提交。

### 加命令

命令 record + handler + 一段声明。权限、`creates()`、事件类型、需要脱敏的字段都在声明点
写死：

```java
modules.object(HrEmployeeFieldsSchema.schema())
        .command("hr.employee.create", HrEmployeeCreate.class)
            .permission("hr.employee.create")
            .creates()
            .handler(create)
            .emits("hr.employee.created")
            .redactedCommandField("id_number");
```

### 加查询

`.query(...)` 直接引用生成的视图；分页、过滤、排序与时点读取由模板提供：

```java
.query(HrEmployeeFields.view())
    .path("/api/hr/employees")
    .permission("hr.employee.read")
    .sort("employee_no");
```

### 加测试

业务规则用 `CommandHarness`：内存存储 + 固定时钟 + 指定调用方，不启动 Spring，毫秒级。

```java
CommandHarness harness = CommandHarness.of(moduleRegistry(), CALLER).clockAt(T0);

harness.execute("hr.employee.create",
        Map.of("name", "张三", "hire_date", LocalDate.parse("2026-10-01"),
               "org_unit_id", ORG_A), "rule-1");

assertThat(harness.object("employee", receipt.locator()).fields())
        .containsEntry("status", "pending_onboarding");
```

`@SpringBootTest` 只留给 HTTP、安全与数据库契约。

## 核心概念

- **命令信封**：`POST /commands/{type}`，`idempotency_key` 去重，返回 `locator` 与 `version`。
- **双时间轴**：`valid_from` / `valid_to` 是业务时间，`recorded_at` 是系统记录时间；
  读接口支持 `as_of` 与 `recorded_as_of`，可以回看"当时以为的样子"。
- **契约层零 Spring**：`core` 只依赖 Java 与 Jackson，业务规则测试不需要容器。
- **事件溯源是显式第二选择**：默认写路径是命令 + 当前视图；需要完整事件历史时再开事件存储。

## 模块

| 模块 | 作用 |
|---|---|
| `core` | 契约：错误码、时间轴、命令、IAM、工作流、SPI、`@SeedjObject` |
| `seedj-codegen` | 构建期注解处理器：schema / OpenAPI / 迁移候选 / 读视图（不进运行时） |
| `seedj-infra-local` | 内存、文件、BouncyCastle 适配器 |
| `seedj-workflow-local` | 工作流 SPI 的本地实现（库模块，不启动 server） |
| `seedj-jdbc-postgresql` | PostgreSQL 适配器、DDL、版本化迁移、生产事件存储 |
| `seedj-biz-starter` | 业务装配：认证 / 命令 / 查询 / 审计 / 事件总线 |
| `seedj-iam-starter` | IAM 装配：OIDC / SCIM / RBAC / ABAC |
| `seedj-server/{hr,iam,im}` | 可运行宿主 |
| `seedj-it` | 契约对照、并发与端到端测试 |

依赖方向：`server → starter → infra/jdbc → core`。

## 文档

- 文档站：<https://byteforce-cn.github.io/seedj/>
- [快速开始](docs/guide/quickstart.md) · [业务开发手册](docs/guide/cookbook.md) ·
  [模块与装配](docs/guide/modules.md) · [已知限制](docs/guide/known-limitations.md)

## 从源码构建

```bash
./mvnw -B -T1C verify     # 测试 + 覆盖率 + 架构约束 + 格式
```

仓库脚本默认离线构建；首次构建去掉 `MAVEN_FLAGS=-o`（或先跑一次
`./mvnw -B dependency:go-offline`）。PostgreSQL 集成测试用 Testcontainers，需要本机 Docker。

## 状态与兼容性

1.0.0 尚未发布。线格式 `/commands/{type}`、`/objects/{type}/{id}` 与错误信封
`{code,message,details}` 已冻结；Java API 在 1.0.0 前仍可能调整。

## 参与

- 贡献指南：[CONTRIBUTING.md](CONTRIBUTING.md)
- 安全漏洞：[SECURITY.md](SECURITY.md)（走私密报告，不要开公开 issue）
- 变更记录：[CHANGELOG.md](CHANGELOG.md)
- 许可：[Apache License 2.0](LICENSE)
