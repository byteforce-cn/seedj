package cn.byteforce.seedj.infra.coord.fixture;

import static org.assertj.core.api.Assertions.assertThat;

import cn.byteforce.coord.sdk.CoordClient;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicDescription;
import org.junit.jupiter.api.Test;

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
