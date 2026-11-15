package cn.byteforce.seedj.infra.coord;

import cn.byteforce.seedj.id.IdGenerator;
import cn.byteforce.seedj.id.IdGeneratorContractTest;

class CoordLongIdGeneratorContractTest extends IdGeneratorContractTest {

    @Override
    protected IdGenerator<Long> newGenerator() {
        return new CoordLongIdGenerator(new FakeIdGen(), "test", 8);
    }
}
