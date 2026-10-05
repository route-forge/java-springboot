package io.github.routeforge.core.exception;

/** RF_BE_003：配置的 {@code cache-driver} 不可用。 */
public class CacheDriverException extends ForgeRuntimeException {

    public CacheDriverException(String message) {
        super(message);
    }

    public CacheDriverException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String code() {
        return "RF_BE_003";
    }

    @Override
    public int httpStatus() {
        return 500;
    }
}
