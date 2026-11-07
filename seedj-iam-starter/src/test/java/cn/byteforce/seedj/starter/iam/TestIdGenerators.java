package cn.byteforce.seedj.starter.iam;

import cn.byteforce.seedj.id.IdGenerator;
import java.util.concurrent.atomic.AtomicLong;

/** Deterministic ID generators for IAM tests. */
public final class TestIdGenerators {

    private TestIdGenerators() {}

    public static IdGenerator<String> sequential() {
        AtomicLong sequence = new AtomicLong();
        return () -> "test-" + sequence.incrementAndGet();
    }
}
