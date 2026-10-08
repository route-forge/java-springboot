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
import io.github.routeforge.spring.manager.ForgeLevelsStore;
import io.github.routeforge.spring.manager.ForgeManagerController;
import io.github.routeforge.spring.manager.ManagerAccessGuard;
import io.github.routeforge.spring.naming.RouteNamingStrategy;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import io.github.routeforge.spring.scan.ForgeAnnotationSanityChecker;
import io.github.routeforge.spring.scan.HandlerMethodRouteSource;
import io.github.routeforge.spring.summary.ForgeSummaryEmbed;
import io.github.routeforge.spring.summary.ForgeSummaryDialect;
import io.github.routeforge.spring.support.ForgeSecurityAdvisory;
import io.github.routeforge.spring.support.Slf4jWarningSink;
import io.github.routeforge.spring.web.ForgeRoutesController;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
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

    /**
     * 「classpath 无 Spring Security」启动提醒（SPEC §4.4）：检测到缺 {@code SecurityFilterChain} 能力时打一条
     * WARN，明示 {@code /_forge/**}（含管理器）当前无鉴权保护。只探测一次、绝不改变任何行为（本包不代配 Security）。
     */
    @Bean
    @ConditionalOnMissingBean
    ForgeSecurityAdvisory forgeSecurityAdvisory() {
        ForgeSecurityAdvisory advisory = ForgeSecurityAdvisory.detect(getClass().getClassLoader());
        advisory.warningMessage().ifPresent(LOGGER::warn);
        return advisory;
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
     * 内嵌摘要的框架无关渲染 API（SPEC §5.4）：任何模板/Servlet 都能注入它拿 {@code <script>}，
     * 无需引模板引擎。摘要只走 {@link ForgeRouteRegistry#summary()}，与端点共用同一缓存/旁路/排除语义。
     */
    @Bean
    @ConditionalOnMissingBean
    ForgeSummaryEmbed forgeSummaryEmbed(ForgeRouteRegistry registry) {
        return new ForgeSummaryEmbed(registry);
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
     * 「注解写在不会被消费位置」的一次性启动扫描（SPEC §4.1 / §4.5 的误用面，参照 Laravel 的
     * {@code ForgeRouteRegistrar::__destruct}「记日志不抛」）。
     *
     * <p>只在 {@code debug=true} 或 {@code strict=true} 时启用——排查配置问题的两个场景；生产两者皆非则
     * bean 存在但 {@code afterSingletonsInstantiated} 直接返回，零反射。提示恒为 warning（{@link WarningSink}
     * 无条件接，要静音由宿主覆盖该 bean），绝不抛。
     */
    @Bean
    @ConditionalOnMissingBean
    ForgeAnnotationSanityChecker forgeAnnotationSanityChecker(ObjectProvider<RequestMappingHandlerMapping> mappings,
            ListableBeanFactory beanFactory, WarningSink warnings, ForgeProperties properties,
            Environment environment) {
        boolean enabled = isDebug(environment) || properties.strictMode();
        return new ForgeAnnotationSanityChecker(mappings.orderedStream().toList(), beanFactory, warnings, enabled);
    }

    /**
     * 开发环境判据 = Spring 的 {@code debug} 属性（与 Laravel 的 {@code APP_DEBUG} 同构，SPEC §4.7）。
     *
     * <p>只按布尔读：Boot 本身就是把它当布尔开关用，这里不发明第三种写法。
     */
    private static boolean isDebug(Environment environment) {
        return Boolean.TRUE.equals(environment.getProperty("debug", Boolean.class, false));
    }

    /**
     * 管理器三件套的装配（SPEC §5.3）：只在 {@link OnManagerEnabledAndDebug} 命中（{@code debug=true} 且
     * {@code forge.manager.enabled=true}）时注册。故正常生产或没显式开管理器的宿主，一个管理器 bean 都不会建、
     * 也不新增 {@code /_forge/manager} 路由——零副作用。
     *
     * <p>{@link ForgeLevelsStore} 用 {@code @ConditionalOnMissingBean} 暴露替换口：测试注入指向临时目录的实例，
     * 无需为「固定路径」（决策 D2）新增配置键。IP 白名单（第三道）在控制器内经 {@link ManagerAccessGuard} 施加。
     */
    @Configuration(proxyBeanMethods = false)
    @Conditional(OnManagerEnabledAndDebug.class)
    static class ManagerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ForgeLevelsStore forgeLevelsStore() {
            return ForgeLevelsStore.defaultLocation();
        }

        @Bean
        @ConditionalOnMissingBean
        ManagerAccessGuard forgeManagerAccessGuard(ForgeProperties properties) {
            return new ManagerAccessGuard(properties.manager().allowedIps());
        }

        @Bean
        @ConditionalOnMissingBean
        ForgeManagerController forgeManagerController(ForgeRouteRegistry registry, ForgeLevelsStore store,
                ManagerAccessGuard guard) {
            return new ForgeManagerController(registry, store, guard);
        }
    }

    /**
     * 管理器注册门禁 = {@code debug=true} 且 {@code forge.manager.enabled=true}（SPEC §4.7 的前两道；
     * IP 白名单是第三道，落在控制器里）。任一不满足则整个 {@link ManagerConfiguration} 不生效。
     */
    static final class OnManagerEnabledAndDebug implements Condition {

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            boolean debug = Boolean.TRUE.equals(env.getProperty("debug", Boolean.class, false));
            boolean enabled = Boolean.TRUE.equals(env.getProperty("forge.manager.enabled", Boolean.class, false));
            return debug && enabled;
        }
    }

    /**
     * 内嵌摘要的<b>可选</b> Thymeleaf 方言（SPEC §5.4）。只在 classpath 有 Thymeleaf 时装配（{@code @ConditionalOnClass}
     * 用字符串名，避免没有 Thymeleaf 的宿主去加载引用了 thymeleaf 类型的配置类）；此时注册的 {@code IDialect} bean
     * 会被 Boot 的 Thymeleaf 自动配置收进 TemplateEngine。纯 SPA/无模板宿主此块整体不生效，{@link ForgeSummaryEmbed}
     * 仍可用（Bean 引用 {@code ${@forgeSummaryEmbed.script()}} 或直接注入）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.thymeleaf.TemplateEngine")
    static class ThymeleafSummaryConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ForgeSummaryDialect forgeSummaryDialect(ForgeSummaryEmbed embed) {
            return new ForgeSummaryDialect(embed);
        }
    }
}
