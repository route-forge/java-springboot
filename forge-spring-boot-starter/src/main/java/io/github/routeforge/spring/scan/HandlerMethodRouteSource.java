package io.github.routeforge.spring.scan;

import io.github.routeforge.core.contract.RouteSource;
import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.uri.UriTemplate;
import io.github.routeforge.spring.annotation.ForgeTiers;
import io.github.routeforge.core.support.WarningSink;
import io.github.routeforge.spring.naming.RouteCandidate;
import io.github.routeforge.spring.naming.RouteNamingStrategy;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.lang.Nullable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 路由来源：把 Spring 的 handler 映射表变成核心层消费的 {@link RouteInfo} 序列。
 *
 * <p>每条 {@code (RequestMappingInfo, HandlerMethod)} 会展开成<b>一个 URI 一条</b>记录：
 * 一个映射带多个路径（{@code path = {"/a", "/b"}}）时，Laravel 侧天然是两条独立路由，
 * 这里也对齐成两条——否则前端按名字拿到的 URI 会取决于取哪一个，等于把歧义留到运行期。
 *
 * <p>两条来自实测的形态处理（见 {@code SpringRoutingModelSpikeTest}）：
 * <ul>
 *   <li>没有 path 条件的映射会被 Spring materialize 成 {@code ["", "/"]} 两个 pattern，且<b>只匹配根路径</b>
 *       （深路径 404）。二者是同一个地址，这里归一为一个 {@code "/"}，否则会产出两条重复且有一条 URI 为空串的纪录；</li>
 *   <li>函数式路由（{@code RouterFunction}）的路径谓词不暴露模板字符串，本版不支持，
 *       届时按 attributes 打标单独设计（见 PROGRESS 风险清单）。</li>
 * </ul>
 *
 * <p>{@code methods} 口径见 SPEC §4.1：声明了方法就按声明下发，GET 额外附上 HEAD（运行期实测可响应），
 * 未声明方法时下发完整标准方法集（实测任何方法都能匹配，空数组会让前端拿不到默认方法）。
 */
public final class HandlerMethodRouteSource implements RouteSource {

    /** 未声明 method 的映射实际能匹配的方法集（顺序固定，前端取第一个非 HEAD 作为默认方法）。 */
    static final List<String> ANY_METHOD_VERBS = List.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE");

    private final List<RequestMappingHandlerMapping> mappings;

    @Nullable
    private final RouteNamingStrategy namingStrategy;

    private final WarningSink warnings;

    public HandlerMethodRouteSource(List<RequestMappingHandlerMapping> mappings,
            @Nullable RouteNamingStrategy namingStrategy, WarningSink warnings) {
        this.mappings = List.copyOf(mappings);
        this.namingStrategy = namingStrategy;
        this.warnings = warnings;
    }

    /** 只有一个映射（绝大多数宿主）的便捷构造。 */
    public HandlerMethodRouteSource(RequestMappingHandlerMapping mapping) {
        this(List.of(mapping), null, WarningSink.NOOP);
    }

    /** 只有一个映射、但接了命名策略的形态（宿主只有一份 {@code RequestMappingHandlerMapping} 时的常态）。 */
    public HandlerMethodRouteSource(RequestMappingHandlerMapping mapping,
            @Nullable RouteNamingStrategy namingStrategy) {
        this(List.of(mapping), namingStrategy, WarningSink.NOOP);
    }

    @Override
    public List<RouteInfo> routes() {
        List<RouteInfo> routes = new ArrayList<>();
        for (RequestMappingHandlerMapping mapping : mappings) {
            mapping.getHandlerMethods().forEach((info, handlerMethod) -> routes.addAll(explode(info, handlerMethod)));
        }
        return List.copyOf(routes);
    }

    private List<RouteInfo> explode(RequestMappingInfo info, HandlerMethod handlerMethod) {
        List<String> patterns = patternStrings(info);
        ForgeDeclaration declared = ForgeDeclaration.of(handlerMethod);
        List<String> middleware = middlewareOf(declared, handlerMethod);
        List<String> methods = verbsOf(info);
        String tier = declared.tier() != null ? declared.tier() : ForgeTiers.resolve(handlerMethod.getMethod());
        List<RouteInfo> out = new ArrayList<>(patterns.size());

        for (String pattern : patterns) {
            UriTemplate template = UriTemplate.parse(pattern);
            if (!declared.optional().isEmpty()) {
                template = template.withOptional(declared.optional());
            }
            Map<String, Object> defaults = defaultsFor(template, declared.defaults(), pattern);
            out.add(new RouteInfo(
                    nameOf(declared, handlerMethod, pattern),
                    template.normalized(),
                    methods,
                    template.parameters(),
                    defaults,
                    middleware,
                    tier,
                    declared.aliases(),
                    handlerMethod));
        }
        return List.copyOf(out);
    }

    /**
     * 标签：显式声明优先，没声明才用守卫注解派生的结果（SPEC §4.4 铁律 2「不要求重复声明」）。
     *
     * <p>两者都在场且不一致时<b>不报错</b>——守卫可以有很多名字，对不上是常态——只经 {@link WarningSink}
     * 出一条提示。装配层是<b>无条件</b>接上 sink 的（{@code ForgeAutoConfiguration} 里用
     * {@code @ConditionalOnMissingBean} 给 {@code Slf4jWarningSink}），不按 strict-mode 收口：那个 sink
     * 被严格模式扫描等多处共用，一刀切关掉会连带别的告警一起哑掉；要静音由宿主自己注册 {@code WarningSink} bean 覆盖。
     *
     * <p>提示频率等于真正扫描的频率，即缓存 miss；{@code debug=true} 旁路缓存时会退化成每请求一条。
     */
    private List<String> middlewareOf(ForgeDeclaration declared, HandlerMethod handlerMethod) {
        List<String> derived = GuardLabels.derive(handlerMethod);
        if (declared.middleware().isEmpty()) {
            return derived;
        }
        if (!derived.isEmpty() && !sameLabels(declared.middleware(), derived)) {
            warnings.warning("handler " + handlerMethod.getBeanType().getSimpleName() + "#"
                    + handlerMethod.getMethod().getName() + " 上显式声明的 middleware " + declared.middleware()
                    + " 与守卫注解派生的标签 " + derived + " 不一致，已采用显式声明；派生结果仅作提示，不判错");
        }
        return declared.middleware();
    }

    private static boolean sameLabels(List<String> first, List<String> second) {
        return new LinkedHashSet<>(first).equals(new LinkedHashSet<>(second));
    }

    /** 名字：显式声明优先；没声明才问命名策略（默认无策略即返回 null，等于未命名）。 */
    @Nullable
    private String nameOf(ForgeDeclaration declared, HandlerMethod handlerMethod, String pattern) {
        if (declared.declaresName()) {
            return declared.name();
        }
        if (namingStrategy == null) {
            return null;
        }
        return namingStrategy.nameOf(new RouteCandidate(
                handlerMethod.getBeanType(), handlerMethod.getMethod().getName(), pattern));
    }

    /**
     * Spring 给的是 PathPattern 对象，取其原文模板（Framework 7 不公开变量名，参数名由核心层自解析）。
     *
     * <p>空串 pattern 归一为 {@code "/"} 并去重：无 path 条件的映射会同时给出 {@code ""} 与 {@code "/"}，
     * 它们是同一个地址，不去重就会产出两条记录（其中一条 URI 是空串）。
     */
    private static List<String> patternStrings(RequestMappingInfo info) {
        PathPatternsRequestCondition pathPatterns = info.getPathPatternsCondition();
        List<String> raw = pathPatterns == null
                ? List.copyOf(info.getPatternValues())
                : pathPatterns.getPatterns().stream()
                        .map(pattern -> pattern.getPatternString())
                        .toList();

        List<String> patterns = new ArrayList<>();
        for (String pattern : raw) {
            String normalized = pattern.isEmpty() ? "/" : pattern;
            if (!patterns.contains(normalized)) {
                patterns.add(normalized);
            }
        }
        return List.copyOf(patterns);
    }

    private static List<String> verbsOf(RequestMappingInfo info) {
        Set<org.springframework.web.bind.annotation.RequestMethod> declared = info.getMethodsCondition().getMethods();
        if (declared.isEmpty()) {
            return ANY_METHOD_VERBS;
        }
        List<String> verbs = new ArrayList<>();
        declared.forEach(verb -> verbs.add(verb.name()));
        // GET 的映射运行期也服务 HEAD（实测），如实一并下发
        if (verbs.contains("GET") && !verbs.contains("HEAD")) {
            verbs.add("HEAD");
        }
        return List.copyOf(verbs);
    }

    /** 默认值必须落在模板参数上；名字写错时直接拒绝，不静默丢一个默认值。 */
    private static Map<String, Object> defaultsFor(UriTemplate template, Map<String, String> defaults, String pattern) {
        if (defaults.isEmpty()) {
            return Map.of();
        }
        List<String> parameters = template.parameters();
        Map<String, Object> out = new LinkedHashMap<>();
        defaults.forEach((name, value) -> {
            if (!parameters.contains(name)) {
                throw new IllegalArgumentException("声明的参数默认值 [" + name + "] 不是 URI 模板 " + pattern
                        + " 的路径参数；模板里的参数是: " + (parameters.isEmpty() ? "（无）" : parameters));
            }
            out.put(name, value);
        });
        // 保序：默认值的键序会原样出现在 parameter_defaults 里
        return Collections.unmodifiableMap(new LinkedHashMap<>(out));
    }
}
