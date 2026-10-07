package io.github.routeforge.spring.registry;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.routeforge.core.analyzer.RouteAnalyzer;
import io.github.routeforge.core.cache.RouteCache;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.contract.RouteSource;
import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.repository.RepositoryConfig;
import io.github.routeforge.core.support.WarningSink;
import io.github.routeforge.spring.cache.InMemoryCacheStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * P3-1：框架内部路由的 URI 维默认排除（{@code /error}、{@code /actuator}）+ 宿主追加键的语义。
 *
 * <p>验证走 {@link ForgeRouteRegistry#analyze()}——命令行与严格模式扫描共用同一 {@code filter}，
 * 因此这里覆盖到的正是宿主接 {@code forge.exclude-uri-prefixes} 时生效的那条排除通路。用合成的
 * {@code /error}（未命名 + 显式 tier）替代真实的 {@code BasicErrorController} 路由：排除按 URI 字符串段级
 * 匹配，与来源是框架还是合成无关，故一条 {@link RouteInfo} 足以钉死口径（不为此再拉一组 Spring 上下文）。
 */
class ForgeUriExclusionTest {

    // /error（未命名却带 tier=app：不排除就会在 strict 下算 missing_name）；
    // /actuator/health、/v3/api-docs、/errorlog 均未命名无 tier；/home 是命名业务路由
    private static List<RouteInfo> routes() {
        return List.of(
                unnamed("/error", "app"),
                unnamed("/actuator/health", null),
                unnamed("/v3/api-docs", null),
                unnamed("/errorlog", null),
                named("app.home", "/home", "app"));
    }

    private static ForgeRouteRegistry registry(boolean strict, List<String> hostUriExclusions) {
        RouteSource source = ForgeUriExclusionTest::routes;
        Map<String, Object> levels = new LinkedHashMap<>();
        levels.put("app", new LinkedHashMap<>(Map.of("description", "应用")));
        RepositoryConfig config = new RepositoryConfig(1, strict, "/_forge/routes", null, 3600);
        RouteCache cache = new RouteCache(new InMemoryCacheStore(), false, 3600);
        return new ForgeRouteRegistry(source, new LevelsConfig(levels), Map.of(), config, cache, null,
                WarningSink.NOOP, hostUriExclusions);
    }

    private static List<String> unnamedUris(ForgeRouteRegistry registry) {
        return registry.analyze().unnamed().stream().map(RouteAnalyzer.Unnamed::uri).toList();
    }

    @Nested
    @DisplayName("默认集 /error + /actuator")
    class Defaults {

        @Test
        @DisplayName("未命名框架路由被排除，第三方与段边界外的路由仍在")
        void frameworkRoutesExcluded() {
            List<String> uris = unnamedUris(registry(false, List.of()));

            assertThat(uris)
                    .doesNotContain("/error") // 内置默认
                    .doesNotContain("/actuator/health") // 内置默认，段级匹配子路径
                    .contains("/v3/api-docs") // 不在默认集：留给宿主追加
                    .contains("/errorlog"); // 段边界：/error 前缀不得误伤 /errorlog
        }

        @Test
        @DisplayName("strict 下 /error 不计入违例（否则宽 match.prefix 会把 CI 永久打断、宿主无法自救）")
        void errorNotCountedAsViolation() {
            RouteAnalyzer.Analysis analysis = registry(true, List.of()).analyze();
            assertThat(analysis.violations().count()).isZero();
        }
    }

    @Nested
    @DisplayName("宿主追加只做加法")
    class HostAppend {

        @Test
        @DisplayName("forge.exclude-uri-prefixes 追加 /v3/api-docs 后一并排除")
        void appendsToDefaults() {
            List<String> uris = unnamedUris(registry(false, List.of("/v3/api-docs")));

            assertThat(uris)
                    .doesNotContain("/v3/api-docs")
                    .doesNotContain("/error") // 追加不覆盖：内置默认仍在
                    .contains("/errorlog");
        }

        @Test
        @DisplayName("宿主写无前导斜杠 / 带空值也吃：'api-docs' 归一为 /api-docs，null 元素被跳")
        void tolerantOfHostShape() {
            List<String> uris = unnamedUris(registry(false, java.util.Arrays.asList("v3/api-docs", null, "  ")));
            assertThat(uris).doesNotContain("/v3/api-docs");
        }
    }

    // -------------------------------------------------------------- 工具

    private static RouteInfo named(String name, String uri, String tier) {
        return new RouteInfo(name, uri, List.of("GET", "HEAD"), List.of(), Map.of(), List.of(), tier, List.of(), null);
    }

    private static RouteInfo unnamed(String uri, String tier) {
        return new RouteInfo(null, uri, List.of("GET", "HEAD"), List.of(), Map.of(), List.of(), tier, List.of(), null);
    }
}
