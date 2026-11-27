package cn.byteforce.seedj.infra.coord;

import cn.byteforce.seedj.file.FileService;
import cn.byteforce.seedj.file.FileServiceContractTest;
import cn.byteforce.seedj.infra.coord.fixture.FakeCoordBackend;
import cn.byteforce.seedj.infra.coord.fixture.FakeObjectStore;
import java.time.Clock;

/** coord 实现的契约测试：每个用例一套干净的内存 KV + 对象存储。 */
class CoordFileServiceContractTest extends FileServiceContractTest {

    @Override
    protected FileService newFileService() {
        FakeCoordBackend backend = new FakeCoordBackend();
        return new CoordFileService(
                new FakeObjectStore(),
                new CoordKvStore(backend.kv(), backend.txn()),
                CoordKeySpace.of("seedj:contract", "file"),
                new CoordEndpoint("127.0.0.1:19527"),
                "seedj-files",
                1024 * 1024,
                Clock.systemUTC());
    }
}
