package io.github.routeforge.core.exception;

/** RF_BE_001：{@code TierResolver.resolve()} 直接调用时，严格模式下该条命名路由未命中任何层级。 */
public class RouteTierNotAssignedException extends ForgeRuntimeException {

    public RouteTierNotAssignedException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "RF_BE_001";
    }

    @Override
    public int httpStatus() {
        return 500;
    }
}
