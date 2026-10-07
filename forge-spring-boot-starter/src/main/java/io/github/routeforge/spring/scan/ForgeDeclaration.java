package io.github.routeforge.spring.scan;

import io.github.routeforge.spring.annotation.Forge;
import io.github.routeforge.spring.annotation.ForgeRoute;
import io.github.routeforge.spring.annotation.ForgeTier;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

/**
 * 一个 handler 方法上「三条声明通道」合并后的 forge 事实。
 *
 * <p>通道与优先级（SPEC §4.1）：{@code @ForgeRoute}（组合注解）＞ {@code @Forge}（副注解）
 * ＞ Spring 原生 {@code @RequestMapping(name=...)}；名字三者都没给时再由 {@code RouteNamingStrategy} 派生。
 *
 * <p><b>冲突一律 fail-fast，不择一</b>：两条通道都对同一事实表态且值不同，说明宿主的意图不明确，
 * 静默按优先级取一个会让「写在另一处的那个声明」变成永远不生效的幽灵配置——这类问题比启动失败难查得多。
 * 抛 {@link io.github.routeforge.core.exception.ConflictingRouteDeclarationException}（RF_BE_010）。
 *
 * <p>类/包级的 {@code @ForgeTier} 不在此合并（那是「就近继承」而非「同一方法上的多通道」），
 * 由 {@link io.github.routeforge.spring.annotation.ForgeTiers} 在 {@code tier} 为空时接管。
 */
public record ForgeDeclaration(
        @Nullable String name,
        @Nullable String tier,
        List<String> aliases,
        List<String> middleware,
        List<String> optional,
        Map<String, String> defaults) {

    public ForgeDeclaration {
        aliases = List.copyOf(aliases);
        middleware = List.copyOf(middleware);
        optional = List.copyOf(optional);
        defaults = Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
    }

    /** 是否由 handler 上的注解显式给了名字（给了就不该再走命名策略）。 */
    public boolean declaresName() {
        return name != null;
    }

    /** 合并一个 handler 方法上的所有声明通道。 */
    public static ForgeDeclaration of(HandlerMethod handlerMethod) {
        Method method = handlerMethod.getMethod();
        String where = handlerMethod.getBeanType().getSimpleName() + "#" + method.getName();

        ForgeRoute route = AnnotatedElementUtils.findMergedAnnotation(method, ForgeRoute.class);
        Forge forge = method.getDeclaredAnnotation(Forge.class);
        ForgeTier onMethod = method.getDeclaredAnnotation(ForgeTier.class);

        // 原生 name：@ForgeRoute 的 name 本就别名到 @RequestMapping.name，与它同源，不构成第二个通道
        String nativeName = route == null
                ? emptyToNull(AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class) == null
                        ? null
                        : AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class).name())
                : null;

        String name = firstDeclared(where, "name",
                route == null ? null : emptyToNull(route.name()),
                forge == null ? null : emptyToNull(forge.name()),
                nativeName);

        String tier = firstDeclared(where, "tier",
                route == null ? null : emptyToNull(route.tier()),
                forge == null ? null : emptyToNull(forge.tier()),
                onMethod == null ? null : onMethod.value());

        List<String> aliases = mergedLists(where, "aliases",
                route == null ? List.of() : List.of(route.aliases()),
                forge == null ? List.of() : List.of(forge.aliases()));

        List<String> middleware = mergedLists(where, "middleware",
                route == null ? List.of() : List.of(route.middleware()),
                forge == null ? List.of() : List.of(forge.middleware()));

        List<String> optional = mergedLists(where, "optional",
                route == null ? List.of() : List.of(route.optional()),
                forge == null ? List.of() : List.of(forge.optional()));

        Map<String, String> defaults = mergedDefaults(where,
                route == null ? List.of() : List.of(route.defaults()),
                forge == null ? List.of() : List.of(forge.defaults()));

        return new ForgeDeclaration(name, tier, aliases, middleware, optional, defaults);
    }

    /**
     * 取第一个表态的值；两个以上通道都表态且值不同 → 冲突。
     *
     * <p>比较用集合语义（忽略顺序与重复），因为 {@code aliases = {"a","b"}} 与 {@code {"b","a"}}
     * 是同一份声明，不该被当成冲突。
     */
    @Nullable
    private static String firstDeclared(String where, String fact, @Nullable String... candidates) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isEmpty()) {
                distinct.add(candidate);
            }
        }
        if (distinct.size() > 1) {
            throw conflict(where, fact, distinct.toString());
        }
        return distinct.isEmpty() ? null : distinct.iterator().next();
    }

    private static List<String> mergedLists(String where, String fact, List<String> fromRoute, List<String> fromForge) {
        if (fromRoute.isEmpty()) {
            return fromForge;
        }
        if (fromForge.isEmpty()) {
            return fromRoute;
        }
        Set<String> a = new LinkedHashSet<>(fromRoute);
        Set<String> b = new LinkedHashSet<>(fromForge);
        if (!a.equals(b)) {
            throw conflict(where, fact, a + " vs " + b);
        }
        return List.copyOf(a);
    }

    private static Map<String, String> mergedDefaults(String where, List<String> fromRoute, List<String> fromForge) {
        Map<String, String> a = parseDefaults(fromRoute);
        Map<String, String> b = parseDefaults(fromForge);
        if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
            throw conflict(where, "defaults", a + " vs " + b);
        }
        return a.isEmpty() ? b : a;
    }

    /**
     * 解析 {@code page=1} 形态的默认值声明。
     *
     * <p>{@code =} 只按第一个切分（默认值本身可以含 {@code =}）；不带 {@code =} 的写法直接拒绝——
     * 静默忽略会让宿主以为默认值已生效。
     */
    public static Map<String, String> parseDefaults(List<String> declarations) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String entry : declarations) {
            int split = entry.indexOf('=');
            if (split <= 0) {
                throw new IllegalArgumentException("forge 默认值声明必须形如 page=1，收到 [" + entry + "]");
            }
            out.put(entry.substring(0, split).trim(), entry.substring(split + 1));
        }
        return out;
    }

    private static io.github.routeforge.core.exception.ConflictingRouteDeclarationException conflict(
            String where, String fact, String values) {
        return new io.github.routeforge.core.exception.ConflictingRouteDeclarationException(
                "Conflicting forge declarations for [" + fact + "] on " + where + ": " + values
                        + ". @ForgeRoute and @Forge must not disagree; keep one channel or make them identical.");
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
