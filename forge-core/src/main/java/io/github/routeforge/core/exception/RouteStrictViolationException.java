package io.github.routeforge.core.exception;

import io.github.routeforge.core.support.StrictViolationScanner;

/**
 * RF_BE_009：严格模式整表扫描发现的配置违规聚合。
 *
 * <p>取代逐条 fail-fast 的 RF_BE_001（命名路由未归级）与 RF_BE_005（有层级无路由名）：严格模式的目的是
 * 让问题一次暴露干净，而不是「修一条刷一条」。消息文本由
 * {@link StrictViolationScanner.Violations#format()} 单点生成——命令行红色清单与 HTTP 错误体共用同一措辞；
 * {@link #violations()} 另给出结构化清单，供管理器页面按行高亮定位。
 *
 * <p>层级名拼错、classifier 抛错等更精确的配置错误仍走各自错误码（RF_BE_002 / 004 / 006），
 * 在这里只作为 {@code unresolved} 信息附录出现。
 */
public class RouteStrictViolationException extends ForgeRuntimeException {

    private final StrictViolationScanner.Violations violations;

    public RouteStrictViolationException(StrictViolationScanner.Violations violations) {
        super(String.join("\n", violations.format()));
        this.violations = violations;
    }

    /** 结构化违规清单；仅在 {@code debug=true} 时随错误体下发（清单本身是宿主内部结构的目录）。 */
    public StrictViolationScanner.Violations violations() {
        return violations;
    }

    @Override
    public String code() {
        return "RF_BE_009";
    }

    @Override
    public int httpStatus() {
        return 500;
    }
}
