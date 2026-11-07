package cn.byteforce.seedj.eventbus.spi;

/**
 * 一次消费投递失败。
 *
 * @param errorType 失败类型（通常是异常类名）
 * @param errorMessage 失败描述
 * @param retryable 是否可自动重试
 */
public record DeliveryFailure(String errorType, String errorMessage, boolean retryable) {}
