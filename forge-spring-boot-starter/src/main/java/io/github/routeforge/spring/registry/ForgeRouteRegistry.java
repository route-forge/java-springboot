package io.github.routeforge.spring.registry;

import io.github.routeforge.core.alias.AliasResolver;
import io.github.routeforge.core.analyzer.RouteAnalyzer;
import io.github.routeforge.core.cache.RouteCache;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.contract.RouteSource;
import io.github.routeforge.core.filter.RouteNameFilter;
import io.github.routeforge.core.repository.RepositoryConfig;
import io.github.routeforge.core.repository.RouteRepository;
import io.github.routeforge.core.support.EndpointPrefix;
import io.github.routeforge.core.tier.RouteClassifier;
import io.github.routeforge.core.tier.TierResolver;
import io.github.routeforge.core.support.WarningSink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.lang.Nullable;

/**
 * 路由注册表：常驻装配 + 取数一律走缓存。
 *
 * <p>为什么不让结果常驻（Spring 是长驻 JVM，最自然的做法就是把算好的表放字段上）：
 * 那样缓存层就退化成第二份重复缓存，而 {@code cache-ttl}、{@code clear --level} 连带失效摘要、
 * Redis 多实例共享这些语义在 Java 侧就没有落点了。装配常驻、结果走缓存，两边语义才与 PHP 逐条可比。
 *
 * <p>因此每次取数都新建一个 {@link RouteRepository}（它很轻：几个 record 加一个 Map），
 * 真正的复用与失效只发生在 {@link RouteCache} 上。失效入口只有三个：{@code --forge:clear}、
 * 管理器保存配置后、以及管理器/端点的显式刷新。
 *
 * <p>包自身端点的排除在这里接线：过滤器同时拿到「路由名前缀」与「规范化后的 endpoint_prefix」两级，
 * 后者是必需的——层级端点本身是未命名路由，没有名字可判，若宿主又给该层级配了
 * {@code endpoint-middleware}，它会被该层级的 match 规则命中，包就会把自己报成宿主的配置错误。
 *
 * <p>URI 维还并入一批 <b>Boot 框架内部路由</b> 的默认前缀（{@link #FRAMEWORK_URI_EXCLUSIONS}）：
 * Spring 侧的框架路由全未命名，宿主没法靠「名字前缀」排除它们（Laravel 靠 {@code storage.*} 那条路在这里不存在）。
 * 宿主只要把某层级的 {@code match.prefix} 写宽（{@code /} 或 {@code /api}），{@code /error}、{@code /actuator/**}
 * 就会整批落进 {@code missing_name} 把严格模式刷满 500，且宿主无法靠命名自救。故 URI 维 =
 * endpoint ∪ 内置默认 ∪ 宿主 {@code forge.exclude-uri-prefixes}（后者只做加法，防止宿主一个值把 /error 丢了）。
 */
public final class ForgeRouteRegistry {

    /**
     * Boot 框架内部路由的 URI 默认排除（段级前缀，未引对应依赖时空转无害）：
     * {@code /error} = {@code BasicErrorController}；{@code /actuator} = actuator 端点基路径。
     *
     * <p>刻意<b>不</b>放 springdoc（{@code /v3/api-docs} 等）：第三方路径多变可配、变体众多，写进默认集是
     * 「假完备」，留给宿主经 {@code forge.exclude-uri-prefixes} 追加。Laravel 无对等物（那边按名字前缀排），
     * 属 Java 专属扩展（SPEC §3、§4.5）。
     */
    private static final List<String> FRAMEWORK_URI_EXCLUSIONS = List.of("/error", "/actuator");

    /**
     * 包自身「管理器」端点的 URI 基前缀（SPEC §5.3，固定 {@code /_forge/manager}，不随 {@code endpoint_prefix} 变）。
     *
     * <p>它不在 {@code endpoint_prefix}（默认 {@code /_forge/routes}）之下，故必须像包自身层级端点一样显式并入
     * URI 维排除——否则 {@code /_forge/manager/**}（控制器注册的未命名路由）在 {@code strict_mode=true} 下会被
     * 包自己报成宿主的 {@code missing_name}、把严格模式刷满 500（AGENTS 铁律 3）。管理器未启用时这条排除空转无害。
     * 常量单点，供 {@code ForgeManagerController} 的 {@code @RequestMapping} 复用，避免两处漂移。
     */
    public static final String MANAGER_URI_PREFIX = "/_forge/manager";

    private final RouteSource source;
    private volatile LevelsConfig levels;
    private final Map<String, Object> aliases;
    private final RepositoryConfig config;
    private final RouteCache cache;
    private final RouteNameFilter filter;
    @Nullable
    private final RouteClassifier classifier;
    private final WarningSink warnings;

    public ForgeRouteRegistry(RouteSource source, LevelsConfig levels, Map<String, Object> aliases,
            RepositoryConfig config, RouteCache cache, @Nullable RouteClassifier classifier, WarningSink warnings,
            List<String> hostUriExclusions) {
        this.source = source;
        this.levels = levels;
        this.aliases = aliases;
        this.config = config;
        this.cache = cache;
        this.classifier = classifier;
        this.warnings = warnings;
        this.filter = new RouteNameFilter().withUriPrefixes(uriExclusions(config, hostUriExclusions));
    }

    /**
     * URI 维排除集 = 规范化 endpoint 前缀 ∪ 内置框架默认 ∪ 宿主追加（各段规范化、跳空值）。
     * 全部走 {@link EndpointPrefix#normalize}，交给 {@link RouteNameFilter} 做段级匹配（{@code /error}
     * 不会误伤 {@code /errorlog}）。{@code withUriPrefixes} 内部按 {@code LinkedHashSet} 去重，重复无害。
     */
    private static List<String> uriExclusions(RepositoryConfig config, List<String> hostUriExclusions) {
        List<String> out = new ArrayList<>();
        String endpoint = config.endpointPrefix() == null
                ? RouteRepository.DEFAULT_ENDPOINT_PREFIX
                : config.endpointPrefix();
        out.add(EndpointPrefix.normalize(endpoint));
        // 包自身管理器端点（不在 endpoint_prefix 之下，须显式并入，见 MANAGER_URI_PREFIX javadoc）
        out.add(EndpointPrefix.normalize(MANAGER_URI_PREFIX));
        FRAMEWORK_URI_EXCLUSIONS.forEach(prefix -> out.add(EndpointPrefix.normalize(prefix)));
        if (hostUriExclusions != null) {
            hostUriExclusions.stream()
                    .filter(prefix -> prefix != null && !prefix.isBlank())
                    .forEach(prefix -> out.add(EndpointPrefix.normalize(prefix.trim())));
        }
        return out;
    }

    /** 摘要端点产物。 */
    public Map<String, Object> summary() {
        return repository().summary();
    }

    /** 层级端点产物。 */
    public Map<String, Object> routesForLevel(String level) {
        return repository().routesForLevel(level);
    }

    /**
     * 命令行与管理器共用的分析视图：走注册表已装的 {@link TierResolver} / {@link AliasResolver} /
     * {@link RouteNameFilter}，对 {@link RouteSource#routes()} 做一次扫描，产出结构化结果。
     *
     * <p>刻意<b>不</b>经过 {@link RouteRepository#infos()} 那条严格模式预扫描（它会直接抛 RF_BE_009）：
     * 命令行的语义是「有违规也照常出全表、末尾附红色清单、退 1」，违规清单从
     * {@link RouteAnalyzer.Analysis#violations()} 取，而不是靠抛异常中断。
     *
     * <p>{@code TierResolver.resolve()} 抛出的 RF_BE_002 / 004 / 006 与 {@code AliasResolver} 抛的
     * RF_BE_008 原样向上传播（配置歧义必须中断，命令层按 {@code [code] 消息} 打一行退 1）。
     * 缓存与此路无关：命令行是一次性诊断，每次都真扫（与 PHP 侧 {@code analyzeRoutes} 同）。
     */
    public RouteAnalyzer.Analysis analyze() {
        TierResolver resolver = new TierResolver(levels, classifier, config.strictMode(), warnings);
        AliasResolver aliasResolver = new AliasResolver(aliases, filter);
        return new RouteAnalyzer(resolver, aliasResolver, filter).analyze(source.routes());
    }

    /** 已配置层级名（声明顺序 = last-wins 优先级 = 摘要键序）。 */
    public List<String> levelNames() {
        return levels.names();
    }

    /**
     * 管理器数据源（SPEC §5.3）：全量路由 × 层级的结构化视图，转核心层
     * {@link RouteRepository#allRoutesWithTiers()}。与两端点、命令行共用同一 filter/resolver 装配，
     * 不在管理器侧重装第二套口径。此路经 {@link #repository()} 走缓存语义，但 {@code allRoutesWithTiers}
     * 本身在核心层直读 {@code infos()}（含严格模式预扫描），故与 {@code analyze()} 的「不抛全表」不同——
     * 它是「配置有严格违例时会抛 RF_BE_009」的取数面，与摘要/层级端点同规。
     */
    public Map<String, Object> allRoutesWithTiers() {
        return repository().allRoutesWithTiers();
    }

    /**
     * 热更层级定义（SPEC §5.3 决策 D1「热生效」）：把新的层级配置换成内存里的 {@link LevelsConfig} 快照。
     *
     * <p>只换装配持有的层级定义（{@code levels} 是 {@code volatile}，{@link #analyze()}/{@link #repository()}
     * 每次取数都重读该字段），<b>不</b>在此清缓存——失效入口须显式，由管理器在写回文件成功后调
     * {@link #clearAllCache()}，保证「文件写成功」与「内存换新」的先后次序可控、失败可回滚。
     *
     * @param normalizedLevels 已过 {@code YamlShape.levels} 归一（索引 Map→列表）的层级原始表
     */
    public void updateLevels(Map<String, Object> normalizedLevels) {
        this.levels = new LevelsConfig(normalizedLevels);
    }

    /**
     * 规范化后的端点前缀（前导 {@code /}、去尾部 {@code /}）：端点注册、摘要下发、d.ts 头注释三处同源，
     * 命令行不自建第二份规范化规则。
     */
    public String normalizedEndpointPrefix() {
        return RouteRepository.normalizeEndpointPrefix(
                config.endpointPrefix() == null ? RouteRepository.DEFAULT_ENDPOINT_PREFIX : config.endpointPrefix());
    }

    /** 清空全部 forge 缓存（含摘要）。{@code RouteCache.clear} 不随 debug 旁路——否则关回 debug 时旧缓存复活。 */
    public void clearAllCache() {
        cache.clear();
    }

    /**
     * 失效某层级缓存，并连带失效摘要（不变量封装在 {@link RouteCache#forgetLevel}：摘要的
     * {@code route_count} 依赖层级数据）。命令行禁止直接 {@code forget}，一律走此口。
     */
    public void clearLevelCache(String level) {
        cache.forgetLevel(level);
    }

    private RouteRepository repository() {
        TierResolver resolver = new TierResolver(levels, classifier, config.strictMode(), warnings);
        return new RouteRepository(source, resolver, cache, levels, aliases, config, filter);
    }
}
