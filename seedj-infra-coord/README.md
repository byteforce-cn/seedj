# seedj-infra-coord

coord 0.4.3 的 seedj 基础设施实现。当前交付 ID、编号、全局序列与 EventBus 发布；不启动
coord server，也不产出可执行制品。

## 本地构建

coord 0.4.2+ 没有发布到 Maven Central（Central 停在 0.4.1），先安装 SDK：

```bash
cd /root/opensource/coord/coord-java-sdk
JAVA_HOME=/opt/jdk21 mvn -B -DskipTests install

cd /root/opensource/seedj
JAVA_HOME=/opt/jdk21 ./mvnw -pl seedj-infra-coord -am -DskipTests install
```

业务应用通过 BOM 引依赖，不写版本：

```xml
<dependency>
    <groupId>cn.byteforce.seedj</groupId>
    <artifactId>seedj-infra-coord</artifactId>
</dependency>
```

## 装配

coord 是显式选择，不是本地实现的替代品。`provider=coord` 但没有
`seedj.coord.enabled=true` 时启动失败（`CoordProviderGuard`），不会悄悄退回本地。

```yaml
seedj:
  coord:
    enabled: true
    agent-host: 127.0.0.1
    agent-port: 19527
    namespace: seedj:hr
    id:
      name: application
      batch-size: 1000
    numbering:
      prefix: numbering
    clock:
      name: global-sequencer
    eventbus:
      partitions: 4
      producer: hr
  id:
    provider: coord
  numbering:
    provider: coord
  clock:
    provider: coord
  eventbus:
    transport: coord
```

鉴权 token 优先读 `seedj.coord.auth.token`，其次 `seedj.coord.auth.token-file`，最后环境变量
`COORD_AUTH_TOKEN`。不要把 token 写进配置文件。

## 已交付 / 下一步

- 已交付：`IdGenerator<String>`、`Numbering`、`GlobalSequencer`、`EventBusTransport.send`
  （MQ v2 `Publish`，eventId 幂等 + 稳定分区）。
- 下一步：EventConsumerStore（KV inflight/done + 自建 DLQ 索引）、Lock、PKI、Transit、
  Workflow。每一项都继续使用 coord 原语，不引入本地兜底。

## 测试夹具（v2）

`src/test/java/.../fixture/CoordCluster.java` 起 server + agent 两个容器（镜像
`ghcr.io/byteforce-cn/coord:v0.4.3`，可用 `-Dseedj.coord.test.image` 覆盖），data_plane=v2，
`mq_v2` 可用。已验证的启动约束：

- agent 拒绝非 loopback 明文绑定，`agent.toml` 又不允许开 dev 旁路，所以两个容器都用
  **host network**、绑定 `127.0.0.1`；仅 Linux 可跑，无 Docker 时测试跳过。
- `server` 的 `--addr` / `--cluster-name` 有 CLI 默认值，会覆盖 TOML，必须显式传；
  `agent` 的配置文件参数是 `--agent-config`（不是全局 `--config`）。
- `multi_raft.enabled=true` 必须同时给 `[[multi_raft.initial_regions]]`（至少一个），
  `multi_raft.pd.target_replicas` 不能超过单节点成员数（设为 1）。
- agent 的 `data_dir` 不要用 `/var/lib/coord-agent`（镜像内非 root，不可写）；transit 需要
  `COORD_TRANSIT_KEK`（hex64）。
