package com.orange.playerlibrary.ai;

/**
 * AI Provider 调用异常。
 * retryable=true 表示瞬时错误（网络中断 / 429 限流 / 5xx），可退避重试；
 * retryable=false 表示永久错误（401/403 key 失效、400 参数错），重试无意义。
 */
public class AiException extends Exception {

    private final boolean retryable;
    private final int httpCode;

    public AiException(String message, boolean retryable) {
        this(message, retryable, 0, null);
    }

    public AiException(String message, boolean retryable, int httpCode, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.httpCode = httpCode;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public int getHttpCode() {
        return httpCode;
    }
}
