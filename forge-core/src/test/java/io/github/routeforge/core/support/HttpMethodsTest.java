package io.github.routeforge.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HttpMethodsTest {

    @Test
    @DisplayName("去 HEAD 保序且大小写不敏感")
    void filtersHeadCaseInsensitively() {
        assertThat(HttpMethods.withoutHead(List.of("GET", "HEAD", "POST", "head")))
                .containsExactly("GET", "POST");
    }

    @Test
    @DisplayName("d.ts 的 method 取首个非 HEAD；全 HEAD 时回落 GET")
    void primaryMethodFallsBackToGet() {
        assertThat(HttpMethods.primary(List.of("PUT", "HEAD"))).isEqualTo("PUT");
        assertThat(HttpMethods.primary(List.of("HEAD"))).isEqualTo("GET");
        assertThat(HttpMethods.primary(List.of())).isEqualTo("GET");
    }

    @Test
    @DisplayName("只有 POST/PUT/PATCH 需要 body 类型")
    void bodyOnlyForWritingMethods() {
        assertThat(HttpMethods.hasBody("POST")).isTrue();
        assertThat(HttpMethods.hasBody("put")).isTrue();
        assertThat(HttpMethods.hasBody("PATCH")).isTrue();
        assertThat(HttpMethods.hasBody("GET")).isFalse();
        assertThat(HttpMethods.hasBody("DELETE")).isFalse();
    }
}
