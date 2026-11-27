package cn.byteforce.seedj.infra.coord.file;

import cn.byteforce.seedj.file.FileService;
import cn.byteforce.seedj.file.FileServiceContractTest;
import cn.byteforce.seedj.infra.coord.fixture.FakeObjectStore;
import java.time.Clock;

/** coord 实现的契约测试：每个用例一套干净的内存对象存储。 */
class CoordFileServiceContractTest extends FileServiceContractTest {

    @Override
    protected FileService newFileService() {
        return new CoordFileService(
                new FakeObjectStore(),
                "127.0.0.1:19527",
                "seedj:contract",
                "seedj-files",
                1024 * 1024,
                Clock.systemUTC());
    }
}
