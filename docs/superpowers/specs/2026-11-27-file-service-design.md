# FileService 契约与 coord 对象存储实现（设计）

日期：2026-11-27
状态：待实现
范围：`core`（SPI）、`seedj-infra-coord`（实现、自动配置、测试）、`docs/guide/configuration.md`

## 1. 意图与背景

seedj 目前没有文件能力：IM 只保存附件引用（`IMAGE` / `FILE` 的元数据），不代理二进制；
业务侧要传附件只能各自接 OSS/MinIO。coord 0.4.6 的 Java SDK 已提供对象存储数据面
（`ObjectStoreClient`: `put/get/stat/delete`），可以让应用把文件落到 coord 集群。

目标：在 `core` 定义一个与存储供应商无关的 `FileService` SPI，并在 `seedj-infra-coord`
提供基于 coord 对象存储的实现。以后切 MinIO / 阿里云 OSS 时新增适配器即可，调用方代码
和契约测试不变。

## 2. 目标 / 非目标

目标：

- `put / open / stat / delete` 四个操作，逻辑 key 寻址，流式入参；
- create-only：同一个 key 不覆盖，已存在返回 `ALREADY_EXISTS`；
- `contentType`、`size`、`createdAt`、`version` 元数据可读；
- provider 选择沿用 `seedj.<cap>.provider=coord` + `CoordProviderGuard`；
- 契约测试放 `core` 的 test-jar，任何 provider 复用；
- coord 侧单测 + 契约测试 + 可选真集群用例。

非目标（YAGNI）：

- 覆盖 / upsert、版本保留、软删除；
- `list` / 分页、预签名 URL、直传（coord 都不支持，以后按可选接口加，不进核心契约）；
- 多 bucket 路由、租户配额、后台 GC/孤儿清理；
- `seedj-infra-local` 的内存实现（coord 适配器用 fake SDK 测试即可）；
- IM 附件接线、HTTP 上传/下载端点。

## 3. 约束

- coord `ObjectStoreClient`：`put(String bucket, byte[] objectId, byte[] data[, int chunkSize])`、
  `get`、`stat`、`delete`；**已存在对象不可覆盖**（`ALREADY_EXISTS`）；没有 list、content-type、
  user metadata、预签名；SDK 整块 `byte[]` 缓冲；`put` 拒绝空数据。
- coord 服务端默认单对象上限 256MiB，单 chunk 4MiB；配额/磁盘水位返回
  `QUOTA_EXCEEDED` / `RESOURCE_EXHAUSTED`。
- coord 0.4.6 只在 `object_storage.enabled=true && kv_t.enabled=false` 时注册 v1
  `coord.storage`；现有测试夹具是 kv_t v2，不能直接复用。
- 0.4.6 的 Java SDK `TxnClient` 走 Txn v2，只在 `kv_t` 开启时可用；v1 对象存储又要求
  `kv_t.enabled=false`。两者不能同时满足，因此文件元数据不能放 coord KV。
- `core` 禁止 Spring / 监控框架（`CorePurityTest`）；新 public 类型要登记
  `config/public-api.txt`；每个模块至少一个 `package-info`；单文件 ≤ 600 行。
- 改动走分支 → PR；CI 门禁为 `verify` + 覆盖率 ratchet + Spotless + SpotBugs。

## 4. 方案对比

| 方案 | 形态 | 取舍 |
| --- | --- | --- |
| A. 薄封装 | `byte[]` 入参、无 content-type，映射 coord 四个调用 | 最省事；SPI 泄漏 coord 的内存模型，换 MinIO 仍要改调用方 |
| B. 可移植核心 + 可选能力接口 | S3 形状的四个方法；list/预签名以后做成独立可选接口 | 调用方与 provider 解耦；coord 侧多一个元数据对象 |
| C. 自建文件平台 | bucket/版本/列举/预签名/配额全上 | v1 过度设计，且 list/预签名 coord 无法实现 |

采用 **B**。关键决策：

1. **create-only**：coord 对象不可覆盖；S3/MinIO/OSS 可用条件写
   （`If-None-Match: *` / `x-oss-forbid-overwrite`）实现同一语义。若契约写成 upsert，
   coord 只能 delete+put，中途失败会丢文件。需要替换时由调用方显式 `delete` 后 `put`。
2. **`InputStream` + `contentLength`**：SPI 面向大文件；coord 实现必须整块缓冲，因此设
   `max-object-bytes`（默认 32MiB，硬顶 256MiB）。以后 MinIO 实现可以真流式，调用方不变。
3. **元数据对象**：coord 只有 size/revision，没有 content-type/时间。coord 侧为每个文件写第二个
   对象：`sha256(scope, key)` 定位的元数据对象（JSON），内容对象用随机 objectId。数据对象先写，
   元数据对象 create-only 后写，后者是创建成功的线性化点；已存在时只删除本次的数据对象。
   `stat` 只下载这个很小的元数据对象；元数据不放 coord KV——0.4.6 的 SDK Txn 是 v2（要求
   `kv_t` 开启），而 v1 对象存储要求 `kv_t` 关闭，两者不能同时满足。
4. **随机数据 objectId**：每次 `put` 生成 16 字节随机 id，避免重试/并发撞上已有对象；key 与
   objectId 的映射只在元数据对象里。删除后重建自然拿到新 id。
5. **list / 预签名不进核心接口**：coord 无对应原语；以后用 `FileLister` / `FileUrlSigner`
   可选接口扩展，核心四个方法保持所有 provider 可实现。

## 5. 接口

包 `cn.byteforce.seedj.file`（core）：

```java
public interface FileService {

    FileMetadata put(String key, String contentType, long contentLength, InputStream content);

    /** 调用方负责 close；不存在抛 NOT_FOUND。 */
    InputStream open(String key);

    Optional<FileMetadata> stat(String key);

    /** 不存在返回 false。 */
    boolean delete(String key);
}

public record FileMetadata(
        String key,
        long size,
        String contentType,   // 可空
        String version,       // 不透明：coord revision / S3 ETag
        Instant createdAt) {}
```

`FileKeys.requireValid(String key)` 承载 key 规则（所有 provider 共用）：
非空、≤512 字符、UTF-8 ≤1024 字节、无控制字符 / NUL、不以 `/` 开头或结尾、无空段、
无 `.` / `..` 段。

`FileException extends InfrastructureException`，`FileErrorCode implements ErrorDescriptor`：

| 错误码 | 分类 | 可重试 | 场景 |
| --- | --- | --- | --- |
| `INVALID_KEY` | CLIENT_ERROR | 否 | key 不符合规则 |
| `INVALID_REQUEST` | CLIENT_ERROR | 否 | 空文件、长度不符、contentType 过长 |
| `NOT_FOUND` | CLIENT_ERROR | 否 | `open` 的目标不存在 |
| `ALREADY_EXISTS` | CLIENT_ERROR | 否 | create-only 冲突 |
| `TOO_LARGE` | LIMIT_EXCEEDED | 否 | 超过 `max-object-bytes` / 服务端对象上限 |
| `PROVIDER_UNAVAILABLE` | BACKEND_ERROR | 是 | agent/网络/选主/安全时间戳滞后 |
| `INTERNAL_ERROR` | BACKEND_ERROR | 否 | 元数据对象损坏、元数据指向缺失的数据对象 |

语义：

- `put`：成功返回元数据；key 已存在抛 `ALREADY_EXISTS`；空内容抛 `INVALID_REQUEST`；
  流长度与 `contentLength` 不一致（短或长）抛 `INVALID_REQUEST`；超过上限抛 `TOO_LARGE`，
  **不写入**。
- `open`：不存在抛 `NOT_FOUND`；返回的流读完即 EOF，`close` 幂等。
- `stat`：不存在返回 `Optional.empty()`。
- `delete`：存在返回 true（删除元数据；数据对象清理尽力而为），不存在 false；重复删除 false。
- 实现线程安全；`FileService` 不持有需要关闭的客户端（coord client 由自动配置管理）。

## 6. coord 实现

`CoordFileService`（package-private）：

- 构造参数：`ObjectStoreClient`、`CoordEndpoint`、`scope`（命名空间）、`bucket`、
  `maxObjectBytes`、`Clock`。
- 数据对象：`<bucket>` 下随机 16 字节 objectId；元数据对象：`sha256("seedj-file-meta\0" + scope +
  "\0" + key)`（32 字节，scope = coord 命名空间），同一 bucket。
- 元数据对象内容（JSON，随模块版本走，不承诺跨版本兼容）：

  ```json
  {"key":"...","objectId":"<hex>","size":123,"contentType":"image/png",
   "revision":42,"createdAtEpochMilli":1790000000000}
  ```

- `put`：校验 → 精确读取 `contentLength` 字节（上限内）→ 写数据对象 → create-only 写元数据
  对象；元数据已存在时只删除本次数据对象并抛 `ALREADY_EXISTS`；元数据写异常同样回滚数据对象后
  映射错误。
- `open`：读元数据对象 → `objects.get` 数据对象 → `ByteArrayInputStream`；数据对象缺失视为
  内部不一致。
- `stat`：读元数据对象 → `FileMetadata`（`version` = 数据对象 revision 十进制字符串）。
- `delete`：读元数据取得数据 objectId → 删除元数据对象（线性化点，未删除返回 false）→
  尽力删除数据对象。数据清理失败只记 WARN，元数据已不可见，最多留下孤儿数据对象。
- 错误映射集中在 `CoordErrors.file(...)`：
  `NOT_FOUND→NOT_FOUND`、`ALREADY_EXISTS→ALREADY_EXISTS`、
  `INVALID_ARGUMENT/OUT_OF_RANGE→INVALID_REQUEST`、`MESSAGE_TOO_LARGE/QUOTA_EXCEEDED→TOO_LARGE`、
  `RESOURCE_EXHAUSTED/UNAVAILABLE/DEADLINE_EXCEEDED/AGENT_UNAVAILABLE/NOT_LEADER/SAFE_TS_LAG/EPOCH_STALE/TXN_*→PROVIDER_UNAVAILABLE`、
  其余 `INTERNAL_ERROR`。
- 校验：bucket 非空、≤255 字节、无 `/`、无 NUL；`max-object-bytes` ∈ [1, 256MiB]。

自动配置：

- `seedj.file.provider=coord` 时装配 `FileService`，`@ConditionalOnMissingBean`；
- `seedj.file.provider` 加入 `CoordProviderGuard` 的冲突清单；
- 配置项：

  | 配置 | 默认 | 说明 |
  | --- | --- | --- |
  | `seedj.coord.file.bucket` | `seedj-files` | coord bucket 名 |
  | `seedj.coord.file.max-object-bytes` | `33554432`（32MiB） | 单文件上限；SDK 整块缓冲，别开太大 |

## 7. 测试

- `core`：`FileServiceContractTest`（抽象，`newFileService()` 由实现提供）：
  二进制往返、contentType/size 回读、重复 put、缺失 stat/open/delete、删除后重建、
  空内容、非法 key、长度不符（短/长）、超限不落盘、并发 put 只成功一个。
- `seedj-infra-coord`：
  - `fixture/FakeObjectStore`：内存版 `ObjectStoreClient`，保留 create-only/缺失语义，
    可注入指定次序调用的错误码；
  - `CoordFileServiceContractTest extends FileServiceContractTest`；
  - `CoordFileServiceTest`：key/contentType 校验、`max-object-bytes`、短读/长读、
    元数据写失败回滚数据对象、元数据损坏、数据对象缺失、错误码映射；
  - `CoordAutoConfigurationTest`：装配 / 非 coord 不装配 / guard 拦截；
  - `CoordFileServiceClusterTest`（Docker/Linux 才跑）：独立 v1 夹具
    （`[object_storage] enabled=true`、`[kv_t] enabled=false`）跑真集群 put/get/stat/delete。
- 门禁：`./mvnw -o -B -pl core,seedj-infra-coord -am verify`；
  `config/public-api.txt` 重新生成；覆盖率基线按 CI 实测登记。

## 8. 已知限制（写进文档）

- coord 实现整块缓冲，默认上限 32MiB、硬顶 256MiB；更大的文件要等 MinIO/OSS 适配器。
- create-only，没有覆盖；要替换请先 `delete`。
- 没有 list / 预签名；coord 侧没有对应原语。
- 数据对象写入成功、元数据对象写入前崩溃会留下不可见孤儿数据对象；v1 不做后台 GC，
  需要时由运维修。
- `stat` 需要下载很小的元数据对象（一次额外 GET），不依赖任何索引。
- 同一 key 并发 put 会都上传数据对象，只有元数据对象 create-only 成功，另一个立即删除自己的数据对象。
- `createdAt` 用实例本地时钟；多实例时钟漂移会反映在元数据里。
- coord v0.4.6 的 v1 对象存储要求 `object_storage.enabled=true` 且 `kv_t.enabled=false`；
  v2 集群不注册 v1 `coord.storage`（SDK 目前也只有 v1 客户端）。

## 9. 验收

1. 契约测试在 coord 实现上全绿；未来 MinIO/OSS 适配器可直接复用。
2. `seedj.file.provider=coord` 且 `seedj.coord.enabled` 未开时启动失败；开启后 `FileService`
   可注入。
3. `docs/guide/configuration.md`、`seedj-infra-coord/README.md`、`package-info` 与
   `config/public-api.txt` 同步。
4. 受影响模块 `verify` 通过，CI 全绿后合并。
