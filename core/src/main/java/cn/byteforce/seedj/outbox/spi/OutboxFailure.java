package cn.byteforce.seedj.outbox.spi;

/**
 * 一次投递失败。
 *
 * @param errorType    失败类型（通常是异常类名）
 * @param errorMessage 失败描述
 * @param retryable    relay 是否可以自动重试
 */
public record OutboxFailure(String errorType, String errorMessage, boolean retryable) {
}
