package io.github.routeforge.core.filter;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 路由排除过滤器：判断一条路由是否应从 forge 的所有用户可见输出中排除。
 *
 * <p>两个维度，缺一都会造成真实的自伤：
 *
 * <ul>
 *   <li><b>按路由名</b>：{@code forge.routes.} / {@code forge.manager.} 加适配层追加的框架内部前缀。
 *       这些是包自身端点与框架内部路由，不属于用户业务路由。</li>
 *   <li><b>按 URI 段</b>：包自己的层级端点 {@code GET {endpoint_prefix}/{level}} 本身就是<b>未命名</b>路由
 *       （Spring 侧更是全部无名），一旦宿主管该层级配了 {@code endpoint_middleware}，它带上的中间件会被
 *       该层级的 {@code match.middleware} 命中。不按 URI 排除，包就会在 warnings 与 {@code RF_BE_009}
 *       违规清单里把自家端点报成宿主的配置错误——宿主一配端点中间件就必然 500。</li>
 * </ul>
 *
 * <p>不可变：{@link #withUriPrefixes(List)} 返回新实例并保留既有名字前缀，命令层与仓库层可安全叠加。
 */
public final class RouteNameFilter {

    /** 所有框架通用的 forge 自身端点路由名前缀。 */
    public static final List<String> FORGE_PREFIXES = List.of("forge.routes.", "forge.manager.");

    private final List<String> excludedNamePrefixes;
    private final List<String> excludedUriPrefixes;

    public RouteNameFilter() {
        this(FORGE_PREFIXES, List.of());
    }

    /**
     * @param excludedNamePrefixes 路由名排除前缀（默认仅 forge 自身；适配层追加框架内部前缀）
     * @param excludedUriPrefixes  URI 排除前缀，取规范化后的 {@code endpoint_prefix}
     */
    public RouteNameFilter(List<String> excludedNamePrefixes, List<String> excludedUriPrefixes) {
        this.excludedNamePrefixes = List.copyOf(new LinkedHashSet<>(excludedNamePrefixes));
        this.excludedUriPrefixes = List.copyOf(new LinkedHashSet<>(excludedUriPrefixes));
    }

    /** 在默认 forge 前缀之外追加框架内部路由名前缀。 */
    public static RouteNameFilter withExtraNamePrefixes(List<String> extraNamePrefixes) {
        Set<String> merged = new LinkedHashSet<>(FORGE_PREFIXES);
        merged.addAll(extraNamePrefixes);
        return new RouteNameFilter(List.copyOf(merged), List.of());
    }

    /** 路由名是否命中排除前缀（按字符串前缀，不按段——路由名的点分层级本身就是分隔）。 */
    public boolean isNameExcluded(String name) {
        if (name == null) {
            return false;
        }
        for (String prefix : excludedNamePrefixes) {
            if (!prefix.isEmpty() && name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * URI 是否命中排除前缀。<b>按段</b>匹配：{@code _forge/routes} 不会误伤 {@code _forge/routeship}。
     * 这是未命名路由的唯一排除口径。
     */
    public boolean isUriExcluded(String uri) {
        String normalized = stripLeadingSlash(uri);
        for (String prefix : excludedUriPrefixes) {
            String cut = stripTrailingSlash(stripLeadingSlash(prefix));
            if (!cut.isEmpty() && (normalized.equals(cut) || normalized.startsWith(cut + "/"))) {
                return true;
            }
        }
        return false;
    }

    /** URI 排除前缀快照（供诊断输出与测试断言）。 */
    public List<String> excludedUriPrefixes() {
        return excludedUriPrefixes;
    }

    /** 路由名排除前缀快照。 */
    public List<String> excludedNamePrefixes() {
        return excludedNamePrefixes;
    }

    /** 不可变追加 URI 排除前缀，保留既有名字前缀与已配置 URI 前缀。 */
    public RouteNameFilter withUriPrefixes(List<String> extraUriPrefixes) {
        Set<String> merged = new LinkedHashSet<>(excludedUriPrefixes);
        merged.addAll(extraUriPrefixes);
        return new RouteNameFilter(excludedNamePrefixes, List.copyOf(merged));
    }

    private static String stripLeadingSlash(String value) {
        int i = 0;
        while (i < value.length() && value.charAt(i) == '/') {
            i++;
        }
        return value.substring(i);
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
