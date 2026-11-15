package cn.byteforce.seedj.infra.coord;

import static cn.byteforce.seedj.infra.coord.CoordTestFixture.ENDPOINT;

import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.id.IdGeneratorContractTest;

class CoordLongIdGeneratorContractTest extends IdGeneratorContractTest {

    @Override
    protected IdGenerator<Long> newGenerator() {
        return new CoordLongIdGenerator(ENDPOINT, new FakeIdGen(), "test", 8);
    }
}
