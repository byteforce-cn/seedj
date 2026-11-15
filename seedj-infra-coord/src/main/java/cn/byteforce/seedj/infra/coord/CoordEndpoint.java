package cn.byteforce.seedj.infra.coord;

/**
 * 诊断用的 agent 地址。
 *
 * <p>每个 coord 失败都必须点名它：{@code provider=coord} 时最常见的事故是地址写错或 agent 没起来，
 * 而 SDK 的原始消息（例如 {@code UNAVAILABLE: io exception}）不含地址。
 */
record CoordEndpoint(String value) {

    CoordEndpoint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("coord endpoint must not be blank");
        }
    }

    static CoordEndpoint of(CoordProperties properties) {
        return new CoordEndpoint(properties.getAgentHost() + ":" + properties.getAgentPort());
    }

    @Override
    public String toString() {
        return value;
    }
}
