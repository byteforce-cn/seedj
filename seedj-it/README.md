# seedj-it

跨模块集成、契约对照与架构/文档门禁。本模块只有测试代码，不产出运行期制品。

## 测试内容

| 测试 | 作用 |
|---|---|
| `ArchitectureTest` | core 无 Spring/JDBC；biz 不反向依赖 IAM；内存实现不依赖 JDBC |
| `SplitPackageTest` | 同一个包只属于一个模块 |
| `PackageCycleTest` | 包级依赖无环（只剩 parent↔spi 白名单） |
| `PublicApiSurfaceTest` | `config/public-api.txt` 与编译产物一致 |
| `FileSizeGuardTest` | 主源码单文件 ≤ 600 行 |
| `PackageInfoTest` | 每个模块有 package-info，说明职责与依赖方向 |
| `DocLinkTest` / `LicensePresenceTest` | 文档链接可解析、许可证存在 |
| `ApiAlignmentTest` / `CommandApiConformanceTest` | 控制器与 OpenAPI/命令目录一致 |
| `HrEmployeeSampleTest` / `HrServerPostgresIT` | HR 活样板与 PostgreSQL 迁移 |
| `SeedjIT`、`IdIT`、`OidcIT`、`TransitIT`、`EventBusIT`、`EventConsumeIT` | 多服务集群端到端 |

## 运行

```bash
./mvnw -o -B -pl seedj-it -am test
./mvnw -o -B -pl seedj-it -am verify -Dit.test='SeedjIT,OidcIT,EventBusIT'
```

架构门禁依赖各模块已编译；`-am` 会先构建依赖模块。Docker 只在使用 Testcontainers
的测试中需要。
