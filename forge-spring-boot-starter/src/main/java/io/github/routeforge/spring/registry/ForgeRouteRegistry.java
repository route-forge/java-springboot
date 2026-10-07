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
 */
public final class ForgeRouteRegistry {

    private final RouteSource source;
    private final LevelsConfig levels;
    private final Map<String, Object> aliases;
    private final RepositoryConfig config;
    private final RouteCache cache;
    private final RouteNameFilter filter;
    @Nullable
    private final RouteClassifier classifier;
    private final WarningSink warnings;

    public ForgeRouteRegistry(RouteSource source, LevelsConfig levels, Map<String, Object> aliases,
            RepositoryConfig config, RouteCache cache, @Nullable RouteClassifier classifier, WarningSink warnings) {
        this.source = source;
        this.levels = levels;
        this.aliases = aliases;
        this.config = config;
        this.cache = cache;
        this.classifier = classifier;
        this.warnings = warnings;
        this.filter = new RouteNameFilter()
                .withUriPrefixes(List.of(EndpointPrefix.normalize(config.endpointPrefix())));
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
