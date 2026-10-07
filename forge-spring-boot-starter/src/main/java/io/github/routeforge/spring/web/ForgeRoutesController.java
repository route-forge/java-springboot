package io.github.routeforge.spring.web;

import io.github.routeforge.core.exception.ForgeRuntimeException;
import io.github.routeforge.core.exception.RouteStrictViolationException;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * forge 的两个元信息端点（SPEC §2）。
 *
 * <p>前缀走配置占位符 {@code ${forge.endpoint-prefix}}：Spring 注册映射时用 Environment 解析，
 * 宿主改前缀不用改代码；核心层下发的 {@code config.endpoint_prefix} 与这里同一规范化，不分叉。
 *
 * <p>摘要只给概览（含每层级的 {@code route} 自描述），层级明细各自一个路径按需懒加载——
 * <b>任何情况下都不在摘要里内联层级路由表</b>，受保护层级的明细不得预置进公开响应。
 *
 * <p>错误处理放在本类而不是全局 advice，两个原因缺一不可：
 * <ol>
 *   <li>层级端点的错误体要带 {@code level} 上下文（PHP 侧同形态），全局 advice 拿路径变量值很别扭；</li>
 *   <li>starter 不提供全局 {@code @RestControllerAdvice}——那会拦截宿主自己的控制器异常，
 *       一个元信息包不该改宿主的行为。</li>
 * </ol>
 *
 * <p>不加鉴权：{@code endpoint-middleware} 在 Java 侧只是声明值（SPEC §4.4 差异 4），
 * {@code /_forge/**} 的保护由宿主的 {@code authorizeHttpRequests} 负责。
 */
@RestController
@RequestMapping("${forge.endpoint-prefix:/_forge/routes}")
public class ForgeRoutesController {

    private final ForgeRouteRegistry registry;
    private final boolean debug;

    /**
     * @param debug 来自 Spring 的 {@code debug} 属性（开发环境判据，SPEC §4.7）：
     *              只有它为真时，严格模式的结构化 {@code violations} 才随错误体下发——
     *              那份清单是宿主越界路由的名字与 URI 目录，属内部结构信息。
     */
    public ForgeRoutesController(ForgeRouteRegistry registry, boolean debug) {
        this.registry = registry;
        this.debug = debug;
    }

    /** {@code GET {endpoint_prefix}}：层级概览 + 全局配置。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> summary() {
        try {
            return ResponseEntity.ok(registry.summary());
        } catch (ForgeRuntimeException e) {
            return errorBody(e, Map.of());
        }
    }

    /**
     * {@code GET {endpoint_prefix}/{level}}：该层级的路由元信息。
     *
     * <p>{@code unassigned} 是与已定义层级结构完全一致的特殊层级；未知层级名 → RF_BE_002（404）。
     */
    @GetMapping("/{level}")
    public ResponseEntity<Map<String, Object>> level(@PathVariable("level") String level) {
        try {
            return ResponseEntity.ok(registry.routesForLevel(level));
        } catch (ForgeRuntimeException e) {
            return errorBody(e, Map.of("level", level));
        }
    }

    /** 错误体：{@code {"error": {code, message, ...上下文}}}，状态码取自异常自身。 */
    private ResponseEntity<Map<String, Object>> errorBody(ForgeRuntimeException e, Map<String, Object> context) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", e.code());
        error.put("message", e.getMessage());
        error.putAll(context);

        if (debug && e instanceof RouteStrictViolationException strict) {
            error.put("violations", strict.violations().toWireMap());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        return ResponseEntity.status(e.httpStatus()).body(body);
    }
}
