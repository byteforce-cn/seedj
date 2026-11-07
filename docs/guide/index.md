# 指南

Seedj 的开发者文档。第一次来，从[快速开始](quickstart.md)开始；做业务时看
[业务开发手册](cookbook.md)。

## 开始

- [快速开始](quickstart.md)：5 分钟跑起来，创建第一条命令
- [概念与术语](concepts.md)：命令、双时间轴、两条写路径

## 业务开发

- [业务开发手册](cookbook.md)：加字段、加命令、加查询、加测试
- [命令契约](command-contract.md)：请求/响应、幂等、错误映射
- [读模型](read-model.md)：分页、过滤、排序与时点读取
- [错误目录](errors.md)：错误码与处理建议
- [测试](testing.md)：`CommandHarness`、覆盖率与门禁

## 装配与集成

- [业务装配](biz-starter.md)：命令、查询、审计、事件总线
- [IAM 装配](iam-starter.md)：OIDC / SCIM / RBAC / ABAC
- [基础设施 SPI](spi.md)：新增适配器的契约

## 边界与决策

- [决策指南](decision-guide.md)：默认路径 vs 事件溯源路径
- [API 兼容性](api-compatibility.md)：兼容承诺与判定口径
- [安全](security.md)：认证、授权与数据范围
