package cn.byteforce.seedj.domain.exception;

import cn.byteforce.seedj.core.exception.BaseException;

/**
 * 聚合不存在。领域层可感知的业务异常。
 */
public class AggregateNotFoundException extends BaseException {

  public AggregateNotFoundException(String repositoryType, Object id) {
    super(-1, """
        Aggregate not found.
          repository: %s
          id:         %s
        Fix: check that the id is correct, or that the aggregate was saved
             in a previous transaction.
        """.formatted(repositoryType, id));
  }
}
