package cn.byteforce.seedj.starter.eis.domain;

import cn.byteforce.seedj.core.exception.BaseException;

import java.io.Serial;

/**
 * 聚合不存在。
 *
 * <p>{@code code} 与 {@code ResponseCode.NOT_FOUND} 取同值（10004），保证
 * 「资源不存在」在全库只有一个对外语义；HTTP 状态沿用 seedj 契约的缺省值
 * （业务失败以响应体 code 为准，ADR-0002）。</p>
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class AggregateNotFoundException extends BaseException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 错误码，与 {@code ResponseCode.NOT_FOUND} 同值。
     */
    public static final int CODE = 10004;

    /**
     * 构造异常。
     *
     * @param repositoryType 仓储类型名（排障用）
     * @param id             聚合标识
     */
    public AggregateNotFoundException(String repositoryType, Object id) {
        super(CODE, """
                Aggregate not found.
                  repository: %s
                  id:         %s
                Fix: check that the id is correct, or that the aggregate was saved
                     in a previous transaction.
                """.formatted(repositoryType, id));
    }
}
