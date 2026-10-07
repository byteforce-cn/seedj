#!/bin/bash
# ==============================================================================
# coord 本地集成测试环境（单容器内：server + agent + loopback 转发器）
#
# 为什么不用 `coord dev`（v0.2.0 实测结论）：
#   1) dev 模式不启用任何可选服务（ADR-0001「默认关」）——Config/Cache 等 RPC
#      返回 UNIMPLEMENTED，无法覆盖 config/registry/cache/mq/workflow/transit；
#   2) dev 模式绑定非 loopback 会被 Raft fail-closed（R-SEC-03）拒绝启动，
#      而容器端口映射只能命中非 loopback 监听；
#   3) 故采用与上游 scripts/ci-java-it-cluster.sh 相同的「server + agent」进程
#      组合（loopback 监听、免鉴权），再由 perl 转发器把 Agent gRPC/HTTP 端口
#      从 0.0.0.0 暴露给 Testcontainers 端口映射（Colima/gvproxy 只转发非
#      loopback 监听）。
#
# 进程拓扑（容器内）：
#   perl fwd.pl 0.0.0.0:29527 -> 127.0.0.1:19527   （Agent gRPC，宿主入口）
#   perl fwd.pl 0.0.0.0:29528 -> 127.0.0.1:19528   （Agent HTTP，诊断用）
#   coord server 127.0.0.1:50051（raft 127.0.0.1:50052，bootstrap 单节点）
#   coord agent  127.0.0.1:19527（[services] 显式开启，见 agent.toml）
# ==============================================================================
set -e

COORD=/usr/local/bin/coord
IT_DIR=/opt/coord-it
DATA_DIR=/var/lib/coord/it

# --fresh 语义：每次启动清空数据目录，保证测试隔离
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR/server" "$DATA_DIR/agent"

# 转发器先行：宿主最早的就绪探测（连接映射端口）不会因转发器未起而空转
perl "$IT_DIR/fwd.pl" 0.0.0.0:29527 127.0.0.1:19527 &
perl "$IT_DIR/fwd.pl" 0.0.0.0:29528 127.0.0.1:19528 &

# docker stop → SIGTERM：转发并优雅关闭子进程
trap 'kill $(jobs -p) 2>/dev/null || true' TERM INT

"$COORD" server --config "$IT_DIR/server.toml" --data-dir "$DATA_DIR/server" --bootstrap &

# 等待 Server gRPC 就绪（bash /dev/tcp；镜像内仅 bash 提供，勿改 /bin/sh）
ready=0
for _ in $(seq 1 240); do
  if (echo > /dev/tcp/127.0.0.1/50051) 2>/dev/null; then ready=1; break; fi
  sleep 0.5
done
if [ "$ready" -ne 1 ]; then
  echo "FATAL: coord server not ready on 127.0.0.1:50051 within 120s" >&2
  exit 1
fi

# 等待单节点 Raft 选主完成（Agent 的 Registry/Config Watch 订阅需要 Leader）
sleep 2

"$COORD" agent --agent-config "$IT_DIR/agent.toml" --data-dir "$DATA_DIR/agent" &

wait
