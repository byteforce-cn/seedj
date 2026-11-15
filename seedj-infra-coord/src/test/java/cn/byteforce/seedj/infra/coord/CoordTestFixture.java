package cn.byteforce.seedj.infra.coord;

import cn.byteforce.seedj.infra.coord.fixture.CoordCluster;

/** 测试共用的 agent 地址：只影响失败消息文本，不涉及真实连接。 */
final class CoordTestFixture {

    static final CoordEndpoint ENDPOINT = new CoordEndpoint("127.0.0.1:19527");

    static CoordEndpoint clusterEndpoint(CoordCluster cluster) {
        return new CoordEndpoint(cluster.agentHost() + ":" + cluster.agentPort());
    }

    private CoordTestFixture() {}
}
