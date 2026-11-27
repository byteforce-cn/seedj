# FileService SPI 与 coord 对象存储实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `core` 定义与供应商无关的 `FileService` SPI，并在 `seedj-infra-coord` 提供基于 coord 对象存储的实现、自动配置与契约测试。

**Architecture:** core 放四个方法的 S3 形状契约（create-only、流式入参、元数据）与错误模型；`seedj-infra-coord` 用 coord 的 `ObjectStoreClient` 存数据对象，并用 `sha256(scope,key)` 定位的元数据对象做 create-only 的创建线性化点；真集群用例用独立 v1 夹具（coord 0.4.6 的 v1 对象存储要求 `kv_t.enabled=false`）。

**Tech Stack:** Java 21、Maven wrapper、Spring Boot 自动配置、coord-java-sdk 0.4.6、JUnit 5 + AssertJ、Testcontainers（真集群用例）。

**Spec:** `docs/superpowers/specs/2026-11-27-file-service-design.md`

**Ruling（实现期）:** 元数据从「coord KV sidecar」改成「同 bucket 的元数据对象」。原因：coord 0.4.6
的 Java SDK `TxnClient` 是 Txn v2（要求 `kv_t` 开启），而 v1 对象存储要求 `kv_t` 关闭，sidecar
在真集群上不可用。代价：`stat` 多一次小对象 GET；收益：实现只依赖 `coord.storage`，v1/v2 集群都能跑。

## Global Constraints

- `core` 不得引入 Spring / 监控框架（`CorePurityTest`）；SPI 只用 JDK 类型。
- create-only：同 key 第二次 `put` 必须 `ALREADY_EXISTS`，且不得破坏第一个文件。
- 空内容（`contentLength == 0`）→ `INVALID_REQUEST`；流长度必须与 `contentLength` 严格一致。
- key 规则：非空、≤512 字符、UTF-8 ≤1024 字节、无控制字符/NUL、不以 `/` 开头或结尾、无空段、无 `.`/`..` 段。
- coord 实现整块缓冲：`max-object-bytes` 默认 `33554432`（32MiB），取值 `[1, 268435456]`（256MiB）。
- 错误码固定为 `INVALID_KEY / INVALID_REQUEST / NOT_FOUND / ALREADY_EXISTS / TOO_LARGE / PROVIDER_UNAVAILABLE / INTERNAL_ERROR`。
- 新增 public 类型必须进 `config/public-api.txt`；每个新包要有 `package-info.java`；单文件 ≤600 行。
- 提交信息用 `feat:` / `test:` / `docs:` 前缀；改动走分支 `feat/file-service-coord` → PR。

## Review Focus

- 同 key 冲突时只回滚**本次**写入的数据对象，不碰已有文件的数据/元数据对象（`CoordFileServiceTest.sameKeyConflictRollsBackOwnDataObject`）。
- 精确长度：短流、长流都必须拒绝且不落盘（契约测试 `rejectsStreamShorterThanDeclaredLength`、`rejectsStreamLongerThanDeclaredLength`）。
- 超限在写入前失败，fake 的 `putCount()` 保持 0（`oversizeIsRejectedBeforeWriting`）。
- `delete` 与重建竞争：删除只删自己读到的元数据对象与对应数据 objectId，不碰重建后的新数据对象（`deleteRemovesFileAndIsIdempotent`、`deleteToleratesDataBlobDeleteFailure`）。
- 元数据对象损坏 / 元数据指向缺失数据对象 → `INTERNAL_ERROR`，不能是 `NOT_FOUND` 或 NPE（`corruptRecordIsInternalError`、`missingBlobIsInternalError`）。

---

### Task 1: core SPI、错误模型与 key 校验

**Files:**

- Create: `core/src/main/java/cn/byteforce/seedj/file/FileService.java`
- Create: `core/src/main/java/cn/byteforce/seedj/file/FileMetadata.java`
- Create: `core/src/main/java/cn/byteforce/seedj/file/FileKeys.java`
- Create: `core/src/main/java/cn/byteforce/seedj/file/FileErrorCode.java`
- Create: `core/src/main/java/cn/byteforce/seedj/file/FileException.java`
- Create: `core/src/main/java/cn/byteforce/seedj/file/package-info.java`
- Test: `core/src/test/java/cn/byteforce/seedj/file/FileKeysTest.java`
- Test: `core/src/test/java/cn/byteforce/seedj/file/FilePackageIndependenceTest.java`
- Modify: `config/public-api.txt`

**Interfaces:**

- Produces:
  - `interface FileService { FileMetadata put(String key, String contentType, long contentLength, InputStream content); InputStream open(String key); Optional<FileMetadata> stat(String key); boolean delete(String key); }`
  - `record FileMetadata(String key, long size, String contentType, String version, Instant createdAt)`
  - `final class FileKeys { static void requireValid(String key) }`
  - `enum FileErrorCode implements ErrorDescriptor { INVALID_KEY, INVALID_REQUEST, NOT_FOUND, ALREADY_EXISTS, TOO_LARGE, PROVIDER_UNAVAILABLE, INTERNAL_ERROR }`
  - `class FileException extends InfrastructureException { FileErrorCode getErrorCode(); }`

- [ ] **Step 1: 写失败的 key 规则测试**

```java
class FileKeysTest {

    @Test
    void acceptsNestedKey() {
        FileKeys.requireValid("attachments/2026/report.pdf");
        FileKeys.requireValid("a".repeat(512));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "/a", "a/", "a//b", "a/./b", "a/../b", "a\u0000b", "a\tb"})
    void rejectsInvalidKeys(String key) {
        assertThatThrownBy(() -> FileKeys.requireValid(key))
                .isInstanceOfSatisfying(FileException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.INVALID_KEY);
                    assertThat(e.isRetryable()).isFalse();
                });
    }

    @Test
    void rejectsKeysLongerThan512Characters() {
        assertThatThrownBy(() -> FileKeys.requireValid("a".repeat(513)))
                .isInstanceOf(FileException.class);
    }

    @Test
    void rejectsKeysLongerThan1024Utf8Bytes() {
        assertThatThrownBy(() -> FileKeys.requireValid("中".repeat(342)))
                .isInstanceOf(FileException.class);
    }

    @Test
    void providerUnavailableIsRetryable() {
        FileException failure = new FileException(FileErrorCode.PROVIDER_UNAVAILABLE, "down");
        assertThat(failure.isRetryable()).isTrue();
        assertThat(failure.category()).isEqualTo(ErrorCategory.BACKEND_ERROR);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -o -B -pl core test -Dtest=FileKeysTest`
Expected: 编译失败，`FileKeys` / `FileException` 不存在。

- [ ] **Step 3: 实现 core 类型**

`FileErrorCode` 按设计表实现 `ErrorDescriptor`；`FileException` 镜像 `LockException`（构造器、`getErrorCode`）；`FileMetadata` 用 record；`FileService` 接口带 Javadoc 说明 create-only、流长度、`open` 关闭责任；`FileKeys.requireValid` 按 Global Constraints 校验并抛 `INVALID_KEY`；`package-info` 说明职责（文件字节契约，不依赖其它能力）与依赖方向。`FilePackageIndependenceTest` 照 `LockPackageIndependenceTest` 写：断言 `file` 包不 import `cn.byteforce.seedj.cqrs|eventbus|outbox|im`。

- [ ] **Step 4: 运行 core 测试**

Run: `./mvnw -o -B -pl core test`
Expected: `FileKeysTest`、`CorePurityTest`、`FilePackageIndependenceTest` 全绿。

- [ ] **Step 5: 更新公共 API 清单**

Run: `./mvnw -o -B -pl seedj-it -am test -Dtest=PublicApiSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false -Dseedj.api.update=true`
Expected: `config/public-api.txt` 新增 `core|cn.byteforce.seedj.file|FileErrorCode`、`FileException`、`FileKeys`、`FileMetadata`、`FileService` 五行；再跑一次不带 `-Dseedj.api.update` 应通过。

- [ ] **Step 6: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/file core/src/test/java/cn/byteforce/seedj/file config/public-api.txt
git commit -m "feat(core): add FileService SPI and error model"
```

---

### Task 2: coord 适配器与契约测试

**Files:**

- Create: `core/src/test/java/cn/byteforce/seedj/file/FileServiceContractTest.java`
- Create: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/fixture/FakeObjectStore.java`
- Create: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordFileServiceContractTest.java`
- Create: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordFileServiceTest.java`
- Create: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordFileRecord.java`
- Create: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordFileService.java`
- Modify: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordErrors.java`

**Interfaces:**

- Consumes: Task 1 的全部类型；`CoordJson`、`CoordEndpoint`。
- Produces:
  - `abstract class FileServiceContractTest { protected abstract FileService newFileService(); }`（12 个用例，见 Step 1）
  - `final class FakeObjectStore implements ObjectStoreClient`：`long putCount()`、`int objectCount()`、`boolean contains(String bucket, byte[] objectId)`、`void failNextPut/Get/Delete(ErrorCode)`、`void failPutOnCall(long, ErrorCode)`、`void failDeleteOnCall(long, ErrorCode)`、`void remove(String bucket, byte[] objectId)`、`void clear()`
  - `final class CoordFileService implements FileService`，构造函数 `CoordFileService(ObjectStoreClient objects, CoordEndpoint endpoint, String scope, String bucket, long maxObjectBytes, Clock clock)`；包内静态 `byte[] metadataObjectId(String scope, String key)`
  - `record CoordFileRecord(String key, String objectId, long size, String contentType, long revision, long createdAtEpochMilli)`
  - `CoordErrors.file(CoordException e, CoordEndpoint endpoint, String action)` → `FileException`

- [ ] **Step 1: 写 core 契约测试（先失败）**

```java
public abstract class FileServiceContractTest {

    protected abstract FileService newFileService();

    private FileService service;

    @BeforeEach
    void setUp() { service = newFileService(); }

    @Test
    void roundTripsBinaryContent() { /* 0x00..0xFF 全部字节，open 读回逐字节相等 */ }

    @Test
    void reportsSizeContentTypeAndKey() { /* stat 的 size/contentType/key 与 put 一致 */ }

    @Test
    void secondPutOnSameKeyFailsWithoutTouchingFirst() {
        service.put("contract/one.bin", "application/octet-stream", 4, stream("AAAA"));

        assertThatThrownBy(() -> service.put("contract/one.bin", "text/plain", 3, stream("BBB")))
                .isInstanceOfSatisfying(FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.ALREADY_EXISTS));

        assertThat(readAll(service.open("contract/one.bin"))).isEqualTo("AAAA".getBytes());
    }

    @Test
    void statOfMissingKeyIsEmpty() { /* stat("contract/missing") empty */ }

    @Test
    void openOfMissingKeyThrowsNotFound() { /* NOT_FOUND */ }

    @Test
    void deleteRemovesFileAndIsIdempotent() {
        service.put("contract/delete.bin", "text/plain", 3, stream("abc"));
        assertThat(service.delete("contract/delete.bin")).isTrue();
        assertThat(service.delete("contract/delete.bin")).isFalse();
        assertThat(service.stat("contract/delete.bin")).isEmpty();
        assertThatThrownBy(() -> service.open("contract/delete.bin"))
                .isInstanceOfSatisfying(FileException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.NOT_FOUND));
    }

    @Test
    void recreateAfterDeleteSucceeds() { /* delete 后同 key put 成功，内容是新值 */ }

    @Test
    void rejectsEmptyContent() { /* contentLength=0 → INVALID_REQUEST，stat 仍 empty */ }

    @Test
    void rejectsInvalidKey() { /* " " 与 "a/../b" → INVALID_KEY */ }

    @Test
    void rejectsStreamShorterThanDeclaredLength() { /* 声明 5 实际 3 → INVALID_REQUEST，stat empty */ }

    @Test
    void rejectsStreamLongerThanDeclaredLength() { /* 声明 3 实际 4 → INVALID_REQUEST，stat empty */ }

    @Test
    void concurrentPutHasSingleWinner() throws Exception {
        // 两个线程同一 CountDownLatch 起跑，各自 put 同一 key（不同内容）：
        // 一个正常返回，另一个 FileException.ALREADY_EXISTS；stat 的 size 等于胜者大小。
    }
}
```

- [ ] **Step 2: 写 fake 与契约测试子类，确认失败**

`FakeObjectStore` 在内存 `Map<BucketKey, byte[]>` 上实现 `ObjectStoreClient`：`put` 已存在抛 `CoordException(ErrorCode.ALREADY_EXISTS)`、空数据抛 `CoordException(ErrorCode.INTERNAL)`；`get` 缺失抛 `NOT_FOUND`；`stat` 缺失返回 empty；`delete` 幂等返回 `DeleteResult(deleted, revision)`；`failNext*` 只影响下一次调用。

`CoordFileServiceContractTest extends FileServiceContractTest`，`newFileService()` 返回：

```java
new CoordFileService(
        new FakeObjectStore(),
        new CoordEndpoint("127.0.0.1:19527"),
        "seedj:contract",
        "seedj-files",
        1024 * 1024,
        Clock.systemUTC());
```

Run: `./mvnw -o -B -pl core,seedj-infra-coord -am test -Dtest=CoordFileServiceContractTest`
Expected: 编译失败（`CoordFileService` 不存在）。

- [ ] **Step 3: 实现 `CoordFileRecord`、`CoordFileService`、`CoordErrors.file`**

按设计 §6 实现：

- key 校验先调 `FileKeys.requireValid`；`contentType` 非空时 ≤255 字符；
- 精确读：`content.readNBytes((int) contentLength)`（上限已保证 ≤256MiB）+ 再 `read()` 一次判断长流；短流或长流抛 `INVALID_REQUEST`；
- 每次 `put` 生成 16 字节随机数据 objectId（`UUID.randomUUID()` 两个 long 写进 `ByteBuffer`）；先 `objects.put(bucket, dataObjectId, data)`，再把 `CoordFileRecord` 写到 `metadataObjectId(scope, key)`（`sha256("seedj-file-meta\0" + scope + "\0" + key)`）；
- 元数据对象已存在或写失败时，对本次 dataObjectId 调 `objects.delete` 并吞掉删除异常，然后抛 `ALREADY_EXISTS` / 映射后的 `FileException`；
- `open` / `stat` 读元数据对象（`NOT_FOUND` → empty / `NOT_FOUND`），再 `objects.get` 数据对象；数据对象缺失 → `INTERNAL_ERROR`；
- 记录 JSON 解析失败 → `INTERNAL_ERROR`；
- `delete` 先读元数据取得 dataObjectId，删元数据对象（返回 false 则 false），再尽力 `objects.delete` 数据对象（失败只 `logger.warn`）；
- 所有 `CoordException` 经 `CoordErrors.file(e, endpoint, action)` 转换；映射按设计 §6。

- [ ] **Step 4: 运行契约测试**

Run: `./mvnw -o -B -pl core,seedj-infra-coord -am test -Dtest=CoordFileServiceContractTest`
Expected: 12 个用例全绿。

- [ ] **Step 5: 写适配器单测（先失败再实现）**

`CoordFileServiceTest` 覆盖 Review Focus 与边界：

```java
@Test void sameKeyConflictRollsBackOwnDataObject() { /* 第二次 put → ALREADY_EXISTS；已有数据/元数据对象不变 */ }
@Test void oversizeIsRejectedBeforeWriting() { /* max=4，put 5 字节 → TOO_LARGE，fake.putCount()==0 */ }
@Test void lengthMismatchLeavesNoBlob() { /* 短流/长流 → INVALID_REQUEST，putCount()==0 */ }
@Test void dataWriteFailureLeavesNothing() { /* 数据对象 put 失败 → PROVIDER_UNAVAILABLE，fake 空 */ }
@Test void metadataWriteFailureRollsBackDataObject() { /* failPutOnCall(2) → 数据已写、元数据失败，回滚数据对象 */ }
@Test void corruptRecordIsInternalError() { /* 预置 "{oops" 元数据对象 → stat/open 抛 INTERNAL_ERROR */ }
@Test void missingBlobIsInternalError() { /* 元数据指向不存在的 objectId → open INTERNAL_ERROR */ }
@Test void deleteToleratesDataBlobDeleteFailure() { /* failDeleteOnCall(2, INTERNAL) → delete true，stat empty */ }
@Test void mapsQuotaExceededToTooLarge() { /* failNextPut(QUOTA_EXCEEDED) → TOO_LARGE */ }
@Test void mapsUnavailableToProviderUnavailable() { /* failNextGet(UNAVAILABLE) → PROVIDER_UNAVAILABLE，可重试 */ }
```

Run: `./mvnw -o -B -pl seedj-infra-coord -am test -Dtest=CoordFileServiceTest`
Expected: 全绿。

- [ ] **Step 6: 提交**

```bash
git add core/src/test/java/cn/byteforce/seedj/file/FileServiceContractTest.java \
  seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordFile*.java \
  seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordErrors.java \
  seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord
git commit -m "feat(coord): implement FileService over coord object storage"
```

---

### Task 3: 自动配置、配置项与 guard

**Files:**

- Modify: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordProperties.java`（新增 `File` 段与 `getFile()`）
- Modify: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordAutoConfiguration.java`（bean + 校验）
- Modify: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordProviderGuard.java`（`seedj.file.provider`）
- Modify: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordAutoConfigurationTest.java`

**Interfaces:**

- Produces:
  - `CoordProperties.File { String bucket; long maxObjectBytes; }`（默认 `seedj-files` / `33554432`）
  - `@Bean FileService coordFileService(CoordClient, CoordProperties, Environment)`，条件 `seedj.file.provider=coord`
  - guard 冲突清单包含 `seedj.file.provider`

- [ ] **Step 1: 写失败的装配测试**

```java
@Test
void createsFileServiceWhenCoordFileSelected() {
    runner.withPropertyValues("seedj.coord.enabled=true", "seedj.file.provider=coord")
            .run(context -> {
                assertThat(context).hasSingleBean(FileService.class);
                assertThat(context.getBean(FileService.class)).isInstanceOf(CoordFileService.class);
            });
}

@Test
void bindsFileTuning() {
    runner.withPropertyValues("seedj.coord.enabled=true", "seedj.file.provider=coord",
                    "seedj.coord.file.bucket=hr-files",
                    "seedj.coord.file.max-object-bytes=1048576")
            .run(context -> {
                CoordProperties.File file = context.getBean(CoordProperties.class).getFile();
                assertThat(file.getBucket()).isEqualTo("hr-files");
                assertThat(file.getMaxObjectBytes()).isEqualTo(1048576);
            });
}

@Test
void leavesFileServiceToOtherProviders() {
    runner.withPropertyValues("seedj.coord.enabled=true", "seedj.file.provider=minio")
            .run(context -> assertThat(context).doesNotHaveBean(FileService.class));
}

@Test
void failsFastWhenFileProviderSelectedWithoutCoordEnabled() {
    runner.withPropertyValues("seedj.file.provider=coord")
            .run(context -> {
                assertThat(context.getStartupFailure())
                        .hasRootCauseInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("seedj.coord.enabled");
            });
}

@Test
void rejectsInvalidFileConfig() {
    // bucket 含 '/'、max-object-bytes=0、max-object-bytes=268435457 各跑一次，断言启动失败
}
```

Run: `./mvnw -o -B -pl seedj-infra-coord -am test -Dtest=CoordAutoConfigurationTest`
Expected: 失败（`getFile()` / bean 不存在）。

- [ ] **Step 2: 实现配置与装配**

`CoordProperties.File` 字段 + getter/setter；`CoordAutoConfiguration` 新增：

```java
@Bean
@ConditionalOnMissingBean
@ConditionalOnProperty(prefix = "seedj.file", name = "provider", havingValue = "coord")
public FileService coordFileService(
        CoordClient client, CoordProperties properties, Environment environment) {
    CoordProperties.File file = properties.getFile();
    return new CoordFileService(
            client.objectStore(),
            CoordEndpoint.of(properties),
            CoordNamespaces.of(properties, environment),
            file.getBucket(),
            file.getMaxObjectBytes(),
            Clock.systemUTC());
}
```

`CoordAutoConfiguration.validate` 增加 bucket（非空、≤255 UTF-8 字节、无 `/`、无 NUL）与 `maxObjectBytes` 范围校验；`CoordProviderGuard.PROVIDER_KEYS` 加 `seedj.file.provider`。

- [ ] **Step 3: 运行装配测试**

Run: `./mvnw -o -B -pl seedj-infra-coord -am test -Dtest=CoordAutoConfigurationTest`
Expected: 全绿。

- [ ] **Step 4: 提交**

```bash
git add seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordProperties.java \
  seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordAutoConfiguration.java \
  seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordProviderGuard.java \
  seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordAutoConfigurationTest.java
git commit -m "feat(coord): wire FileService auto-configuration and guard"
```

---

### Task 4: 真集群 v1 夹具与集群用例

**Files:**

- Create: `seedj-infra-coord/src/test/resources/coord/server-v1.toml`
- Create: `seedj-infra-coord/src/test/resources/coord/agent-v1.toml`
- Modify: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/fixture/CoordCluster.java`（新增 `startV1WithObjectStorage()`）
- Create: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordFileServiceClusterTest.java`

**Interfaces:**

- Produces: `static CoordCluster startV1WithObjectStorage()`；其余 API 与 `start()` 相同（`client()`、`agentHost()`、`agentPort()`、`close()`）。

- [ ] **Step 1: 写集群用例**

```java
class CoordFileServiceClusterTest {

    @Test
    void fileRoundTripOnRealV1Cluster() {
        try (CoordCluster cluster = CoordCluster.startV1WithObjectStorage()) {
            FileService files = service(cluster);
            byte[] payload = new byte[1024 * 1024];
            new Random(7).nextBytes(payload);

            FileMetadata put = files.put("cluster/file.bin", "application/octet-stream",
                    payload.length, new ByteArrayInputStream(payload));
            assertThat(put.size()).isEqualTo(payload.length);
            assertThat(files.stat("cluster/file.bin")).get()
                    .extracting(FileMetadata::contentType)
                    .isEqualTo("application/octet-stream");
            assertThat(files.open("cluster/file.bin").readAllBytes()).isEqualTo(payload);

            assertThatThrownBy(() -> files.put("cluster/file.bin", "text/plain", 1,
                    new ByteArrayInputStream(new byte[] {1})))
                    .isInstanceOfSatisfying(FileException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FileErrorCode.ALREADY_EXISTS));

            assertThat(files.delete("cluster/file.bin")).isTrue();
            assertThat(files.stat("cluster/file.bin")).isEmpty();
        }
    }
}
```

`service(cluster)` 构造与 `CoordFileServiceContractTest` 相同，但用 `cluster.client().objectStore()`、`CoordTestFixture.clusterEndpoint(cluster)`、scope `"seedj:cluster-test"`、`Clock.systemUTC()`。

Run: `./mvnw -o -B -pl seedj-infra-coord -am test -Dtest=CoordFileServiceClusterTest`
Expected: 有 Docker 时失败（夹具方法不存在）；无 Docker 时同样先编译失败。

- [ ] **Step 2: 加 v1 夹具资源**

`server-v1.toml`：复制 `server.toml`，把 `[kv_t] enabled = true` 改为 `false`（或整段删掉），`[mq_v2] enabled = false`，追加：

```toml
[object_storage]
enabled = true
max_object_size_bytes = 268435456
chunk_size_bytes = 4194304
```

`agent-v1.toml`：复制 `agent.toml`，`data_plane = "v1"`，`[services]` 保留 `idgen = true`、`lock = true`，其余 `false`（storage 代理不受 `[services]` 开关约束）。

- [ ] **Step 3: 让夹具支持 v1 变体**

把 `start(boolean auth)` 的参数换成模式枚举（`V2`、`AUTH_V2`、`V1_STORAGE`），`render` 的 classpath 资源按模式选择 `coord/server.toml` vs `coord/server-v1.toml`、`coord/agent.toml` vs `coord/agent-v1.toml`；`awaitIdgen` / `awaitKv` 保持。`startV1WithObjectStorage()` 调 `start(Mode.V1_STORAGE)`。

Run: `./mvnw -o -B -pl seedj-infra-coord -am test -Dtest=CoordFileServiceClusterTest`
Expected: Linux + Docker 时通过；否则按现有夹具惯例 `Assumptions` 跳过。

- [ ] **Step 4: 提交**

```bash
git add seedj-infra-coord/src/test/resources/coord/server-v1.toml \
  seedj-infra-coord/src/test/resources/coord/agent-v1.toml \
  seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/fixture/CoordCluster.java \
  seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordFileServiceClusterTest.java
git commit -m "test(coord): cover FileService on a v1 object-storage cluster"
```

---

### Task 5: 文档、覆盖率与全量门禁

**Files:**

- Modify: `docs/guide/configuration.md`
- Modify: `seedj-infra-coord/README.md`
- Modify: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/package-info.java`
- Modify: `config/coverage-baseline.json`（仅当实测低于基线时按实测上调；不允许下调）
- Modify: `config/public-api.txt`（若 Task 1 之后还有新增 public 类型）

**Interfaces:**

- 无新代码接口。

- [ ] **Step 1: 更新文档**

`configuration.md` 的 coord 表格加三行：

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `seedj.file.provider` | 空 | `coord` 选择 coord 对象存储文件服务 |
| `seedj.coord.file.bucket` | `seedj-files` | blob 所在 bucket |
| `seedj.coord.file.max-object-bytes` | `33554432` | 单文件上限（SDK 整块缓冲）；取值 ≤256MiB |

再加一段说明：create-only（同 key 需先 delete）、元数据对象、无 list/预签名、v0.4.6 要求 `object_storage.enabled=true` 且 `kv_t.enabled=false`。

`README.md` 的「已交付」加 `FileService` 与配置示例；`package-info` 的能力清单加「文件」。

- [ ] **Step 2: 跑受影响模块全量验证**

Run: `./mvnw -o -B -pl core,seedj-infra-coord -am verify`
Expected: 全绿；若覆盖率 gate 失败，运行 `bash config/ci/check-coverage.sh` 后按实测更新基线（只上调）。

- [ ] **Step 3: 跑公共 API 与格式门禁**

Run:

```bash
./mvnw -o -B -pl seedj-it -am test -Dtest=PublicApiSurfaceTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -B spotless:check
```

Expected: 全绿。

- [ ] **Step 4: 提交**

```bash
git add docs/guide/configuration.md seedj-infra-coord/README.md \
  seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/package-info.java \
  config/public-api.txt config/coverage-baseline.json
git commit -m "docs(coord): document FileService contract and limits"
```

---

## 自检

- 设计 §5 的每个错误码都有测试（Task 1/2）；§6 的每个实现点对应 Task 2 步骤；§7 的测试矩阵对应 Task 2/3/4；§8 的限制写进 Task 5。
- 签名一致性：`put(String, String, long, InputStream)`、`FileMetadata(String, long, String, String, Instant)`、`CoordFileService(ObjectStoreClient, CoordEndpoint, String, String, long, Clock)` 在 Task 2/3/4 中一致。
- Review Focus 五条都有对应测试。
- 没有 Task 依赖未定义的符号。
