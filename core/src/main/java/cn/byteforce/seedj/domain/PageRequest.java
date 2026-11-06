package cn.byteforce.seedj.domain;

/**
 * 分页请求：页码从 1 开始，单页最多 200 条。
 *
 * <p>边界值在构造期失败（无效载荷），避免把 {@code size=0} 或超大页带到 SQL 层。</p>
 *
 * @param page 页码（>=1）
 * @param size 每页条数（1..200）
 * @author ByteForce
 * @since 1.0.0
 */
public record PageRequest(int page, int size) {

    /** 单页最大条数。 */
    public static final int MAX_SIZE = 200;

    public PageRequest {
        if (page < 1) {
            throw new cn.byteforce.seedj.core.exception.ContractException(
                    cn.byteforce.seedj.core.exception.ContractErrorCode.INVALID_PAYLOAD,
                    "page must be >= 1: " + page);
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new cn.byteforce.seedj.core.exception.ContractException(
                    cn.byteforce.seedj.core.exception.ContractErrorCode.INVALID_PAYLOAD,
                    "size must be between 1 and " + MAX_SIZE + ": " + size);
        }
    }

    /**
     * 默认分页：第 1 页、20 条。
     *
     * @return 默认分页
     */
    public static PageRequest defaults() {
        return new PageRequest(1, 20);
    }

    /**
     * 偏移量。
     *
     * @return 跳过的条数
     */
    public long offset() {
        return (long) (page - 1) * size;
    }
}
