package cn.byteforce.seedj.infra.coord;

/** 测试共用的 agent 地址：只影响失败消息文本，不涉及真实连接。 */
final class CoordTestFixture {

    static final CoordEndpoint ENDPOINT = new CoordEndpoint("127.0.0.1:19527");

    private CoordTestFixture() {}
}
