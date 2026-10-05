package io.github.routeforge.core.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 排除过滤器守卫。URI 维度尤其关键：包自身的层级端点是未命名路由，
 * 配了 {@code endpoint_middleware} 就会被该层级的 match 规则命中，
 * 不按 URI 段排除的话，包会把自己报成宿主的配置错误。
 */
class RouteNameFilterTest {

    @Test
    @DisplayName("默认排除 forge 自身端点路由名，按字符串前缀判定")
    void excludesForgeOwnRouteNames() {
        RouteNameFilter filter = new RouteNameFilter();

        assertThat(filter.isNameExcluded("forge.routes.admin")).isTrue();
        assertThat(filter.isNameExcluded("forge.manager.api.config")).isTrue();
        assertThat(filter.isNameExcluded("admin.users.index")).isFalse();
        assertThat(filter.isNameExcluded(null)).isFalse();
    }

    @Test
    @DisplayName("适配层可追加框架内部路由名前缀，且不丢默认前缀")
    void extraNamePrefixesKeepDefaults() {
        RouteNameFilter filter = RouteNameFilter.withExtraNamePrefixes(List.of("__think_auto_route__"));

        assertThat(filter.isNameExcluded("__think_auto_route__.foo")).isTrue();
        assertThat(filter.isNameExcluded("forge.routes.x")).isTrue();
        assertThat(filter.isNameExcluded("client.orders.list")).isFalse();
    }

    @Test
    @DisplayName("URI 排除按段匹配：_forge/routes 不误伤 _forge/routeship")
    void uriExclusionMatchesBySegment() {
        RouteNameFilter filter = new RouteNameFilter().withUriPrefixes(List.of("/_forge/routes"));

        assertThat(filter.isUriExcluded("_forge/routes")).isTrue();
        assertThat(filter.isUriExcluded("/_forge/routes")).isTrue();
        assertThat(filter.isUriExcluded("_forge/routes/admin")).isTrue();
        assertThat(filter.isUriExcluded("_forge/routeship")).isFalse();
        assertThat(filter.isUriExcluded("admin/users")).isFalse();
    }

    @Test
    @DisplayName("空前缀不命中任何 URI（否则等于清空整张路由表）")
    void emptyUriPrefixMatchesNothing() {
        RouteNameFilter filter = new RouteNameFilter().withUriPrefixes(List.of("/", ""));

        assertThat(filter.isUriExcluded("admin/users")).isFalse();
        assertThat(filter.isUriExcluded("")).isFalse();
    }

    @Test
    @DisplayName("叠加 URI 前缀不可变：原实例不受影响，且去重")
    void withUriPrefixesIsImmutableAndDeduplicated() {
        RouteNameFilter base = new RouteNameFilter().withUriPrefixes(List.of("/_forge/routes"));
        RouteNameFilter extended = base.withUriPrefixes(List.of("/_forge/routes", "/_debug"));

        assertThat(base.excludedUriPrefixes()).containsExactly("/_forge/routes");
        assertThat(extended.excludedUriPrefixes()).containsExactly("/_forge/routes", "/_debug");
        assertThat(extended.excludedNamePrefixes()).containsExactlyElementsOf(RouteNameFilter.FORGE_PREFIXES);
    }

    @Test
    @DisplayName("Spring 侧口径：无名路由靠 URI 排除，有名端点靠名字排除，两维独立")
    void twoDimensionsAreIndependent() {
        RouteNameFilter filter = new RouteNameFilter().withUriPrefixes(List.of("/_forge/routes"));

        // 未命名端点：名字判不了，URI 维度兜住
        assertThat(filter.isUriExcluded("_forge/routes/manage")).isTrue();
        // 有名内部路由：URI 不在前缀内，名字维度兜住
        assertThat(filter.isNameExcluded("forge.routes.manage")).isTrue();
        assertThat(filter.isUriExcluded("admin/members")).isFalse();
    }
}
