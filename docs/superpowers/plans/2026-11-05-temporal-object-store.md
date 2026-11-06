# 契约地基与双时间轴对象存储实施计划（P0 + P1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在既有模块内落地 L1 数据契约的地基——规范名清理、契约错误码目录、UUIDv7、双时间轴对象模型与内存/PostgreSQL 两种存储，并用同一套契约测试证明两者语义一致。

**Architecture:** 纯 Java 语义放 `core`（`ObjectVersion`、`TemporalRules`、`Sealing`、`TemporalStore` SPI + 契约测试套件），存储实现放 `seedj-infra-local`（内存）与 `seedj-jdbc-postgresql`（DDL、迁移、JDBC）；两个实现跑同一抽象契约测试，PG 实现另加真实约束测试。语义基准是规范参考内核 `tools/lib/kernel.py` 的 `submit/apply_late_fact/as_of` 行为。

**Tech Stack:** Java 21、Maven 多模块、JUnit 5、AssertJ、Spring `JdbcTemplate`（仅 PG 模块）、Testcontainers（`postgres:16-alpine`）、PostgreSQL 16 + `btree_gist`。

**Spec:** `docs/superpowers/specs/2026-11-05-contract-conformance-design.md`（本计划只覆盖 P0 + P1；P2–P6 在阶段落地后另出计划）

## Global Constraints

- 产品代码（`src/main/java`、`src/main/resources`、所有 `pom.xml`）不得出现 `eis`（不区分大小写），**唯一例外**：错误码 `EIS-{3位}-{3位}`、Schema URI `eis://…`、DDL 域类型 `eis_id` / `eis_version` / `eis_timestamp`。
- 不新增 Maven module、不新增仓库根目录；新代码只进既有模块。
- `core` 零 Spring、零 seedj 模块依赖；`seedj-infra-local` 与 `seedj-jdbc-postgresql` 互不依赖。
- 所有时间字段为 UTC、微秒精度（nano 为 1000 的整数倍）；取时只经 `cn.byteforce.seedj.core.clock.Clock`。
- 标识为 UUIDv7；对象主键 `PK(id, recorded_at)`；半开区间 `[from, to)`。
- 每个新 public 类型带中文 javadoc，含 `@author ByteForce` 与 `@since 1.0.0`（沿用仓库风格）。
- 每个任务结束时该模块 `mvn -o -B -pl <module> -am test` 必须绿；提交信息用 `feat:` / `test:` / `refactor:` 前缀。
- 规范工具不在本计划的测试路径里；本计划只做 Java 侧可执行证明。

## Review Focus

- **同一微秒的两次追加**：同一 `id` 在同一微秒写入两条会被 `PK(id, recorded_at)` 拒绝；存储必须把新 `recorded_at` 提升到旧值 + 1µs，而不是抛错或覆盖。
- **半开区间边界**：`t == valid_to` 或 `t == superseded_at` 时必须命中后继版本，不得命中前驱。
- **迟到事实的未来时间**：`lateFact` 的 `valid_from >= now` 必须拒绝（`EIS-400-003`），不得写入。
- **`min_version` 与历史参数组合**：带 `as_of`/`recorded_as_of` 且返回版本低于 `min_version` 时同样返回 `EIS-409-003`；对象不存在时是 `EIS-404-001`，不是 409。
- **未知对象类型**：`objectType` 未注册时必须给出明确的 `IllegalArgumentException`（含类型名），不得让底层 SQL 异常穿透。

---

### Task 1: 规范名门禁 + 清理 4 处产品代码 javadoc

**Files:**
- Create: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/NamingGuardTest.java`
- Modify: `core/src/main/java/cn/byteforce/seedj/audit/AuditEntry.java:14`
- Modify: `core/src/main/java/cn/byteforce/seedj/audit/AuditAppendRequest.java:9`
- Modify: `core/src/main/java/cn/byteforce/seedj/core/utils/CanonicalJsonHelper.java:28`
- Modify: `core/src/main/java/cn/byteforce/seedj/cqrs/IdCodecs.java:10`

**Interfaces:**
- Consumes: 无
- Produces: 一个可重复运行的门禁测试 `NamingGuardTest`，后续任务每加一个文件都会自动受检。

- [ ] **Step 1: 写失败测试**

```java
package cn.byteforce.seedj.conformance;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class NamingGuardTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();
    private static final Pattern ALLOWED = Pattern.compile(
            "EIS-\\d{3}-\\d{3}|eis://[^\\s\"']+|eis_(id|version|timestamp)");
    private static final Pattern FORBIDDEN = Pattern.compile("(?i)eis");

    @Test
    void productArtifactsDoNotNameTheSpec() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(ROOT)) {
            paths.filter(Files::isRegularFile)
                    .filter(NamingGuardTest::isProductArtifact)
                    .forEach(path -> scan(path, hits));
        }
        assertThat(hits).isEmpty();
    }

    private static boolean isProductArtifact(Path path) {
        String p = path.toString();
        if (p.contains("/target/") || p.contains("/.git/") || p.contains("/docs/")) {
            return false;
        }
        return p.endsWith("/pom.xml") || p.contains("/src/main/java/") || p.contains("/src/main/resources/");
    }

    private static void scan(Path path, List<String> hits) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String stripped = ALLOWED.matcher(lines.get(i)).replaceAll("");
                if (FORBIDDEN.matcher(stripped).find()) {
                    hits.add(path + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取失败: " + path, e);
        }
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-it -am test -Dtest=NamingGuardTest`
Expected: FAIL，命中正好 4 条（`AuditEntry`、`AuditAppendRequest`、`CanonicalJsonHelper`、`IdCodecs` 的 javadoc）。

- [ ] **Step 3: 改写 4 处 javadoc，去掉规范名**

把 `EIS 契约使用 UUIDv7` 改为 `契约规定使用 UUIDv7`；`EIS 固定为 …` 改为 `契约固定为 …`；`EIS 域内数值` 改为 `契约域内数值`；`UUID（EIS 审计契约）` 改为 `UUID（审计契约）`。只改文字，不动代码。

- [ ] **Step 4: 重跑，确认通过**

Run: `mvn -o -B -pl seedj-it -am test -Dtest=NamingGuardTest`
Expected: PASS，输出 `Tests run: 1, Failures: 0`。

- [ ] **Step 5: 提交**

```bash
git add seedj-it/src/test/java/cn/byteforce/seedj/conformance/NamingGuardTest.java \
        core/src/main/java/cn/byteforce/seedj/audit/AuditEntry.java \
        core/src/main/java/cn/byteforce/seedj/audit/AuditAppendRequest.java \
        core/src/main/java/cn/byteforce/seedj/core/utils/CanonicalJsonHelper.java \
        core/src/main/java/cn/byteforce/seedj/cqrs/IdCodecs.java
git commit -m "test: guard product code against spec naming leaks"
```

---

### Task 2: 契约错误码目录、异常与错误信封

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/core/exception/ContractErrorCode.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/exception/ContractException.java`
- Create: `core/src/main/java/cn/byteforce/seedj/core/exception/ApiError.java`
- Create: `core/src/test/java/cn/byteforce/seedj/core/exception/ContractErrorCodeTest.java`

**Interfaces:**
- Consumes: `cn.byteforce.seedj.core.CodeEnum`（已有）
- Produces:
  - `ContractErrorCode implements CodeEnum<String>`，方法 `httpStatus(): int`、`retryable(): boolean`
  - `ContractException extends RuntimeException`，构造 `(ContractErrorCode, String reason)` 与 `(ContractErrorCode, String reason, Map<String,Object> details, String reasonCode)`；访问器 `code(): ContractErrorCode`、`reason()`、`details()`、`reasonCode()`
  - `ApiError record(String code, String message, Map<String,Object> details)` + `static ApiError of(ContractException)`

- [ ] **Step 1: 写失败测试**

```java
class ContractErrorCodeTest {

    @Test
    void everyCodeMatchesFrozenFormatAndStatusPrefix() {
        for (ContractErrorCode code : ContractErrorCode.values()) {
            assertThat(code.getCode()).matches("EIS-\\d{3}-\\d{3}");
            assertThat(code.getCode()).startsWith("EIS-" + code.httpStatus() + "-");
        }
    }

    @Test
    void codesAreUniqueAndComplete() {
        assertThat(Arrays.stream(ContractErrorCode.values()).map(ContractErrorCode::getCode))
                .doesNotHaveDuplicates()
                .hasSize(12)
                .contains("EIS-400-001", "EIS-400-003", "EIS-403-002",
                        "EIS-404-001", "EIS-409-003", "EIS-503-001");
    }

    @Test
    void exceptionCarriesReasonAndDetailsThroughEnvelope() {
        ContractException e = new ContractException(ContractErrorCode.READ_MODEL_NOT_READY,
                "version 5 not readable yet", Map.of("required", 5), null);
        ApiError error = ApiError.of(e);
        assertThat(error.code()).isEqualTo("EIS-409-003");
        assertThat(error.message()).isEqualTo("version 5 not readable yet");
        assertThat(error.details()).containsEntry("required", 5);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl core test -Dtest=ContractErrorCodeTest`
Expected: 编译失败（类型不存在）。

- [ ] **Step 3: 实现三个类型**

枚举 12 项，顺序即码表顺序：`INVALID_PAYLOAD(EIS-400-001,400,false)`、`COMMAND_REJECTED(EIS-400-002,400,false)`、`TEMPORAL_VIOLATION(EIS-400-003,400,false)`、`UNAUTHENTICATED(EIS-401-001,401,false)`、`FORBIDDEN(EIS-403-001,403,false)`、`CALLER_NOT_ALLOWED(EIS-403-002,403,false)`、`OBJECT_NOT_FOUND(EIS-404-001,404,false)`、`IDEMPOTENCY_CONFLICT(EIS-409-001,409,false)`、`VERSION_CONFLICT(EIS-409-002,409,true)`、`READ_MODEL_NOT_READY(EIS-409-003,409,true)`、`INTERNAL_ERROR(EIS-500-001,500,true)`、`UNAVAILABLE(EIS-503-001,503,true)`。

`getLabel()` 返回枚举名的小写短语（如 `"invalid payload"`）。`ContractException` 的构造器把 `reason` 作为 `RuntimeException` 的 message；`details` 缺省为 `Map.of()`；`reasonCode` 可空。`ApiError.of` 的 `message` 取 `reason`，`details` 原样传递。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl core test -Dtest=ContractErrorCodeTest`
Expected: PASS（3 个用例）。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/core/exception/ContractErrorCode.java \
        core/src/main/java/cn/byteforce/seedj/core/exception/ContractException.java \
        core/src/main/java/cn/byteforce/seedj/core/exception/ApiError.java \
        core/src/test/java/cn/byteforce/seedj/core/exception/ContractErrorCodeTest.java
git commit -m "feat: add contract error code catalogue and envelope"
```

---

### Task 3: UUIDv7 生成器

**Files:**
- Create: `seedj-infra-local/src/main/java/cn/byteforce/seedj/id/Uuid7IdGenerator.java`
- Create: `seedj-infra-local/src/test/java/cn/byteforce/seedj/id/Uuid7IdGeneratorTest.java`

**Interfaces:**
- Consumes: `cn.byteforce.seedj.core.clock.Clock`、`cn.byteforce.seedj.id.IdGenerator`
- Produces: `public final class Uuid7IdGenerator implements IdGenerator<UUID>`，构造器 `Uuid7IdGenerator(Clock clock)`，方法 `UUID id()`

- [ ] **Step 1: 写失败测试**

```java
class Uuid7IdGeneratorTest {

    private final Clock clock = Clocks.fixed(Instant.parse("2026-10-08T09:00:00Z"));
    private final Uuid7IdGenerator generator = new Uuid7IdGenerator(clock);

    @Test
    void producesRfc9562Version7Variant2() {
        UUID id = generator.id();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void isUnique() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(ids.add(generator.id())).isTrue();
        }
    }

    @Test
    void isMonotonicWithinSameMillisecond() {
        UUID first = generator.id();
        UUID second = generator.id();
        assertThat(second.toString()).isGreaterThan(first.toString());
    }

    @Test
    void encodesClockMilliseconds() {
        UUID id = generator.id();
        long ms = id.getMostSignificantBits() >>> 16;
        assertThat(ms).isEqualTo(clock.now().toEpochMilli());
    }
}
```

`TestClock` 留给 Task 6 的契约套件；本任务用已有的 `Clocks.fixed(...)` 即可。

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=Uuid7IdGeneratorTest`
Expected: 编译失败（`Uuid7IdGenerator` 不存在）。

- [ ] **Step 3: 实现生成器**

48 位毫秒时间戳 + `0x7` 版本 + 12 位毫秒内计数器（`rand_a`）+ `0b10` 变体 + 62 位 `ThreadLocalRandom` 随机数。用 `synchronized` 保护「时间戳 + 计数器」：同一毫秒内计数器自增；计数器溢出（4096）时把时间戳 +1 毫秒并清零计数器。时间戳不得为负（`Instant` 早于 epoch 时抛 `IllegalArgumentException`）。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=Uuid7IdGeneratorTest`
Expected: PASS（4 个用例）。

- [ ] **Step 5: 提交**

```bash
git add seedj-infra-local/src/main/java/cn/byteforce/seedj/id/Uuid7IdGenerator.java \
        seedj-infra-local/src/test/java/cn/byteforce/seedj/id/Uuid7IdGeneratorTest.java
git commit -m "feat: add uuidv7 id generator"
```

---

### Task 4: `ObjectVersion` 与 `TemporalQuery`

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/domain/ObjectVersion.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/TemporalQuery.java`
- Create: `core/src/test/java/cn/byteforce/seedj/domain/ObjectVersionTest.java`

**Interfaces:**
- Consumes: `ContractException` / `ContractErrorCode`（Task 2）
- Produces:
  - `record ObjectVersion(UUID id, long version, Instant validFrom, Instant validTo, Instant recordedAt, Instant supersededAt, UUID createdBy, Instant createdAt, UUID updatedBy, Instant updatedAt)`，方法 `isCurrent(): boolean`、`validAt(Instant): boolean`、`recordedAt(Instant): boolean`、`withSupersededAt(Instant): ObjectVersion`
  - `record TemporalQuery(Instant asOf, Instant recordedAsOf, Long minVersion)`，静态 `current()`

- [ ] **Step 1: 写失败测试**

```java
class ObjectVersionTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    @Test
    void validIntervalIsHalfOpen() {
        ObjectVersion v = new ObjectVersion(ID, 1, T0, T0.plusSeconds(60), T0, null,
                ACTOR, T0, null, null);
        assertThat(v.validAt(T0)).isTrue();
        assertThat(v.validAt(T0.plusSeconds(59))).isTrue();
        assertThat(v.validAt(T0.plusSeconds(60))).isFalse();
    }

    @Test
    void recordedIntervalIsHalfOpen() {
        ObjectVersion v = new ObjectVersion(ID, 1, T0, null, T0, T0.plusSeconds(60),
                ACTOR, T0, null, null);
        assertThat(v.recordedAt(T0.plusSeconds(60))).isFalse();
        assertThat(v.recordedAt(T0.plusSeconds(59))).isTrue();
    }

    @Test
    void currentRequiresBothAxesOpen() {
        assertThat(new ObjectVersion(ID, 1, T0, null, T0, null, ACTOR, T0, null, null).isCurrent()).isTrue();
        assertThat(new ObjectVersion(ID, 1, T0, T0.plusSeconds(1), T0, null, ACTOR, T0, null, null).isCurrent()).isFalse();
        assertThat(new ObjectVersion(ID, 1, T0, null, T0, T0.plusSeconds(1), ACTOR, T0, null, null).isCurrent()).isFalse();
    }

    @Test
    void truncatesToMicroseconds() {
        Instant nanos = Instant.parse("2026-10-08T09:00:00.123456789Z");
        ObjectVersion v = new ObjectVersion(ID, 1, nanos, null, nanos, null, ACTOR, nanos, null, null);
        assertThat(v.validFrom().getNano() % 1_000).isZero();
        assertThat(v.recordedAt().getNano() % 1_000).isZero();
    }

    @Test
    void rejectsInvertedOrEqualValidRange() {
        assertThatThrownBy(() -> new ObjectVersion(ID, 1, T0, T0, T0, null, ACTOR, T0, null, null))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.TEMPORAL_VIOLATION);
    }

    @Test
    void queryRejectsNonPositiveMinVersion() {
        assertThatThrownBy(() -> new TemporalQuery(null, null, 0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TemporalQuery.current().minVersion()).isNull();
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl core test -Dtest=ObjectVersionTest`
Expected: 编译失败（类型不存在）。

- [ ] **Step 3: 实现**

`ObjectVersion` 紧凑构造器：`id/validFrom/recordedAt/createdBy/createdAt` 非空，`version >= 1`；所有 `Instant` 截断到 `ChronoUnit.MICROS`；`validTo != null && validTo <= validFrom` → `TEMPORAL_VIOLATION`；`supersededAt != null && supersededAt <= recordedAt` → `TEMPORAL_VIOLATION`。`validAt`/`recordedAt` 按半开区间。`withSupersededAt` 返回副本。

`TemporalQuery` 紧凑构造器：`minVersion != null && minVersion < 1` → `IllegalArgumentException`；`asOf`/`recordedAsOf` 截断到微秒。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl core test -Dtest=ObjectVersionTest`
Expected: PASS（6 个用例）。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/domain/ObjectVersion.java \
        core/src/main/java/cn/byteforce/seedj/domain/TemporalQuery.java \
        core/src/test/java/cn/byteforce/seedj/domain/ObjectVersionTest.java
git commit -m "feat: add bitemporal object version and query model"
```

---

### Task 5: 封口/追加语义（`Sealing`、`TemporalRules`、`TemporalRow`、`AppendRequest`）

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/domain/TemporalRow.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/AppendKind.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/AppendRequest.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/AppendResult.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/TemporalRules.java`
- Create: `core/src/main/java/cn/byteforce/seedj/domain/Sealing.java`
- Create: `core/src/test/java/cn/byteforce/seedj/domain/SealingTest.java`

**Interfaces:**
- Consumes: Task 4 的类型、Task 2 的错误码
- Produces:
  - `enum AppendKind { CORRECTION, LATE_FACT }`
  - `record TemporalRow(String objectType, ObjectVersion version, Map<String,Object> fields)`
  - `record AppendRequest(String objectType, UUID id, Instant validFrom, Instant validTo, UUID actor, Map<String,Object> fields, AppendKind kind)`，静态工厂 `create(...)` / `correction(...)` / `lateFact(...)`
  - `record AppendResult(TemporalRow appended, Optional<TemporalRow> superseded)`
  - `final class TemporalRules`：`static void validate(ObjectVersion)`、`static void validateAppend(AppendRequest, TemporalRow current, Instant now)`
  - `final class Sealing`：`static AppendResult next(AppendRequest, TemporalRow current, Instant now)`

- [ ] **Step 1: 写失败测试**

```java
class SealingTest {

    private static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
    private static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");
    private static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    @Test
    void createProducesVersionOne() {
        AppendResult r = Sealing.next(AppendRequest.create("probe", ID, T0, null, ACTOR, Map.of("name", "v1")), null, T0);
        assertThat(r.appended().version().version()).isEqualTo(1);
        assertThat(r.appended().version().createdBy()).isEqualTo(ACTOR);
        assertThat(r.appended().version().updatedBy()).isNull();
        assertThat(r.superseded()).isEmpty();
    }

    @Test
    void correctionSealsPreviousAtNewRecordedAt() {
        TemporalRow first = Sealing.next(AppendRequest.create("probe", ID, T0, null, ACTOR, Map.of("name", "v1")), null, T0).appended();
        AppendResult second = Sealing.next(AppendRequest.correction("probe", ID, T0, null, ACTOR, Map.of("name", "v2")), first, T0.plusSeconds(60));
        assertThat(second.appended().version().version()).isEqualTo(2);
        assertThat(second.superseded().orElseThrow().version().supersededAt())
                .isEqualTo(second.appended().version().recordedAt());
        assertThat(second.appended().version().createdAt()).isEqualTo(first.version().createdAt());
    }

    @Test
    void sameMicrosecondStillAdvancesRecordedAt() {
        TemporalRow first = Sealing.next(AppendRequest.create("probe", ID, T0, null, ACTOR, Map.of()), null, T0).appended();
        TemporalRow second = Sealing.next(AppendRequest.correction("probe", ID, T0, null, ACTOR, Map.of()), first, T0).appended();
        assertThat(second.version().recordedAt()).isEqualTo(T0.plus(1, ChronoUnit.MICROS));
    }

    @Test
    void lateFactAllowsHistoricalValidRangeButRejectsFuture() {
        TemporalRow first = Sealing.next(AppendRequest.create("probe", ID, T0, null, ACTOR, Map.of()), null, T0).appended();
        TemporalRow late = Sealing.next(AppendRequest.lateFact("probe", ID, T0.minusSeconds(3600), null, ACTOR, Map.of()), first, T0.plusSeconds(60)).appended();
        assertThat(late.version().validFrom()).isEqualTo(T0.minusSeconds(3600));
        assertThatThrownBy(() -> Sealing.next(AppendRequest.lateFact("probe", ID, T0.plusSeconds(1), null, ACTOR, Map.of()), first, T0.plusSeconds(60)))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.TEMPORAL_VIOLATION);
    }

    @Test
    void correctionOnMissingObjectIsNotFound() {
        assertThatThrownBy(() -> Sealing.next(AppendRequest.correction("probe", ID, T0, null, ACTOR, Map.of()), null, T0))
                .isInstanceOf(ContractException.class)
                .extracting(e -> ((ContractException) e).code())
                .isEqualTo(ContractErrorCode.OBJECT_NOT_FOUND);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl core test -Dtest=SealingTest`
Expected: 编译失败（类型不存在）。

- [ ] **Step 3: 实现**

`TemporalRow`：`objectType` 非空、`fields` 做不可变有序拷贝（`Collections.unmodifiableMap(new LinkedHashMap<>(fields))`），`version` 非空。

`Sealing.next` 的算法：

1. `TemporalRules.validateAppend(request, current, now)`；
2. `recordedAt = current == null ? now : max(now, current.recordedAt + 1µs)`；
3. `version = current == null ? 1 : current.version + 1`；
4. `createdBy/createdAt`：有 current 时沿用，否则 `actor/now`；`updatedBy/updatedAt`：有 current 时 `actor/now`，否则 `null`；
5. 新行 `ObjectVersion(id, version, validFrom, validTo, recordedAt, null, createdBy, createdAt, updatedBy, updatedAt)`；
6. `superseded = current == null ? Optional.empty() : Optional.of(current.version().withSupersededAt(recordedAt))`；
7. 返回 `new AppendResult(new TemporalRow(objectType, newVersion, fields), superseded)`。

`TemporalRules.validateAppend`：`objectType` 与 `fields` 非空；`validTo != null && validTo <= validFrom` → `TEMPORAL_VIOLATION`；`kind == LATE_FACT` 且 `current == null` → `OBJECT_NOT_FOUND`；`kind == LATE_FACT` 且 `validFrom >= now` → `TEMPORAL_VIOLATION`。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl core test -Dtest=SealingTest`
Expected: PASS（5 个用例）。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/domain/TemporalRow.java \
        core/src/main/java/cn/byteforce/seedj/domain/AppendKind.java \
        core/src/main/java/cn/byteforce/seedj/domain/AppendRequest.java \
        core/src/main/java/cn/byteforce/seedj/domain/AppendResult.java \
        core/src/main/java/cn/byteforce/seedj/domain/TemporalRules.java \
        core/src/main/java/cn/byteforce/seedj/domain/Sealing.java \
        core/src/test/java/cn/byteforce/seedj/domain/SealingTest.java
git commit -m "feat: add bitemporal sealing semantics"
```

---

### Task 6: `TemporalStore` SPI 与契约测试套件

**Files:**
- Create: `core/src/main/java/cn/byteforce/seedj/domain/TemporalStore.java`
- Create: `core/src/test/java/cn/byteforce/seedj/core/clock/TestClock.java`
- Create: `core/src/test/java/cn/byteforce/seedj/domain/TemporalStoreContractTest.java`
- Modify: `seedj-infra-local/src/test/java/cn/byteforce/seedj/id/Uuid7IdGeneratorTest.java`（改用共享 `TestClock`）
- Delete: Task 3 在 `seedj-infra-local` 测试里建的临时 `TestClock`

**Interfaces:**
- Consumes: Task 5 的类型
- Produces:
  - `interface TemporalStore`：
    - `Optional<TemporalRow> find(String objectType, UUID id, TemporalQuery query)`
    - `Optional<TemporalRow> current(String objectType, UUID id)`（default，等价 `find(..., TemporalQuery.current())`）
    - `AppendResult append(AppendRequest request)`
    - `List<TemporalRow> history(String objectType, UUID id)`
  - `abstract class TemporalStoreContractTest`（core test-jar）：抽象方法 `store()`、`clock()`、`reset()`

- [ ] **Step 1: 写失败测试（契约套件）**

```java
public abstract class TemporalStoreContractTest {

    protected static final String TYPE = "probe";
    protected static final UUID ID = UUID.fromString("018f2e3a-1234-7000-8000-000000000001");
    protected static final UUID ACTOR = UUID.fromString("018f2e3a-0000-7000-8000-000000000001");
    protected static final Instant T0 = Instant.parse("2026-10-08T09:00:00Z");

    protected abstract TemporalStore store();
    protected abstract TestClock clock();
    protected abstract void reset();

    @BeforeEach
    void cleanState() {
        reset();
        clock().set(T0);
    }

    private TemporalRow seed(String name, Instant validFrom) {
        return store().append(AppendRequest.create(TYPE, ID, validFrom, null, ACTOR, Map.of("name", name))).appended();
    }

    @Test
    void createIsVersionOneAndCurrent() { /* seed(); assert version 1, isCurrent(), createdBy == ACTOR */ }

    @Test
    void appendSealsPreviousAndIncrementsVersion() { /* advance 60s; correction; assert superseded recordedAt == new recordedAt, version 2 */ }

    // 同微秒、半开边界、recorded_as_of、min_version、迟到事实 6 个用例的完整断言
    // 见本步骤末尾的代码块；实现时把它们并入本类，不得用注释代替断言。

    @Test
    void historyIsOrderedByRecordedAt() { /* two corrections; history versions == [1,2,3] */ }

    @Test
    void correctionOnMissingObjectIsNotFound() { /* find on empty store -> empty; append correction -> EIS-404-001 */ }
}
```

这 6 个 Review Focus 用例的断言必须逐条写全（并入上面的 `TemporalStoreContractTest`）：

```java
@Test
void sameMicrosecondAppendDoesNotCollide() {
    seed("v1", T0);                       // 不推进时钟
    TemporalRow second = store().append(
            AppendRequest.correction(TYPE, ID, T0, null, ACTOR, Map.of("name", "v2"))).appended();
    assertThat(second.version().recordedAt()).isEqualTo(T0.plus(1, ChronoUnit.MICROS));
}

@Test
void asOfUsesHalfOpenBounds() {
    // v1: [T0, T0+60)；v2 从 T0+60 开始
    store().append(AppendRequest.create(TYPE, ID, T0, T0.plusSeconds(60), ACTOR, Map.of("name", "v1")));
    clock().advance(Duration.ofSeconds(60));
    store().append(AppendRequest.correction(TYPE, ID, T0.plusSeconds(60), null, ACTOR, Map.of("name", "v2")));
    assertThat(store().find(TYPE, ID, new TemporalQuery(T0.plusSeconds(59), null, null))
            .orElseThrow().fields()).containsEntry("name", "v1");
    assertThat(store().find(TYPE, ID, new TemporalQuery(T0.plusSeconds(60), null, null))
            .orElseThrow().fields()).containsEntry("name", "v2");
}

@Test
void recordedAsOfReturnsBeliefAtSystemTime() {
    seed("v1", T0);
    clock().advance(Duration.ofSeconds(60));
    store().append(AppendRequest.correction(TYPE, ID, T0, null, ACTOR, Map.of("name", "v2")));
    assertThat(store().find(TYPE, ID, new TemporalQuery(null, T0.plusSeconds(30), null))
            .orElseThrow().fields()).containsEntry("name", "v1");
    assertThat(store().find(TYPE, ID, new TemporalQuery(null, T0.plusSeconds(90), null))
            .orElseThrow().fields()).containsEntry("name", "v2");
}

@Test
void minVersionAboveReadableIsRejected() {
    seed("v1", T0);
    assertThatThrownBy(() -> store().find(TYPE, ID, new TemporalQuery(null, null, 2L)))
            .isInstanceOf(ContractException.class)
            .extracting(e -> ((ContractException) e).code())
            .isEqualTo(ContractErrorCode.READ_MODEL_NOT_READY);
}

@Test
void minVersionWithHistoricalQueryAlsoComparesReturnedVersion() {
    seed("v1", T0);
    clock().advance(Duration.ofSeconds(60));
    store().append(AppendRequest.correction(TYPE, ID, T0, null, ACTOR, Map.of("name", "v2")));
    // recorded_as_of=T0+30 只看到 v1，低于 min_version=2
    assertThatThrownBy(() -> store().find(TYPE, ID, new TemporalQuery(null, T0.plusSeconds(30), 2L)))
            .isInstanceOf(ContractException.class)
            .extracting(e -> ((ContractException) e).code())
            .isEqualTo(ContractErrorCode.READ_MODEL_NOT_READY);
}

@Test
void lateFactKeepsHistoryAndAllowsOverlap() {
    seed("v1", T0);
    clock().advance(Duration.ofSeconds(60));
    store().append(AppendRequest.lateFact(TYPE, ID, T0.minusSeconds(3600), null, ACTOR, Map.of("name", "late")));
    assertThat(store().history(TYPE, ID)).hasSize(2);
    assertThat(store().find(TYPE, ID, new TemporalQuery(T0.minusSeconds(1800), null, null))
            .orElseThrow().fields()).containsEntry("name", "late");
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl core test -Dtest=TemporalStoreContractTest`
Expected: 编译失败（`TemporalStore` 不存在）。

- [ ] **Step 3: 实现 SPI 与 `TestClock`**

`TemporalStore` 接口 + javadoc（写清：`append` 必须在调用方事务内执行；`find` 的 `minVersion` 语义）。`TestClock`：`implements Clock`，`set(Instant)`、`advance(Duration)`、`now()` 截断到微秒。

- [ ] **Step 4: 编译契约套件，确认通过**

Run: `mvn -o -B -pl core -am test-compile`
Expected: BUILD SUCCESS（抽象类无可执行用例）。再跑 `mvn -o -B -pl seedj-infra-local -am test -Dtest=Uuid7IdGeneratorTest` 确认改用共享 `TestClock` 前的 UUIDv7 测试仍绿。

- [ ] **Step 5: 提交**

```bash
git add core/src/main/java/cn/byteforce/seedj/domain/TemporalStore.java \
        core/src/test/java/cn/byteforce/seedj/core/clock/TestClock.java \
        core/src/test/java/cn/byteforce/seedj/domain/TemporalStoreContractTest.java \
        seedj-infra-local/src/test/java/cn/byteforce/seedj/id/Uuid7IdGeneratorTest.java
git commit -m "feat: add temporal store spi and contract test suite"
```

---

### Task 7: 内存实现 `InMemoryTemporalStore`

**Files:**
- Create: `seedj-infra-local/src/main/java/cn/byteforce/seedj/domain/memory/InMemoryTemporalStore.java`
- Create: `seedj-infra-local/src/test/java/cn/byteforce/seedj/domain/memory/InMemoryTemporalStoreContractTest.java`

**Interfaces:**
- Consumes: `TemporalStore`、`Sealing`、`TestClock`
- Produces: `public final class InMemoryTemporalStore implements TemporalStore`，构造器 `InMemoryTemporalStore(Clock clock)`

- [ ] **Step 1: 写失败测试**

```java
class InMemoryTemporalStoreContractTest extends TemporalStoreContractTest {

    private final TestClock clock = new TestClock(T0);
    private final TemporalStore store = new InMemoryTemporalStore(clock);

    @Override protected TemporalStore store() { return store; }
    @Override protected TestClock clock() { return clock; }
    @Override protected void reset() { ((InMemoryTemporalStore) store).clear(); }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=InMemoryTemporalStoreContractTest`
Expected: 编译失败（`InMemoryTemporalStore` 不存在）。

- [ ] **Step 3: 实现**

`ConcurrentHashMap<Key, List<TemporalRow>>`，`Key = record(String objectType, UUID id)`。每次操作对 `List` 加锁（`synchronized (rows)`），保证封口与追加原子。`find` 按 `TemporalQuery` 过滤（半开区间、`minVersion`），命中多条取 `version` 最大者；无命中返回 `Optional.empty()`。`append` 调 `Sealing.next`，把旧行替换为 `superseded`、追加新行。`history` 按 `recordedAt` 升序。`clear()` 清空（测试用）。未注册类型直接允许写入（内存实现不依赖表注册），但 `objectType` 为空由 `Sealing` 拒绝。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl seedj-infra-local -am test -Dtest=InMemoryTemporalStoreContractTest`
Expected: PASS（契约套件 10 个用例）。

- [ ] **Step 5: 提交**

```bash
git add seedj-infra-local/src/main/java/cn/byteforce/seedj/domain/memory/InMemoryTemporalStore.java \
        seedj-infra-local/src/test/java/cn/byteforce/seedj/domain/memory/InMemoryTemporalStoreContractTest.java
git commit -m "feat: add in-memory temporal store"
```

---

### Task 8: PostgreSQL 对象表 DDL、迁移与 schema capability

**Files:**
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalTable.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalTables.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalDdl.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalMigrations.java`
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/TemporalSchemaCapability.java`
- Create: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/domain/jdbc/TemporalDdlTest.java`

**Interfaces:**
- Consumes: `Migration`、`JdbcMigrationRunner`、`InfrastructureCapability`
- Produces:
  - `record TemporalTable(String objectType, String tableName, Map<String,String> columns)`（`columns` 为 领域列名 → PG 类型，保持插入顺序；名字必须匹配 `^[a-z][a-z0-9_]*$`）
  - `final class TemporalTables`：构造 `TemporalTables(List<TemporalTable>)`，`TemporalTable require(String objectType)`（未知类型抛 `IllegalArgumentException`，消息含类型名）
  - `final class TemporalDdl`：`static String createTable(TemporalTable table)`
  - `final class TemporalMigrations`：`static List<Migration> all()`
  - `public class TemporalSchemaCapability implements InfrastructureCapability`：`NAME = "postgres-temporal"`，`MIGRATION_SCOPE = "temporal"`，构造 `TemporalSchemaCapability(JdbcMigrationRunner)`，`getOrder()` 返回 120

- [ ] **Step 1: 写失败测试**

```java
@Testcontainers
class TemporalDdlTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seedj").withUsername("seedj").withPassword("seedj");

    private static JdbcTemplate jdbc;
    private static TemporalTable table;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "seedj", "seedj");
        jdbc = new JdbcTemplate(ds);
        new TemporalSchemaCapability(new JdbcMigrationRunner(ds)).initialize();
        table = new TemporalTable("probe", "probe_object", new LinkedHashMap<>(Map.of("name", "TEXT")));
        jdbc.execute(TemporalDdl.createTable(table));
    }

    @Test
    void createsBothTemporalIndexesAndCurrentUniqueIndex() {
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'probe_object'", String.class);
        assertThat(indexes).contains("uq_probe_object_current", "idx_probe_object_valid_time",
                "idx_probe_object_recorded_time", "idx_probe_object_bitemporal");
    }

    @Test
    void rejectsSecondCurrentRow() {
        insert("018f2e3a-1234-7000-8000-000000000001", 1, "2026-10-08T09:00:00Z", null, "2026-10-08T09:00:00Z", null);
        assertThatThrownBy(() -> insert("018f2e3a-1234-7000-8000-000000000001", 2, "2026-10-08T09:00:00Z", null, "2026-10-08T09:00:01Z", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsOverlappingValidRanges() {
        insert("018f2e3a-1234-7000-8000-000000000002", 1, "2026-10-08T09:00:00Z", null, "2026-10-08T09:00:00Z", "2026-10-08T09:00:02Z");
        assertThatThrownBy(() -> insert("018f2e3a-1234-7000-8000-000000000002", 2, "2026-10-08T09:00:01Z", null, "2026-10-08T09:00:01Z", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void createsAsOfQueryFunctions() {
        List<String> functions = jdbc.queryForList(
                "SELECT proname FROM pg_proc WHERE proname IN ('as_of_valid','as_of_recorded','as_of_bitemporal')",
                String.class);
        assertThat(functions).containsExactlyInAnyOrder(
                "as_of_valid", "as_of_recorded", "as_of_bitemporal");
    }

    private static void insert(String id, int version, String validFrom, String validTo, String recordedAt, String supersededAt) {
        jdbc.update("INSERT INTO probe_object (id, version, valid_from, valid_to, recorded_at, superseded_at, created_by, created_at, name) VALUES (?::uuid, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::uuid, ?::timestamptz, 'x')",
                id, version, validFrom, validTo, recordedAt, supersededAt, id, recordedAt);
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest=TemporalDdlTest`
Expected: 编译失败（类型不存在）。

- [ ] **Step 3: 实现**

`TemporalMigrations.all()` 返回单条 `Migration(1, "temporal core objects", ...)`：

```sql
CREATE EXTENSION IF NOT EXISTS btree_gist;
DO $$ BEGIN
  CREATE DOMAIN eis_id AS UUID;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  CREATE DOMAIN eis_version AS BIGINT CHECK (VALUE >= 1);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
  CREATE DOMAIN eis_timestamp AS TIMESTAMPTZ(6);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
CREATE OR REPLACE FUNCTION as_of_valid(p_table TEXT, p_time TIMESTAMPTZ) RETURNS SETOF RECORD AS $$ ... $$;
CREATE OR REPLACE FUNCTION as_of_recorded(p_table TEXT, p_time TIMESTAMPTZ) RETURNS SETOF RECORD AS $$ ... $$;
CREATE OR REPLACE FUNCTION as_of_bitemporal(p_table TEXT, p_time TIMESTAMPTZ, p_recorded_time TIMESTAMPTZ) RETURNS SETOF RECORD AS $$ ... $$;
```

函数体照抄规范 `03-time-contract/query-templates.sql` 的前三个函数（半开区间谓词）。

`TemporalDdl.createTable` 按 §7.1 生成：10 个元字段（`eis_id` / `eis_version` / `eis_timestamp`）、`PRIMARY KEY (id, recorded_at)`、`valid_range` / `recorded_range` 两个 `CHECK`、`uq_<table>_current`、`ex_<table>_valid_overlap`（`tstzrange` 四维 `EXCLUDE`）、三个时点索引。表名与列名先做 `^[a-z][a-z0-9_]*$` 校验再拼接。

`TemporalSchemaCapability` 与 `AuditSchemaCapability` 同构（`isReady`/`initialize`/`validate` 委托 runner，`getOrder()` 120）。`TemporalTables.require` 在构造期做表名/列名校验并拒绝重复 `objectType`。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest=TemporalDdlTest`
Expected: PASS（4 个用例）。

- [ ] **Step 5: 提交**

```bash
git add seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc \
        seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/domain/jdbc/TemporalDdlTest.java
git commit -m "feat: add postgres temporal ddl and schema capability"
```

---

### Task 9: `PostgresTemporalStore` 通过同一契约套件

**Files:**
- Create: `seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/PostgresTemporalStore.java`
- Create: `seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/domain/jdbc/PostgresTemporalStoreContractTest.java`

**Interfaces:**
- Consumes: `TemporalStore`、`Sealing`、`TemporalTables`、`TestClock`
- Produces: `public final class PostgresTemporalStore implements TemporalStore`，构造器 `PostgresTemporalStore(JdbcTemplate jdbc, Clock clock, TemporalTables tables)`

- [ ] **Step 1: 写失败测试**

```java
@Testcontainers
class PostgresTemporalStoreContractTest extends TemporalStoreContractTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("seedj").withUsername("seedj").withPassword("seedj");

    private static JdbcTemplate jdbc;
    private static TestClock clock;
    private static TemporalStore store;

    @BeforeAll
    static void initialize() {
        DriverManagerDataSource ds = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "seedj", "seedj");
        jdbc = new JdbcTemplate(ds);
        new TemporalSchemaCapability(new JdbcMigrationRunner(ds)).initialize();
        TemporalTable table = new TemporalTable("probe", "probe_object",
                new LinkedHashMap<>(Map.of("name", "TEXT")));
        jdbc.execute(TemporalDdl.createTable(table));
        clock = new TestClock(T0);
        store = new PostgresTemporalStore(jdbc, clock, new TemporalTables(List.of(table)));
    }

    @Override protected TemporalStore store() { return store; }
    @Override protected TestClock clock() { return clock; }
    @Override protected void reset() { jdbc.execute("TRUNCATE probe_object"); }

    @Test
    void unknownObjectTypeIsRejectedClearly() {
        assertThatThrownBy(() -> store.find("missing", ID, TemporalQuery.current()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
    }
}
```

- [ ] **Step 2: 运行，确认失败**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest=PostgresTemporalStoreContractTest`
Expected: 编译失败（`PostgresTemporalStore` 不存在）。

- [ ] **Step 3: 实现**

- `find`：`tables.require(objectType)`；按 `TemporalQuery` 拼 `WHERE`（`asOf` → `valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)`；`recordedAsOf` → `recorded_at <= ? AND (superseded_at IS NULL OR superseded_at > ?)`；都为空 → `superseded_at IS NULL AND valid_to IS NULL`）；`ORDER BY version DESC LIMIT 1`；无行 → `Optional.empty()`；有行且 `minVersion != null && version < minVersion` → `ContractException(READ_MODEL_NOT_READY, …, Map.of("minVersion", n, "readableVersion", v))`。
- `append`：断言 `TransactionSynchronizationManager.isActualTransactionActive()`（否则 `IllegalStateException`）；`SELECT * FROM <table> WHERE id = ? AND superseded_at IS NULL FOR UPDATE`；`Sealing.next(...)`；若有旧行 `UPDATE ... SET superseded_at = ? WHERE id = ? AND recorded_at = ? AND superseded_at IS NULL`；`INSERT` 元字段 + 领域列（列名来自 `TemporalTable.columns()`，值来自 `fields`，缺失列写 `NULL`，多余键忽略并在 javadoc 注明）；返回 `AppendResult`。
- `history`：`ORDER BY recorded_at ASC`。
- 行映射：`ObjectVersion` 从固定列名读取，`fields` 从剩余领域列读取。

- [ ] **Step 4: 运行，确认通过**

Run: `mvn -o -B -pl seedj-jdbc-postgresql -am test -Dtest=PostgresTemporalStoreContractTest`
Expected: PASS（契约套件 10 个用例 + 未知类型 1 个用例）。

- [ ] **Step 5: 提交**

```bash
git add seedj-jdbc-postgresql/src/main/java/cn/byteforce/seedj/domain/jdbc/PostgresTemporalStore.java \
        seedj-jdbc-postgresql/src/test/java/cn/byteforce/seedj/domain/jdbc/PostgresTemporalStoreContractTest.java
git commit -m "feat: add postgres temporal store"
```

---

### Task 10: 全量回归与阶段验收

**Files:**
- Modify: `docs/superpowers/specs/2026-11-05-contract-conformance-design.md`（仅在发现偏差时补充“实现备注”）
- 无新代码

**Interfaces:**
- Consumes: Task 1–9 的全部交付物
- Produces: 可复现的阶段验收记录

- [ ] **Step 1: 跑全量构建与测试**

Run: `mvn -o -B verify`
Expected: BUILD SUCCESS；`core`、`seedj-infra-local`、`seedj-jdbc-postgresql` 全部测试通过，JaCoCo 门禁不报“无测试产物”。

- [ ] **Step 2: 检查命名门禁覆盖新代码**

Run: `mvn -o -B -pl seedj-it -am test -Dtest=NamingGuardTest`
Expected: PASS（新增的 `domain` / `domain.jdbc` / `domain.memory` 包与 DDL 除三条例外不含 `eis`）。

- [ ] **Step 3: 记录阶段验收**

在 `docs/conformance/runs/2026-11-05-p0-p1.md` 写：命令、模块、用例数、PG 镜像版本、与规范的对应关系（T-R001..T-R011 逐条 → 测试名）。若目录不存在则创建；这是文档目录，不是模块。

- [ ] **Step 4: 提交**

```bash
git add docs/conformance/runs/2026-11-05-p0-p1.md
git commit -m "docs: record p0-p1 conformance evidence"
```

---

## 自查记录（写完计划后执行）

- **Spec 覆盖**：P0（命名清理、错误码、UUIDv7）→ Task 1/2/3；P1（对象模型、封口追加、时点查询、PG 约束、DDL 冒烟）→ Task 4–9；验收 → Task 10。P2–P6 不在本计划，已在 Spec §9 分期。
- **类型一致性**：`TemporalRow.version()` 返回 `ObjectVersion`（Task 5），Task 7/9 均按此调用；`TemporalStore.find` 返回 `Optional<TemporalRow>`（Task 6），Task 9 实现一致；`TemporalTable.columns` 为 `Map<String,String>`（Task 8），Task 9 读取一致。
- **Review Focus 的测试归属**：同微秒 → Task 5 `sameMicrosecondStillAdvancesRecordedAt` + Task 6 契约用例；半开边界 → Task 4 + Task 6 契约用例；迟到事实未来时间 → Task 5；`min_version` 组合 → Task 6 契约用例；未知类型 → Task 9 的 `unknownObjectTypeIsRejectedClearly`（内存实现无表注册，不做此约束）。
- **比例**：计划含 10 个任务、约 40 个步骤；生产代码以签名与算法要点给出，测试以名称与关键断言给出，未逐行转写实现。
