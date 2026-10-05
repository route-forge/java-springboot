package io.github.routeforge.core.exception;

/** RF_BE_008：别名指向的路由名不存在（悬空别名，fail-fast）。 */
public class AliasTargetException extends ForgeRuntimeException {

    public AliasTargetException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "RF_BE_008";
    }

    @Override
    public int httpStatus() {
        return 500;
    }
}
