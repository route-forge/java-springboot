package io.github.routeforge.core.exception;

/** RF_BE_006：classifier 返回的层级名不在 levels 配置中（无论 strict_mode 开关都抛）。 */
public class UnknownClassifierTierException extends ForgeRuntimeException {

    public UnknownClassifierTierException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "RF_BE_006";
    }

    @Override
    public int httpStatus() {
        return 500;
    }
}
