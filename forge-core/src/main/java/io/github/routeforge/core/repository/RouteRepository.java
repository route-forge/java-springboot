package io.github.routeforge.core.repository;

import io.github.routeforge.core.alias.AliasResolver;
import io.github.routeforge.core.cache.RouteCache;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.contract.RouteSource;
import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.exception.RouteStrictViolationException;
import io.github.routeforge.core.exception.UnknownLevelException;
import io.github.routeforge.core.filter.RouteNameFilter;
import io.github.routeforge.core.support.EndpointPrefix;
import io.github.routeforge.core.support.HttpMethods;
import io.github.routeforge.core.support.StrictViolationScanner;
import io.github.routeforge.core.tier.TierResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路由仓库：扫描路由集合，按层级分组产出端点元信息、摘要与管理器数据。
 *
 * <p>框架无关：输入是 {@link RouteSource}（适配层已把框架路由归一化成 {@link RouteInfo}），
 * 层级解析、缓存、排除过滤、别名注入、严格模式预扫描全部在本类完成，不接触任何框架类。
 *
 * <p><b>产物为什么是有序 Map 而不是带注解的 DTO</b>：端点契约的键名混用 snake_case
 * （{@code route_count} / {@code parameter_defaults}）与 camelCase（{@code schemeVersion}），
 * 与 Java 标识符习惯冲突。用 PHP 侧同样的「有序 map 即产物」模型，键名与顺序只写一处，
 * 跨语言 fixture 能整份比对，适配层也不必再维护一套 wire DTO 做字段名映射。
 *
 * <p>缓存：层级与摘要产物经 {@link RouteCache} 复用（命中即返回，不重扫）；
 * 全量与 unassigned 明细不缓存（管理器页面与命令行要即时可见）。
 */
public final class RouteRepository {

    /** 特殊层级名：未命中任何层级的命名路由归属此层级，经 {@code GET {endpoint_prefix}/unassigned} 获取。 */
    public static final String UNASSIGNED_LEVEL = "unassigned";

    /** 摘要端点响应格式版本（{@code schemeVersion}）。引入不兼容格式变更时递增。 */
    public static final int SCHEME_VERSION = 1;

    /** 未配置 {@code endpoint_prefix} 时的契约默认值。 */
    public static final String DEFAULT_ENDPOINT_PREFIX = "/_forge/routes";

    private final RouteSource source;
    private final TierResolver tierResolver;
    private final RouteCache cache;
    private final LevelsConfig levels;
    private final RepositoryConfig config;
    private final RouteNameFilter filter;
    private final AliasResolver aliasResolver;

    /** 严格模式扫描器按实例记忆：一次请求内多个产物方法重复取数只算一遍。 */
    private StrictViolationScanner violationScanner;

    public RouteRepository(RouteSource source, TierResolver tierResolver, RouteCache cache, LevelsConfig levels,
            Map<String, Object> aliasesConfig, RepositoryConfig config, RouteNameFilter filter) {
        this.source = source;
        this.tierResolver = tierResolver;
        this.cache = cache;
        this.levels = levels;
        this.config = config;
        this.filter = filter;
        this.aliasResolver = new AliasResolver(aliasesConfig, filter);
    }

    /**
     * 某层级下所有命名路由的元信息（带缓存）。
     *
     * <p>{@code level} 支持特殊值 {@code unassigned}：返回所有未命中层级的命名路由，结构完全一致。
     * 包自身端点与框架内部路由一律排除；别名条目注入到目标路由所在层级，元信息与目标纯复制一致。
     *
     * @throws UnknownLevelException         层级名不在配置中（RF_BE_002）
     * @throws RouteStrictViolationException 严格模式下存在违规（RF_BE_009，取数前预扫描）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> routesForLevel(String level) {
        boolean unassigned = UNASSIGNED_LEVEL.equals(level);
        if (!unassigned && !levels.containsLevel(level)) {
            throw new UnknownLevelException("Unknown level: " + level);
        }

        Map<String, Object> cached = cache.get(level, Map.class);
        if (cached != null) {
            return cached;
        }

        List<RouteInfo> infos = infos();
        Map<String, Object> routes = unassigned ? collectUnassigned(infos) : collectLevel(infos, level);

        // 别名注入（SPEC §3.1.7）：别名出现在目标路由所在层级、无附加标记字段；
        // 解析结果随扫描进缓存，悬空别名在此 fail-fast（RF_BE_008）
        for (Map.Entry<String, String> alias : aliasResolver.resolve(infos).aliases().entrySet()) {
            if (routes.containsKey(alias.getValue())) {
                routes.put(alias.getKey(), routes.get(alias.getValue()));
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("level", level);
        payload.put("routes", routes);
        cache.set(level, payload);
        return payload;
    }

    /**
     * 摘要端点产物（SPEC §3.1.6）：格式版本 + 所有层级概览（含 unassigned）+ 全局配置。
     *
     * <p>unassigned 的路由明细不在摘要内联，前端按 {@code route} 字段另发一次层级请求。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> summary() {
        Map<String, Object> cached = cache.get(RouteCache.SUMMARY_LEVEL, Map.class);
        if (cached != null) {
            return cached;
        }

        LevelCounts counts = countRoutesPerLevel();
        String endpointPrefix = normalizedEndpointPrefix();
        Map<String, Object> levelSummaries = new LinkedHashMap<>();

        for (String level : levels.names()) {
            levelSummaries.put(level, levelSummary(level, textOption(level, "description", ""),
                    textOption(level, "load", "lazy"), counts.of(level), endpointPrefix));
        }
        // unassigned 特殊层级：与已定义层级结构完全一致
        levelSummaries.put(UNASSIGNED_LEVEL,
                levelSummary(UNASSIGNED_LEVEL, "未命中任何层级的路由", "lazy", counts.unassigned(), endpointPrefix));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemeVersion", config.schemeVersionOrDefault());
        payload.put("levels", levelSummaries);
        payload.put("config", runtimeConfigView(endpointPrefix));
        cache.set(RouteCache.SUMMARY_LEVEL, payload);
        return payload;
    }

    /**
     * 全量命名路由 + 层级归属（管理器页面与命令行共用，不缓存）。
     *
     * <p>条目 {@code methods} 已去 HEAD（展示口径）；真实路由条目<b>不带</b> {@code alias_of} 键，
     * 别名条目带且值为所指真实名。目标以同名多次注册命中多个层级时，别名逐层级各铺一行
     * （与层级端点的别名注入一致）；计数只记一次，记在末次注册层级（与摘要 {@code route_count} 同口径）。
     */
    public Map<String, Object> allRoutesWithTiers() {
        List<Map<String, Object>> routes = new ArrayList<>();
        Map<String, Integer> tiers = new LinkedHashMap<>();

        List<RouteInfo> infos = infos();
        for (RouteInfo info : infos) {
            String name = info.name();
            if (name == null || name.isEmpty() || filter.isNameExcluded(name)) {
                continue;
            }
            String tier = orUnassigned(tierResolver.resolve(info));
            tiers.merge(tier, 1, Integer::sum);
            routes.add(managerRow(name, info, tier));
        }

        Map<String, List<Map<String, Object>>> rowsByName = new LinkedHashMap<>();
        for (Map<String, Object> row : routes) {
            rowsByName.computeIfAbsent((String) row.get("name"), key -> new ArrayList<>()).add(row);
        }

        for (Map.Entry<String, String> alias : aliasResolver.resolve(infos).aliases().entrySet()) {
            List<Map<String, Object>> targetRows = rowsByName.get(alias.getValue());
            if (targetRows == null || targetRows.isEmpty()) {
                // 目标不可能是未命名或被排除路由（解析器已保证目标为真实命名路由）；防御性跳过
                continue;
            }
            Map<String, Object> lastRow = targetRows.get(targetRows.size() - 1);
            tiers.merge((String) lastRow.get("tier"), 1, Integer::sum);

            List<String> emittedTiers = new ArrayList<>();
            for (Map<String, Object> targetRow : targetRows) {
                String tier = (String) targetRow.get("tier");
                if (emittedTiers.contains(tier)) {
                    continue;
                }
                emittedTiers.add(tier);
                routes.add(aliasRowOf(alias.getKey(), targetRow, tier));
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("routes", routes);
        payload.put("tiers", tiers);
        return payload;
    }

    /** unassigned 特殊层级的路由元信息（不缓存；与层级端点 routes 结构一致）。 */
    public Map<String, Object> unassignedRoutes() {
        return collectUnassigned(infos());
    }

    /** 端点前缀规范化：前导 {@code /}、去尾部 {@code /}。端点注册、摘要下发、d.ts 头注释三处同源。 */
    public static String normalizeEndpointPrefix(String prefix) {
        return EndpointPrefix.normalize(prefix);
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 取全量归一化路由，并在严格模式下跑违规预扫描。
     *
     * <p>所有读路径都经此处，因此「有层级归属却无路由名」与「命名路由未归级」两类问题在这里
     * 一次聚合报全（RF_BE_009），而不是修一条刷一条——逐条 fail-fast 时未命名路由根本走不到
     * {@code resolve()}，各循环都在它之前跳过，那两类错误码原本是死代码。
     */
    private List<RouteInfo> infos() {
        List<RouteInfo> infos = source.routes();

        if (tierResolver.isStrict()) {
            if (violationScanner == null) {
                violationScanner = new StrictViolationScanner(tierResolver, filter);
            }
            var violations = violationScanner.scan(infos);
            if (violations.count() > 0) {
                throw new RouteStrictViolationException(violations);
            }
        }
        return infos;
    }

    private Map<String, Object> collectLevel(List<RouteInfo> infos, String level) {
        Map<String, Object> routes = new LinkedHashMap<>();
        for (RouteInfo info : infos) {
            String name = info.name();
            if (name == null || filter.isNameExcluded(name)) {
                continue;
            }
            if (!level.equals(tierResolver.resolve(info))) {
                continue;
            }
            routes.put(name, metaOf(info));
        }
        return routes;
    }

    private Map<String, Object> collectUnassigned(List<RouteInfo> infos) {
        Map<String, Object> routes = new LinkedHashMap<>();
        for (RouteInfo info : infos) {
            String name = info.name();
            if (name == null || filter.isNameExcluded(name)) {
                continue;
            }
            if (tierResolver.resolve(info) == null) {
                routes.put(name, metaOf(info));
            }
        }
        return routes;
    }

    /** 单条路由的元信息：键名与顺序即端点契约。 */
    private static Map<String, Object> metaOf(RouteInfo info) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("uri", info.uri());
        meta.put("methods", info.methods());
        meta.put("parameters", info.parameters());
        // 空默认值必须是 {}（「按名字索引的对象」契约），不能是 []
        meta.put("parameter_defaults", info.parameterDefaults());
        return meta;
    }

    private LevelCounts countRoutesPerLevel() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, String> levelByName = new LinkedHashMap<>();
        int unassigned = 0;

        List<RouteInfo> infos = infos();
        for (RouteInfo info : infos) {
            String name = info.name();
            if (name == null || filter.isNameExcluded(name)) {
                continue;
            }
            String resolved = tierResolver.resolve(info);
            if (resolved == null) {
                unassigned++;
                levelByName.put(name, UNASSIGNED_LEVEL);
            } else {
                counts.merge(resolved, 1, Integer::sum);
                levelByName.put(name, resolved);
            }
        }

        // 别名计入目标所在层级；目标落在 unassigned 时并入该特殊层级计数
        for (String target : aliasResolver.resolve(infos).aliases().values()) {
            String targetLevel = levelByName.get(target);
            if (UNASSIGNED_LEVEL.equals(targetLevel)) {
                unassigned++;
            } else if (targetLevel != null) {
                counts.merge(targetLevel, 1, Integer::sum);
            }
        }
        return new LevelCounts(counts, unassigned);
    }

    private record LevelCounts(Map<String, Integer> counts, int unassigned) {

        int of(String level) {
            return counts.getOrDefault(level, 0);
        }
    }

    private String textOption(String level, String key, String fallback) {
        Object value = levels.optionOf(level, key);
        return value instanceof String text && !text.isEmpty() ? text : fallback;
    }

    private static Map<String, Object> levelSummary(String level, String description, String load, int routeCount,
            String endpointPrefix) {
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("uri", endpointPrefix + "/" + level);
        route.put("methods", List.of("GET", "HEAD"));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("description", description);
        summary.put("load", load);
        summary.put("route_count", routeCount);
        summary.put("route", route);
        return summary;
    }

    private Map<String, Object> runtimeConfigView(String endpointPrefix) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("strict_mode", config.strictMode());
        view.put("endpoint_prefix", endpointPrefix);
        view.put("url_prefix", config.urlPrefix());
        view.put("cache_ttl", config.cacheTtl());
        return view;
    }

    private static Map<String, Object> managerRow(String name, RouteInfo info, String tier) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("uri", info.uri());
        row.put("methods", HttpMethods.withoutHead(info.methods()));
        row.put("parameters", info.parameters());
        // 管理器条目刻意复刻 PHP 的 array→JSON 语义：空 map 序列化成 [] 而非 {}（该产物只给管理器页面消费，
        // 不是前端契约；前端契约的 parameter_defaults 恒为对象，见 metaOf）。留此差异是为了端到端比对时字节一致。
        row.put("parameter_defaults", info.parameterDefaults().isEmpty() ? List.of() : info.parameterDefaults());
        row.put("middleware", info.middleware());
        row.put("tier", tier);
        return row;
    }

    /** 别名行整份复制目标行的元信息，只改 name 并加 alias_of——元信息层与真实路由不可区分。 */
    private static Map<String, Object> aliasRowOf(String alias, Map<String, Object> targetRow, String tier) {
        Map<String, Object> row = new LinkedHashMap<>(targetRow);
        row.put("name", alias);
        row.put("tier", tier);
        row.put("alias_of", targetRow.get("name"));
        return row;
    }

    private String normalizedEndpointPrefix() {
        return normalizeEndpointPrefix(config.endpointPrefix() == null ? DEFAULT_ENDPOINT_PREFIX
                : config.endpointPrefix());
    }

    private static String orUnassigned(String level) {
        return level == null ? UNASSIGNED_LEVEL : level;
    }
}
