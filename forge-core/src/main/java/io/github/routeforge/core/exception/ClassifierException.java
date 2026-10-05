package io.github.routeforge.core.exception;

/** RF_BE_004：宿主的 classifier 回调自身抛错（包装原始异常，保留 cause 供排查）。 */
public class ClassifierException extends ForgeRuntimeException {

    public ClassifierException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String code() {
        return "RF_BE_004";
    }

    @Override
    public int httpStatus() {
        return 500;
    }
}
