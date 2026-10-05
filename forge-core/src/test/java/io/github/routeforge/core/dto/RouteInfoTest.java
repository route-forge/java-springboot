package io.github.routeforge.core.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link RouteInfo} 是核心层唯一的输入形状，适配层构造、算法层消费。
 * 这里守住两件事：集合不可变（避免扫描结果被下游改写），以及空集合不等于 null
 * （端点要把 {@code parameter_defaults} 序列化成 {@code {}}，null 会破契约）。
 */
class RouteInfoTest {

    private static RouteInfo sample(String name, String tier, List<String> aliases) {
        return new RouteInfo(name, "admin/users/{user}", List.of("GET", "HEAD"), List.of("user"),
                Map.of(), List.of("auth", "admin"), tier, aliases, new Object());
    }

    @Test
    @DisplayName("集合入参被拷贝，外部改动不影响已构造的路由信息")
    void copiesCollectionsDefensively() {
        List<String> methods = new ArrayList<>(List.of("GET"));
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("page", "1");
        List<String> middleware = new ArrayList<>(List.of("auth"));

        RouteInfo info = new RouteInfo("a.b", "a/b", methods, List.of(), defaults, middleware,
                "admin", List.of(), null);

        methods.add("POST");
        defaults.put("page", "2");
        middleware.add("admin");

        assertThat(info.methods()).containsExactly("GET");
        assertThat(info.parameterDefaults()).containsEntry("page", "1");
        assertThat(info.middleware()).containsExactly("auth");
    }

    @Test
    @DisplayName("集合传 null 直接拒绝：宁可启动期失败，也不要端点 500 或序列化出 null")
    void rejectsNullCollections() {
        assertThatThrownBy(() -> new RouteInfo("a.b", "a/b", null, List.of(), Map.of(), List.of(),
                null, List.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("methods");
    }

    @Test
    @DisplayName("空白 tier 等于没标注：层级归属不能靠空串表达")
    void blankTierIsNotExplicitTier() {
        assertThat(sample("admin.users.index", "admin", List.of()).hasExplicitTier()).isTrue();
        assertThat(sample("admin.users.index", null, List.of()).hasExplicitTier()).isFalse();
        assertThat(sample("admin.users.index", "   ", List.of()).hasExplicitTier()).isFalse();
    }

    @Test
    @DisplayName("未命名路由的判据含空串：适配层常把 URI 原样填进 name 位")
    void unnamedCoversBlankName() {
        assertThat(sample(null, "admin", List.of()).isUnnamed()).isTrue();
        assertThat(sample("", "admin", List.of()).isUnnamed()).isTrue();
        assertThat(sample("admin.users.index", "admin", List.of()).isUnnamed()).isFalse();
    }
}
