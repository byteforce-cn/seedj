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

- [模块与装配](modules.md)：模块职责与依赖方向
- [业务装配](biz-starter.md)：命令、查询、审计、事件总线
- [IAM 装配](iam-starter.md)：OIDC / SCIM / RBAC / ABAC
- [IM 装配](im-starter.md)：长连接、帧协议、握手与在线状态
- [AI 装配](ai-starter.md)：决策账本、DecisionToken、guard 与有界 agent 循环
- [工作流 coord 适配器](workflow-coord.md)：workflow SPI 的 coord 生产实现与边界
- [上游诉求](ai-upstream.md)：AI 依赖的外部契约与 T0 收敛路径
- [基础设施 SPI](spi.md)：新增适配器的契约
- [配置说明](configuration.md)：profile 与常用配置项

## 边界与决策

- [决策指南](decision-guide.md)：默认路径 vs 事件溯源路径
- [API 兼容性](api-compatibility.md)：兼容承诺与判定口径
- [已知限制](known-limitations.md)：生产可用边界
- [安全](security.md)：认证、授权与数据范围
