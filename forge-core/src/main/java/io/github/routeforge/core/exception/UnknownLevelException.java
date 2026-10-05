package io.github.routeforge.core.exception;

/** RF_BE_002：请求的层级名不在 levels 配置中。 */
public class UnknownLevelException extends ForgeRuntimeException {

    public UnknownLevelException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "RF_BE_002";
    }

    @Override
    public int httpStatus() {
        // 唯一非 500 的错误码：层级名是外部输入，拼错属客户端可修正的 404
        return 404;
    }
}
