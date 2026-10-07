package io.github.routeforge.spring.registry;

import io.github.routeforge.core.contract.RouteSource;
import io.github.routeforge.core.filter.RouteNameFilter;
import io.github.routeforge.core.repository.RepositoryConfig;
import io.github.routeforge.core.repository.RouteRepository;
import io.github.routeforge.core.cache.RouteCache;
import io.github.routeforge.core.config.LevelsConfig;
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

    private RouteRepository repository() {
        TierResolver resolver = new TierResolver(levels, classifier, config.strictMode(), warnings);
        return new RouteRepository(source, resolver, cache, levels, aliases, config, filter);
    }
}
