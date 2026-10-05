package io.github.routeforge.core.exception;

/**
 * RF_BE_010（Java 专属新增）：命名三通道对同一 handler 方法给出互相冲突的事实。
 *
 * <p>Spring 侧的命名通道为 {@code @ForgeRoute} 组合注解、{@code @Forge} 副注解、{@code RouteNamingStrategy}
 * 派生策略。三者并存时按「组合注解 &gt; 副注解 &gt; 派生」取值；<b>同一层级的事实被两条通道给了不同值</b>
 * （例如两条都给了 name 且不同）即为本异常——静默择一会让宿主以为生效的那条其实没生效。
 *
 * <p>不占用 Laravel 侧的 RF_BE_007（该码对应 PHP 析构期的 Registrar 属性丢弃告警，Spring 无此生命周期）。
 */
public class ConflictingRouteDeclarationException extends ForgeRuntimeException {

    public ConflictingRouteDeclarationException(String message) {
        super(message);
    }

    @Override
    public String code() {
        return "RF_BE_010";
    }

    @Override
    public int httpStatus() {
        return 500;
    }
}
