# coord 基座（P0）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 coord SDK 在本地与 CI 可解析，建立 coord v2 真集群夹具，并把已写的 ID/编号/全局序列/EventBus 发布适配器补成可验证的 P0 能力（TCK + 装配 + 真集群）。

**Architecture:** 在 `seedj-infra-coord` 内按能力拆 provider：生成器只依赖 SDK 子接口，自动配置负责建 `CoordClient`、拼 namespace、装配 provider；测试分三层——单测（fake SDK 接口）、装配测试（`ApplicationContextRunner`）、真集群测试（Testcontainers 起 coord server + agent，data_plane=v2）。

**Tech Stack:** Java 21、Spring Boot 4.1.1、coord-java-sdk 0.4.3、Maven wrapper、JUnit 5、AssertJ、Mockito、Testcontainers、PostgreSQL（本计划仅 coord 容器）。

**Spec:** `docs/superpowers/specs/2026-11-15-seedj-infra-coord-design.md`

**Plan sequence:** 本计划只覆盖 P0（SDK/夹具/客户端/守卫）与阶段 0 已有的 ID/编号/序列/发布。后续独立计划：

1. `2026-11-15-coord-consumer-lock.md`：EventBus 消费契约收窄 + 消费适配器 + Lock（Spec §5.3/§5.4）。
2. `2026-11-15-coord-pki-transit.md`：PKI/Transit 契约收窄 + 适配器 + IAM 接入（§5.5/§5.6）。
3. `2026-11-15-coord-workflow.md`：`seedj-workflow-coord`（§5.7）。
4. `2026-11-15-coord-cutover.md`：删两个 local、切 server/starter、删 PG 重复实现、`seedj-it` 全量切换（§5.9/§8.4）。
5. `2026-11-15-coord-policy.md`：Rego 判定接入（§5.8）。

## Global Constraints

- 不改 coord 仓库代码；SDK 固定 `cn.byteforce:coord-java-sdk:0.4.3`（BOM 已声明）。
- 不新增内存版基础设施实现；测试里的 fake 只实现 SDK 接口，不实现 seedj 持久化 SPI。
- coord 镜像 `ghcr.io/byteforce-cn/coord:v0.4.3`；无 Docker 时测试跳过（`disabledWithoutDocker=true`），CI 有 Docker 必须真跑。
- `main` 受保护：分支 → PR → `gh pr checks --watch` 全绿 → merge；coord/seedj 的 `gh` 凭据只来自 `GH_TOKEN`。
- 格式：Spotless google-java-format；静态检查 SpotBugs 阻断。
- 新 public 顶层类型必须登记 `config/public-api.txt`，并同步 `config/cognitive-load-baseline.properties` 的 `api.publicTypes`（当前 610）。
- 新模块必须进 `seedj-it/.../ModuleClassPath.MODULES`、`config/coverage-baseline.json`。
- 提交信息：`feat(coord): ...` / `test(coord): ...` / `docs(coord): ...`。

## Review Focus

- **agent 不可达**：`seedj.coord.enabled=true` 且 agent 端口无人监听时，启动/首次调用必须快速失败并点名地址，不能挂起或静默降级。
- **并发发号**：同一生成器 32 线程并发取号不得重复；跨批次的顺序只保证单实例内递增。
- **topic epoch 过期**：`TOPIC_EPOCH_STALE` 只刷新一次并重试一次；第二次失败必须抛出，不能循环。
- **同一 eventId 重试**：`partitionKey` 为空时按 `eventId` 定分区，重试必须落同一分区（幂等键才生效）。
- **provider 选择与总开关不一致**：`seedj.id.provider=coord` 但 `seedj.coord.enabled` 缺失时启动失败，错误消息同时点名两个键。

---

### Task 1: coord SDK 在本地与 CI 可解析

**Files:**
- Create: `config/conformance/coord-ref.txt`
- Create: `config/ci/install-coord-sdk.sh`
- Modify: `.github/workflows/ci.yml`（verify / conformance / release-gates 三个 job，在 setup-java 后加 checkout coord + install 步骤）
- Modify: `config/ci/verify.sh:1-25`、`config/ci/conformance.sh`（调用安装脚本）

**Interfaces:**
- Consumes: coord 仓库 `coord-java-sdk/pom.xml`（standalone，可直接 `mvn -f ... install`）。
- Produces: 本地仓库中的 `cn.byteforce:coord-java-sdk:0.4.3`；后续任务直接依赖它。

- [ ] **Step 1: 写失败检查**

`config/ci/install-coord-sdk.sh`：

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
VERSION="$(sed -n 's:.*<coord-java-sdk.version>\(.*\)</coord-java-sdk.version>.*:\1:p' "$ROOT/bom/pom.xml" | head -n1)"
ART="$HOME/.m2/repository/cn/byteforce/coord-java-sdk/$VERSION/coord-java-sdk-$VERSION.jar"
if [[ -f "$ART" ]]; then
  echo "coord-java-sdk $VERSION: already installed"
  exit 0
fi
if [[ -z "${COORD_SOURCE:-}" || ! -f "$COORD_SOURCE/coord-java-sdk/pom.xml" ]]; then
  echo "coord-java-sdk $VERSION 不在本地仓库，且未设置 COORD_SOURCE（指向 coord 仓库根）" >&2
  exit 1
fi
mvn -B -q -f "$COORD_SOURCE/coord-java-sdk/pom.xml" -DskipTests install
echo "coord-java-sdk $VERSION: installed from $COORD_SOURCE"
```

- [ ] **Step 2: 验证脚本先红**

Run: `bash config/ci/install-coord-sdk.sh`
Expected（本机 m2 已装 0.4.3）：输出 `already installed`，退出 0。
在干净容器里（或无 m2）此步会以「未设置 COORD_SOURCE」失败——这就是要修的状态。

- [ ] **Step 3: 加 pin 与 CI 步骤**

`config/conformance/coord-ref.txt`：

```text
tag=v0.4.3
```

`.github/workflows/ci.yml` 每个构建 job 在 `setup-java` 之后加：

```yaml
      - name: resolve coord ref
        id: coord
        run: echo "ref=$(sed -n 's/^tag=//p' config/conformance/coord-ref.txt | head -n1)" >> "$GITHUB_OUTPUT"
      - uses: actions/checkout@v7
        with:
          repository: ${{ github.repository_owner }}/coord
          ref: ${{ steps.coord.outputs.ref }}
          path: .coord
          token: ${{ secrets.COORD_READ_TOKEN || github.token }}
      - name: install coord sdk
        env:
          COORD_SOURCE: ${{ github.workspace }}/.coord
        run: bash config/ci/install-coord-sdk.sh
```

`verify.sh` 与 `conformance.sh` 的 `cd` 之后各加一行：`bash config/ci/install-coord-sdk.sh`。

- [ ] **Step 4: 验证构建**

Run: `COORD_SOURCE=/root/opensource/coord bash config/ci/install-coord-sdk.sh && ./mvnw -o -B -pl seedj-infra-coord -am -DskipTests install`
Expected: `BUILD SUCCESS`；第二次跑脚本输出 `already installed`。

- [ ] **Step 5: 提交**

```bash
git add config/conformance/coord-ref.txt config/ci/install-coord-sdk.sh config/ci/verify.sh config/ci/conformance.sh .github/workflows/ci.yml
git commit -m "build(coord): install pinned coord-java-sdk in ci"
```

---

### Task 2: 把阶段 0 代码干净地并入主线

**Files:**
- Create: `seedj-infra-coord/**`（来自 `feat/seedj-infra-coord` commit `c8e62c8`）
- Modify: `seedj-infra-coord/pom.xml`（加 `core` test-jar 依赖，供 TCK）
- Revert: `seedj-iam-starter/pom.xml`、`seedj-it/pom.xml`、`seedj-jdbc-postgresql/pom.xml`、`seedj-server/hr/pom.xml`、`seedj-workflow-local/pom.xml`、`seedj-infra-local/pom.xml` 的无关版本删除（保持 diff 聚焦）

**Interfaces:**
- Consumes: `CoordAutoConfiguration`、`CoordProperties`、`CoordErrors`、`CoordIdGenerator`、`CoordNumbering`、`CoordGlobalSequencer`、`CoordEventBusTransport`、`CoordProviderGuardAutoConfiguration`（均已存在于该 commit）。
- Produces: 可编译、可跑的 `seedj-infra-coord` 模块。

- [ ] **Step 1: 从 main 开分支并取代码**

```bash
git fetch origin && git switch -c feat/coord-foundation origin/main
git cherry-pick c8e62c8
git checkout origin/main -- seedj-iam-starter/pom.xml seedj-it/pom.xml seedj-jdbc-postgresql/pom.xml seedj-server/hr/pom.xml seedj-workflow-local/pom.xml seedj-infra-local/pom.xml
git commit -m "chore(coord): drop unrelated pom churn"
```

（`seedj-infra-local/.../IdAutoConfiguration.java` 的 `seedj.id.provider` 条件保留：过渡期需要它。）

- [ ] **Step 2: 加 core test-jar 依赖（供 Task 4 TCK）**

在 `seedj-infra-coord/pom.xml` 的 `<dependencies>` 加：

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>core</artifactId>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 3: 跑模块测试**

Run: `./mvnw -o -B -pl seedj-infra-coord -am test`
Expected: 5 个测试类通过（`CoordAutoConfigurationTest`、`CoordEventBusTransportTest`、`CoordGlobalSequencerTest`、`CoordIdGeneratorTest`、`CoordNumberingTest`、`CoordProviderGuardTest`）。

- [ ] **Step 4: 提交**

```bash
git add seedj-infra-coord
git commit -m "feat(coord): land id, numbering, sequencer and eventbus publisher"
```

---

### Task 3: 错误映射表

**Files:**
- Modify: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordErrors.java`
- Test: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordErrorsTest.java`

**Interfaces:**
- Produces: `static IdException id(CoordException e)`、`static EventBusException eventBus(CoordException e, EventBusErrorCode fallback)`。

- [ ] **Step 1: 写参数化测试**

```java
@ParameterizedTest
@CsvSource({
  "UNAVAILABLE,TRANSPORT_UNAVAILABLE",
  "DEADLINE_EXCEEDED,TRANSPORT_UNAVAILABLE",
  "AGENT_UNAVAILABLE,TRANSPORT_UNAVAILABLE",
  "NOT_LEADER,TRANSPORT_UNAVAILABLE",
  "MESSAGE_TOO_LARGE,SEND_FAILED",
  "QUOTA_EXCEEDED,SEND_FAILED",
  "RESOURCE_EXHAUSTED,SEND_FAILED",
  "INVALID_ARGUMENT,SEND_FAILED",
  "INTERNAL,INTERNAL_ERROR"
})
void eventBusMapsByErrorCode(ErrorCode coord, EventBusErrorCode expected) {
    EventBusException e = CoordErrors.eventBus(new CoordException(coord, "x"), EventBusErrorCode.INTERNAL_ERROR);
    assertThat(e.getErrorCode()).isEqualTo(expected);
    assertThat(e).hasCauseInstanceOf(CoordException.class);
}
```

`IdException`/`EventBusException` 的取码方法是 `getErrorCode()`。

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -B -pl seedj-infra-coord test -Dtest=CoordErrorsTest`
Expected: 失败的映射（`INTERNAL` 当前落到 fallback）。

- [ ] **Step 3: 实现**

`CoordErrors.eventBus` 按 Spec §6.3 全表 switch；未知 code → `INTERNAL_ERROR`。`CoordErrors.id`：所有 coord 失败 → `IdErrorCode.STATE_UNAVAILABLE`（本地校验不进 coord），保留 cause 与 coord code 文本。

- [ ] **Step 4: 跑测试**

Run: `./mvnw -o -B -pl seedj-infra-coord test -Dtest=CoordErrorsTest`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-infra-coord
git commit -m "feat(coord): map coord error codes to seedj capability errors"
```

---

### Task 4: Long 生成器 + String 适配 + ID TCK

**Files:**
- Create: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordLongIdGenerator.java`
- Modify: `seedj-infra-coord/src/main/java/cn/byteforce/seedj/infra/coord/CoordIdGenerator.java`
- Test: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordLongIdGeneratorContractTest.java`
- Test: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/FakeIdGen.java`

**Interfaces:**
- `final class CoordLongIdGenerator implements IdGenerator<Long>`（`cn.byteforce.seedj.id.IdGenerator`），构造 `(IdGenClient client, String name, int batchSize)`。
- `CoordIdGenerator(IdGenClient, String, int)` 保留，`id()` 返回 `Long.toString(delegate.id())`。
- `FakeIdGen implements IdGenClient`，`nextId` 自增，`nextBatch(name,count)` 返回连续 `count` 个值。

- [ ] **Step 1: 写 TCK 子类**

```java
class CoordLongIdGeneratorContractTest extends IdGeneratorContractTest {
    @Override
    protected IdGenerator<Long> newGenerator() {
        return new CoordLongIdGenerator(new FakeIdGen(), "test", 8);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -B -pl seedj-infra-coord test -Dtest=CoordLongIdGeneratorContractTest`
Expected: 编译失败（`CoordLongIdGenerator` 不存在）。

- [ ] **Step 3: 实现**

把现有 `CoordIdGenerator` 的批缓存逻辑移到 `CoordLongIdGenerator`（`synchronized id()`、`fetch()`、`close()`）；`CoordIdGenerator` 只做字符串转换。`close()` 后再取号抛 `IdException(IdErrorCode.PROVIDER_CLOSED)`，`close()` 幂等。

- [ ] **Step 4: 跑测试**

Run: `./mvnw -o -B -pl seedj-infra-coord test -Dtest='CoordLongIdGeneratorContractTest,CoordIdGeneratorTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-infra-coord
git commit -m "feat(coord): split long id generator and cover it with the core tck"
```

---

### Task 5: 编号与全局序列语义测试

**Files:**
- Modify: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordNumberingTest.java`
- Modify: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordGlobalSequencerTest.java`

**Interfaces:**
- Consumes: `FakeIdGen`（Task 4）。
- Produces: 名字拼接与并发唯一性的回归测试。

- [ ] **Step 1: 写断言**

```java
@Test
void mapsScopeAndYearToDistinctIdgenNames() {
    FakeIdGen fake = new FakeIdGen();
    CoordNumbering numbering = new CoordNumbering(fake, "numbering");
    assertThat(numbering.next("invoice")).isEqualTo(1L);
    assertThat(numbering.next("invoice", 2026)).isEqualTo(2L);
    assertThat(fake.names()).containsExactly("numbering:invoice", "numbering:invoice_2026");
}

@Test
void rejectsIllegalScope() {
    assertThatThrownBy(() -> new CoordNumbering(new FakeIdGen(), "n").next("Invoice-1"))
        .isInstanceOf(IllegalArgumentException.class);
}
```

`CoordGlobalSequencerTest`：8 线程 × 200 次 `next()`，断言返回集合大小 1600 且严格单调（fake 客户端按调用顺序返回 1..N）。

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -B -pl seedj-infra-coord test -Dtest='CoordNumberingTest,CoordGlobalSequencerTest'`
Expected: 断言失败（当前测试未覆盖 name 拼接）。

- [ ] **Step 3: 实现**

给 `FakeIdGen` 加 `names()`（记录每次调用的 name，线程安全）。`CoordNumbering`/`CoordGlobalSequencer` 行为不改。

- [ ] **Step 4: 跑测试**

Run: 同 Step 2，Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-infra-coord
git commit -m "test(coord): pin numbering and sequencer name mapping"
```

---

### Task 6: EventBus 发布 TCK 与边界

**Files:**
- Create: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordEventBusTransportContractTest.java`
- Modify: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordEventBusTransportTest.java`

**Interfaces:**
- Consumes: `EventBusTransportContractTest`（core test-jar）。
- Produces: fake `MqV2Client`（Mockito mock 接口）驱动的发布测试。

- [ ] **Step 1: 写 TCK 子类**

```java
class CoordEventBusTransportContractTest extends EventBusTransportContractTest {
    private final MqV2Client mq = mock(MqV2Client.class);

    @Override
    protected EventBusTransport newTransport() {
        when(mq.describeTopic(anyString()))
            .thenAnswer(inv -> new MqV2TopicDescription(
                inv.getArgument(0), 1L, 1L, 1, null, 0L, List.of()));
        when(mq.publish(any())).thenReturn(new MqV2PublishResult(0L, 0L, false));
        return new CoordEventBusTransport(mq, "test", 1);
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -B -pl seedj-infra-coord test -Dtest=CoordEventBusTransportContractTest`
Expected: 编译失败（类型/构造参数不匹配）。

- [ ] **Step 3: 补边界测试**

在 `CoordEventBusTransportTest` 增加：

```java
@Test
void sameEventIdRetriesLandOnSamePartition() { /* ArgumentCaptor<MqV2Publish> ×2，断言 partition 相等 */ }

@Test
void staleEpochRefreshesOnceAndRetries() { /* 第一次 publish 抛 TOPIC_EPOCH_STALE，第二次成功；verify describeTopic 2 次 */ }

@Test
void secondStaleEpochFailsClosed() { /* 两次都 stale → CompletionStage 异常完成，publish 只调用 2 次 */ }

@Test
void sendAfterCloseFailsWithProviderClosed() { /* close() 后 send 以 PROVIDER_CLOSED 完成 */ }
```

- [ ] **Step 4: 跑测试**

Run: `./mvnw -o -B -pl seedj-infra-coord test -Dtest='CoordEventBusTransportContractTest,CoordEventBusTransportTest'`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-infra-coord
git commit -m "test(coord): cover eventbus contract, partition stability and epoch refresh"
```

---

### Task 7: coord v2 真集群夹具

**Files:**
- Create: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/fixture/CoordCluster.java`
- Create: `seedj-infra-coord/src/test/resources/coord/server.toml`
- Create: `seedj-infra-coord/src/test/resources/coord/agent.toml`
- Test: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/fixture/CoordClusterSmokeTest.java`
- Modify: `seedj-infra-coord/pom.xml`（加 `org.testcontainers:testcontainers-junit-jupiter` test 依赖；加 `maven-jar-plugin` 的 `test-jar` 执行，供后续模块复用夹具）

**Interfaces:**
- `CoordCluster` 暴露：`String agentHost()`、`int agentPort()`、`CoordClient client()`、`static CoordCluster start()`、`close()`。
- 后续 `seedj-workflow-coord`、`seedj-it` 复用该类的 `start()`/`client()`。
- 镜像可用系统属性 `seedj.coord.test.image` 覆盖，默认 `ghcr.io/byteforce-cn/coord:v0.4.3`。

**关键约束：** agent 拒绝非 loopback 上的明文绑定（`coord-agent/src/lib.rs` 的 bind guard），而 `agent.toml` 不能打开 dev 旁路；开 auth 需要 CCT/Ed25519 签发链路，对测试夹具成本过高。因此夹具用 **Docker host network**：server 与 agent 都绑定 `127.0.0.1`（容器内主机同一 loopback），测试 JVM 直接连随机空闲端口。仅在 Linux 上运行；非 Linux 在 `start()` 里 `Assumptions.assumeTrue` 跳过，无 Docker 同样跳过（`DockerClientFactory.instance().isDockerAvailable()`）。

- [ ] **Step 1: 写 server/agent 配置模板**

`server.toml`（两个地址由 `CoordCluster` 在启动前替换 `__PORT__` 占位符）：

```toml
[node]
id = 1
[network]
grpc_addr = "127.0.0.1:__GRPC__"
raft_addr = "127.0.0.1:__RAFT__"
http_addr = "127.0.0.1:__HTTP__"
[cluster]
cluster_name = "seedj-it"
bootstrap = true
initial_nodes = [{ id = 1, grpc = "127.0.0.1:__GRPC__", raft = "127.0.0.1:__RAFT__" }]
[storage]
data_dir = "/var/lib/coord"
[security]
auth_enabled = false
[multi_raft]
enabled = true
[multi_raft.pd]
enabled = true
[kv_t]
enabled = true
[mq_v2]
enabled = true
```

`agent.toml`：

```toml
agent_addr = "127.0.0.1:__AGENT__"
http_addr = "127.0.0.1:__AGENT_HTTP__"
data_dir = "/var/lib/coord-agent"
discovery_mode = "static"
static_peers = ["127.0.0.1:__GRPC__"]
data_plane = "v2"
[auth]
enabled = false
[services]
idgen = true
lock = true
mq = true
pki = true
transit = true
policy = true
workflow = true
workflow_durable = true
```

（`transit` 需要 KEK；若 agent 以 `missing transit KEK` 退出，给容器加 `COORD_TRANSIT_KEK=$(openssl rand -hex 32)` 环境变量并重试。其余服务失败时按 coord `deploy/docker-compose/README.md` 的 v2 说明补齐。）

- [ ] **Step 2: 写夹具**

```java
public final class CoordCluster implements AutoCloseable {
    private static final String IMAGE = System.getProperty("seedj.coord.test.image",
        "ghcr.io/byteforce-cn/coord:v0.4.3");

    public static CoordCluster start() {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
        // 1) ServerSocket(0) 分配 5 个空闲端口（agent、agent-http、grpc、raft、http）。
        // 2) 把两份 TOML 渲染到临时目录，withCopyToContainer 到 /etc/coord/。
        // 3) 两个 GenericContainer + withNetworkMode("host")：
        //    server 命令 "coord --config /etc/coord/server.toml server"；
        //    agent  命令 "coord agent --agent-config /etc/coord/agent.toml"。
        // 4) 等待 agent http://127.0.0.1:${agentHttp}/healthz 返回 200。
        // 5) 用 CoordConfig.builder().agentHost("127.0.0.1").agentPort(agentPort)
        //    .allowInsecurePlaintext(true).build() 建客户端。
    }
}
```

- [ ] **Step 3: 写冒烟测试**

```java
class CoordClusterSmokeTest {
    @Test
    void idgenAndMqV2Work() {
        try (CoordCluster cluster = CoordCluster.start()) {
            CoordClient client = cluster.client();
            long first = client.idgen().nextId("smoke");
            long second = client.idgen().nextId("smoke");
            assertThat(second).isGreaterThan(first);

            client.mqV2().createTopic("smoke.topic", 1, "smoke-create", null);
            MqV2TopicDescription topic = client.mqV2().describeTopic("smoke.topic");
            assertThat(topic.partitionCount()).isEqualTo(1);
        }
    }
}
```

- [ ] **Step 4: 跑真集群**

Run: `./mvnw -o -B -pl seedj-infra-coord test -Dtest=CoordClusterSmokeTest`
Expected: PASS（首次拉镜像较慢）；`docker ps` 在测试期间可见两个 `--network host` 容器。

- [ ] **Step 5: 提交**

```bash
git add seedj-infra-coord
git commit -m "test(coord): add v2 server+agent testcontainers fixture"
```

---

### Task 8: 装配与守卫测试

**Files:**
- Modify: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordAutoConfigurationTest.java`
- Modify: `seedj-infra-coord/src/test/java/cn/byteforce/seedj/infra/coord/CoordProviderGuardTest.java`

**Interfaces:**
- Consumes: `CoordAutoConfiguration`、`CoordProviderGuardAutoConfiguration`。
- Produces: 每个 provider 键的守卫回归；enabled/disabled 两种上下文的 bean 断言。

- [ ] **Step 1: 写断言**

```java
@Test
void disabledCreatesNoCoordBeans() {
    runner.withPropertyValues("seedj.coord.enabled=false").run(ctx -> {
        assertThat(ctx).doesNotHaveBean(CoordClient.class);
        assertThat(ctx).doesNotHaveBean(IdGenerator.class);
        assertThat(ctx).doesNotHaveBean(Numbering.class);
        assertThat(ctx).doesNotHaveBean(GlobalSequencer.class);
        assertThat(ctx).doesNotHaveBean(EventBusTransport.class);
    });
}

@ParameterizedTest
@ValueSource(strings = {"seedj.eventbus.transport=coord", "seedj.lock.provider=coord",
    "seedj.pki.provider=coord", "seedj.transit.provider=coord", "seedj.numbering.provider=coord",
    "seedj.clock.provider=coord"})
void guardFailsForEveryCoordProvider(String property) {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(CoordProviderGuardAutoConfiguration.class))
        .withPropertyValues(property)
        .run(ctx -> assertThat(ctx.getStartupFailure()).hasMessageContaining(property.split("=")[0]));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `./mvnw -o -B -pl seedj-infra-coord test -Dtest='CoordAutoConfigurationTest,CoordProviderGuardTest'`
Expected: 部分键未被 guard 覆盖而失败。

- [ ] **Step 3: 实现**

`CoordProviderGuard.PROVIDER_KEYS` 补齐 `seedj.lock.provider`、`seedj.pki.provider`、`seedj.transit.provider`、`seedj.numbering.provider`、`seedj.clock.provider`。

再加一个不依赖 agent 的可用性测试 `CoordClientUnreachableTest`：`CoordConfig.builder().agentHost("127.0.0.1").agentPort(1).requestTimeout(Duration.ofMillis(200)).allowInsecurePlaintext(true).build()`，断言 `idgen().nextId("x")` 在 5 秒内抛错而不是挂起。

- [ ] **Step 4: 跑测试**

Run: 同 Step 2，Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add seedj-infra-coord
git commit -m "test(coord): guard every coord-backed provider key"
```

---

### Task 9: 门禁与文档

**Files:**
- Modify: `config/public-api.txt`（追加 3 行）
- Modify: `config/cognitive-load-baseline.properties:19`（`api.publicTypes=610` → `613`）
- Modify: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/ModuleClassPath.java`（MODULES 加 `seedj-infra-coord`）
- Modify: `seedj-it/src/test/java/cn/byteforce/seedj/conformance/ArchitectureTest.java`（新增 coord 依赖规则）
- Modify: `config/coverage-baseline.json`（加 `seedj-infra-coord`，用实测值向下取 0.02）
- Modify: `seedj-infra-coord/README.md`、`docs/guide/modules.md`、`docs/guide/configuration.md`、`CHANGELOG.md`

**Interfaces:**
- Produces: 门禁认可的新模块与 public 类型。

- [ ] **Step 1: 加 public API 与基线**

`config/public-api.txt` 追加（按字母序）：

```text
seedj-infra-coord|cn.byteforce.seedj.infra.coord|CoordAutoConfiguration
seedj-infra-coord|cn.byteforce.seedj.infra.coord|CoordProperties
seedj-infra-coord|cn.byteforce.seedj.infra.coord|CoordProviderGuardAutoConfiguration
```

- [ ] **Step 2: 加覆盖率基线**

先跑 `./mvnw -o -B -pl seedj-infra-coord -am verify -Panalysis`，读 `seedj-infra-coord/target/site/jacoco/jacoco.xml` 的实测 line/branch，向下取到 0.02 的整数倍写入 `config/coverage-baseline.json`。

- [ ] **Step 3: 架构规则**

`ArchitectureTest` 增加：

```java
@ArchTest
static final ArchRule coordDoesNotDependOnBusinessStores =
    noClasses().that().resideInAPackage("cn.byteforce.seedj.infra.coord..")
        .should().dependOnClassesThat()
        .resideInAnyPackage("cn.byteforce.seedj.infra.jdbc..", "cn.byteforce.seedj.starter..");
```

- [ ] **Step 4: 跑门禁**

Run: `./mvnw -o -B -pl seedj-it -am test -Dtest='PublicApiSurfaceTest,ArchitectureTest,PackageInfoTest,SplitPackageTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS（若 ModuleClassPath 未包含新模块，`ArchitectureTest` 会因 `classesDir` 缺失失败——这正是要修的）。

- [ ] **Step 5: 提交**

```bash
git add config seedj-it docs CHANGELOG.md seedj-infra-coord/README.md
git commit -m "docs(coord): register module, public api and coverage floor"
```

---

### Task 10: 全量验证与 PR

- [ ] **Step 1: 本地门禁**

Run: `bash config/ci/verify.sh`
Expected: 末尾 `verify: OK`。

- [ ] **Step 2: 推送并开 PR**

```bash
git push -u origin feat/coord-foundation
gh pr create --base main --fill
gh pr checks --watch
```

Expected: 全部 check 绿；红则修到绿，不得跳过。

- [ ] **Step 3: 合并**

```bash
gh pr merge --squash --delete-branch
git switch main && git pull
```
