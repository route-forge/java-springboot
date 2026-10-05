package io.github.routeforge.core.exception;

/**
 * RF_BE_005：单条语义保留——{@code resolve()} 直接调用时，严格模式下路由设了层级却没有路由名。
 *
 * <p>forge 的两条消费路径（HTTP 端点与命令行）自 common 1.2.0 起不再逐条抛它，
 * 统一由聚合的 {@code RF_BE_009} 一次报全；本类保留给显式调用 {@code resolve()} 的宿主代码。
 */
public class RouteMissingNameException extends ForgeRuntimeException {

    public RouteMissingNameException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "RF_BE_005";
    }

    @Override
    public int httpStatus() {
        return 500;
    }
}
