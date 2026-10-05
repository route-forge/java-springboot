package io.github.routeforge.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 端点前缀规范化守卫：这个函数被三处共用（端点注册、摘要下发、d.ts 文件头），
 * 一旦三处口径分叉，前端按下发值拼出的 URL 就打不到真实注册路径。
 */
class EndpointPrefixTest {

    @Test
    @DisplayName("补齐前导斜杠、去掉尾部斜杠")
    void normalizesLeadingAndTrailingSlashes() {
        assertThat(EndpointPrefix.normalize("_forge/routes")).isEqualTo("/_forge/routes");
        assertThat(EndpointPrefix.normalize("/_forge/routes/")).isEqualTo("/_forge/routes");
        assertThat(EndpointPrefix.normalize("///forge/routes///")).isEqualTo("/forge/routes");
        assertThat(EndpointPrefix.normalize("/api")).isEqualTo("/api");
    }

    @Test
    @DisplayName("空串归一为单斜杠：与 PHP 侧 rtrim/ltrim 结果逐字一致")
    void emptyBecomesSingleSlash() {
        assertThat(EndpointPrefix.normalize("")).isEqualTo("/");
        assertThat(EndpointPrefix.normalize("/")).isEqualTo("/");
    }

    @Test
    @DisplayName("幂等：归一一次与归一两次同值")
    void idempotent() {
        String once = EndpointPrefix.normalize("forge/routes/");

        assertThat(EndpointPrefix.normalize(once)).isEqualTo(once);
    }
}
