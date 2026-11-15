package cn.byteforce.seedj.infra.coord;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.byteforce.coord.sdk.mq.v2.MqV2Client;
import cn.byteforce.coord.sdk.mq.v2.MqV2PublishResult;
import cn.byteforce.coord.sdk.mq.v2.MqV2TopicDescription;
import cn.byteforce.seedj.eventbus.spi.EventBusTransport;
import cn.byteforce.seedj.eventbus.spi.EventBusTransportContractTest;
import java.util.List;

class CoordEventBusTransportContractTest extends EventBusTransportContractTest {

    private final MqV2Client mq = mock(MqV2Client.class);

    @Override
    protected EventBusTransport newTransport() {
        when(mq.describeTopic(anyString()))
                .thenAnswer(
                        inv ->
                                new MqV2TopicDescription(
                                        inv.getArgument(0), 1L, 1L, 1, null, 0L, List.of()));
        when(mq.publish(any())).thenReturn(new MqV2PublishResult(0L, 0L, false));
        return new CoordEventBusTransport(mq, "test", 1);
    }
}
