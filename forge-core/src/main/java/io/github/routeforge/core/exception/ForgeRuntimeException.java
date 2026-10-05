package io.github.routeforge.core.exception;

import io.github.routeforge.core.contract.ForgeException;

/**
 * 所有 Route Forge 后端异常的基类：把「错误码 + HTTP 状态」钉在类型上。
 *
 * <p>消息文本与 Laravel 适配包逐字一致——跨语言对等验收阶段会把两侧产物做字节级比对，
 * 措辞漂移会造成假失败。调用方推荐按 {@link ForgeException} 捕获后读 {@code code()}。
 */
public abstract class ForgeRuntimeException extends RuntimeException implements ForgeException {

    protected ForgeRuntimeException(String message) {
        super(message);
    }

    protected ForgeRuntimeException(String message, Throwable cause) {
        super(message, cause);
    }
}
