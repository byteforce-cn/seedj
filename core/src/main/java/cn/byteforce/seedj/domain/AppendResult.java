package cn.byteforce.seedj.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * 追加结果：新行与被封口的旧行（创建时为空）。
 *
 * @param appended   新追加的行
 * @param superseded 被封口的旧行，创建时 {@link Optional#empty()}
 * @author ByteForce
 * @since 1.0.0
 */
public record AppendResult(TemporalRow appended, Optional<TemporalRow> superseded) {

    public AppendResult {
        Objects.requireNonNull(appended, "appended");
        superseded = superseded == null ? Optional.empty() : superseded;
    }
}
