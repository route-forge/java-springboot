package io.github.routeforge.spring.autoconfigure;

import io.github.routeforge.core.cache.RouteCache;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.contract.CacheStore;
import io.github.routeforge.core.contract.RouteSource;
import io.github.routeforge.core.exception.CacheDriverException;
import io.github.routeforge.core.repository.RepositoryConfig;
import io.github.routeforge.core.support.WarningSink;
import io.github.routeforge.core.tier.RouteClassifier;
import io.github.routeforge.spring.cache.InMemoryCacheStore;
import io.github.routeforge.spring.cli.ForgeClearCommand;
import io.github.routeforge.spring.cli.ForgeCliRunner;
import io.github.routeforge.spring.cli.ForgeListCommand;
import io.github.routeforge.spring.cli.ForgeTypesCommand;
import io.github.routeforge.spring.config.ForgeProperties;
import io.github.routeforge.spring.naming.RouteNamingStrategy;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import io.github.routeforge.spring.scan.HandlerMethodRouteSource;
import io.github.routeforge.spring.support.Slf4jWarningSink;
import io.github.routeforge.spring.web.ForgeRoutesController;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Route Forge 的自动装配：把 {@code forge.*} 配置、Spring 的映射表与核心层的解析/仓库接成两个端点。
 *
 * <p>装配生命周期按 SPEC §4.6：<b>装配常驻、结果一律走缓存</b>。注册表单例只做装配，
 * 每次取数新建一个仓库实例（很轻），因此 {@code cache-ttl}、{@code clear --level} 连带失效摘要、
 * Redis 多实例共享这些语义都仍有落点。
 *
 * <p>刻意不做的事：
 * <ul>
 *   <li>不注册、不改写、不排序宿主的 {@code SecurityFilterChain}（SPEC §4.4 铁律 3）；</li>
 *   <li>不提供全局 {@code @RestControllerAdvice}（会拦截宿主控制器自己的异常）；</li>
 *   <li>不要求任何可选依赖：Security / Redis / 模板引擎都不进传递依赖。</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(ForgeProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ForgeAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ForgeAutoConfiguration.class);

    /**
     * 内存缓存驱动（默认）。
     *
     * <p>{@code cache-driver} 只认 {@code memory}；配成别的值时启动即失败，而不是悄悄退回内存——
     * 静默降级会让宿主以为已经吃到 Redis 的多实例共享。
     */
    @Bean
    @ConditionalOnMissingBean(CacheStore.class)
    CacheStore forgeCacheStore(ForgeProperties properties) {
        if (!"memory".equals(properties.cacheDriver())) {
            throw new CacheDriverException("Unsupported forge.cache-driver [" + properties.cacheDriver()
                    + "]; supported drivers: memory.");
        }
        return new InMemoryCacheStore();
    }

    /**
     * 统一缓存：TTL 与 debug 旁路的判据都来自这里。
     *
     * <p>{@code debug=true} 旁路读写（改路由/改配置即时生效），但 {@code clear} 不旁路——
     * 否则「切了 debug 又关回去」时旧缓存会复活。
     */
    @Bean
    @ConditionalOnMissingBean
    RouteCache forgeRouteCache(CacheStore store, ForgeProperties properties, Environment environment) {
        boolean debug = isDebug(environment);
        if (debug) {
            LOGGER.warn("Route Forge development mode (debug=true): metadata cache is bypassed, "
                    + "every endpoint request re-scans the route table. Do not leave it enabled in production.");
        }
        return new RouteCache(store, debug, properties.cacheTtl());
    }

    /** 非致命告警出口：文案由核心层单点生成，这里只搬运不改写。 */
    @Bean
    @ConditionalOnMissingBean(WarningSink.class)
    WarningSink forgeWarningSink() {
        return new Slf4jWarningSink();
    }

    /** 路由来源：宿主可能有多份 {@code RequestMappingHandlerMapping}，一并纳入。 */
    @Bean
    @ConditionalOnMissingBean(RouteSource.class)
    RouteSource forgeRouteSource(ObjectProvider<RequestMappingHandlerMapping> mappings,
            ObjectProvider<RouteNamingStrategy> namingStrategy, WarningSink warnings) {
        List<RequestMappingHandlerMapping> found = mappings.orderedStream().toList();
        if (found.isEmpty()) {
            LOGGER.warn("No RequestMappingHandlerMapping bean found; forge metadata endpoints will be empty.");
        }
        return new HandlerMethodRouteSource(found, namingStrategy.getIfAvailable(), warnings);
    }

    /** 常驻装配的注册表。 */
    @Bean
    @ConditionalOnMissingBean
    ForgeRouteRegistry forgeRouteRegistry(RouteSource source, RouteCache cache, ForgeProperties properties,
            Environment environment, ObjectProvider<RouteClassifier> classifier, WarningSink warnings) {
        return new ForgeRouteRegistry(
                source,
                // normalizedLevels：把 Boot 在 Object 位置绑出的索引 Map（{0=/admin}）还原成列表
                new LevelsConfig(properties.normalizedLevels()),
                properties.aliases(),
                new RepositoryConfig(
                        properties.schemeVersion(),
                        properties.strictMode(),
                        properties.endpointPrefix(),
                        properties.urlPrefix(),
                        properties.cacheTtl()),
                cache,
                classifier.getIfAvailable(),
                warnings,
                properties.excludeUriPrefixes());
    }

    @Bean
    @ConditionalOnMissingBean
    ForgeRoutesController forgeRoutesController(ForgeRouteRegistry registry, Environment environment) {
        return new ForgeRoutesController(registry, isDebug(environment));
    }

    /**
     * 命令行三件套（SPEC §5.1）。命令对象只依赖注册表——取数一律 {@code registry.analyze()}，
     * 与两个 HTTP 端点共用同一套装配，杜绝「命令自己再扫一遍」的第二份口径。
     *
     * <p>{@link ForgeTypesCommand} 的时间戳走 {@code TypeGenerator::currentTimestamp}（宿主实跑取当下时刻）；
     * 命令逻辑本身是可脱离 Spring 直接单测的纯对象，这里只是把它们接成 bean。
     */
    @Bean
    @ConditionalOnMissingBean
    ForgeListCommand forgeListCommand(ForgeRouteRegistry registry) {
        return new ForgeListCommand(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    ForgeTypesCommand forgeTypesCommand(ForgeRouteRegistry registry) {
        return new ForgeTypesCommand(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    ForgeClearCommand forgeClearCommand(ForgeRouteRegistry registry) {
        return new ForgeClearCommand(registry);
    }

    /**
     * CLI 入口：{@code --forge:*} flag 缺席即完全 no-op，因此无条件注册也安全
     * （正常启动与不带这些参数的 {@code @SpringBootTest} 都不受影响）。命中命令后跑完 {@code System.exit}
     * 落地退出码——这是宿主显式把它当运维命令用的语义（SPEC §5.1，宿主启服务时勿误带这些 flag）。
     */
    @Bean
    @ConditionalOnMissingBean
    ForgeCliRunner forgeCliRunner(ForgeListCommand list, ForgeTypesCommand types, ForgeClearCommand clear) {
        return new ForgeCliRunner(list, types, clear);
    }

    /**
     * 开发环境判据 = Spring 的 {@code debug} 属性（与 Laravel 的 {@code APP_DEBUG} 同构，SPEC §4.7）。
     *
     * <p>只按布尔读：Boot 本身就是把它当布尔开关用，这里不发明第三种写法。
     */
    private static boolean isDebug(Environment environment) {
        return Boolean.TRUE.equals(environment.getProperty("debug", Boolean.class, false));
    }
}
