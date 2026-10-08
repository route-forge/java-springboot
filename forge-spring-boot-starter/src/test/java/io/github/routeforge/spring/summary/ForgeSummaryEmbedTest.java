package io.github.routeforge.spring.summary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.routeforge.core.summary.SummaryRenderer;
import io.github.routeforge.spring.endpoint.ForgeTestApplication;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 内嵌摘要纯 Java API（SPEC §5.4）：产出的 {@code <script>} 必须与「用同一份摘要走核心渲染器」逐字相等——
 * 钉住 producer 复用（不另起扫描），并确认全局 key 与层级数据都在、且是未二次转义的原始 HTML。
 */
@SpringBootTest(classes = ForgeTestApplication.class)
class ForgeSummaryEmbedTest {

    @Autowired
    private ForgeSummaryEmbed embed;

    @Autowired
    private ForgeRouteRegistry registry;

    @Test
    @DisplayName("script() = 核心渲染器吃 registry.summary() 的结果，且含 __ROUTE_FORGE__ 与层级键")
    void scriptReusesEndpointProducer() {
        String script = embed.script();

        assertThat(script).startsWith("<script>").endsWith("</script>");
        assertThat(script).contains(SummaryRenderer.GLOBAL_KEY);
        assertThat(script).as("摘要里的层级键应出现在内嵌 JSON 中").contains("admin");
        assertThat(script)
                .as("必须与端点同一 producer 直接渲染的结果逐字相等（不许另起一份扫描）")
                .isEqualTo(SummaryRenderer.render(registry.summary()));
    }

    @Test
    @DisplayName("输出是原始 <script>，不是被 HTML 转义过的 &lt;script&gt;")
    void outputIsRawNotHtmlEscaped() {
        assertThat(embed.script())
                .doesNotContain("&lt;")
                .contains("<script>");
    }
}
