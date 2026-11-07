package cn.byteforce.seedj.domain.exception;

import cn.byteforce.seedj.core.exception.ContractErrorCode;
import cn.byteforce.seedj.core.exception.ContractException;
import java.util.Map;

/**
 * 聚合不存在：统一表达为 {@link ContractErrorCode#OBJECT_NOT_FOUND}（404）。
 *
 * <p>领域层可以直接抛出它；Web 边界由 {@code ContractErrorHandler} 映射为契约信封。
 *
 * @author ByteForce
 * @since 1.0.0
 */
public class AggregateNotFoundException extends ContractException {

    /**
     * 构造异常。
     *
     * @param repositoryType 聚合/仓储类型（诊断用）
     * @param id 聚合标识
     */
    public AggregateNotFoundException(String repositoryType, Object id) {
        super(
                ContractErrorCode.OBJECT_NOT_FOUND,
                "aggregate not found: " + repositoryType + "/" + id,
                Map.of(
                        "repository",
                        String.valueOf(repositoryType),
                        "aggregate_id",
                        String.valueOf(id)),
                null);
    }
}
