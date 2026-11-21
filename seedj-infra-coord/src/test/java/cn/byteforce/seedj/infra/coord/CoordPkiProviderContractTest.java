package cn.byteforce.seedj.infra.coord;

import cn.byteforce.coord.sdk.transit.TransitClient;
import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import cn.byteforce.seedj.infra.coord.fixture.FakePkiClient;
import cn.byteforce.seedj.provider.pki.PkiProvider;
import cn.byteforce.seedj.provider.pki.PkiProviderContractTest;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.mockito.Mockito;

/** coord 适配器跑公共契约套件（内存 fake，不依赖 Docker）。 */
class CoordPkiProviderContractTest extends PkiProviderContractTest {

    @Override
    protected PkiProvider newProvider() {
        FakeCoordBackend backend = new FakeCoordBackend();
        String prefix = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return new CoordPkiProvider(
                new FakePkiClient(),
                Mockito.mock(TransitClient.class),
                new CoordPkiState(
                        new CoordKvStore(backend.kv(), backend.txn()),
                        CoordKeySpace.of("seedj:test", "pki")),
                new CoordEndpoint("127.0.0.1:19527"),
                prefix,
                Duration.ofHours(24),
                Clock.systemUTC());
    }
}
