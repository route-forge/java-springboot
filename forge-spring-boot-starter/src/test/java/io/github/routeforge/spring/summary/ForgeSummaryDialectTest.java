package io.github.routeforge.spring.summary;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.routeforge.core.summary.SummaryRenderer;
import io.github.routeforge.spring.endpoint.ForgeTestApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

/**
 * 可选 Thymeleaf 方言的渲染契约：{@code #forgeSummary.summary} 经 {@code th:utext} 输出<b>未转义</b>的原始
 * {@code <script>}——这是 {@link SummaryRenderer} 那条「不能再转义一次」红线的落地验证。同时证明当 classpath
 * 有 Thymeleaf 时，方言 bean 确实被自动配置注册进容器。
 */
@SpringBootTest(classes = ForgeTestApplication.class)
class ForgeSummaryDialectTest {

    @Autowired
    private ForgeSummaryEmbed embed;

    @Autowired
    private ForgeSummaryDialect dialect;

    @Test
    @DisplayName("thymeleaf 在场时方言 bean 被注册（@ConditionalOnClass 命中）")
    void dialectIsRegisteredWhenThymeleafPresent() {
        assertThat(dialect).isNotNull();
        assertThat(dialect.getName()).isEqualTo("forgeSummary");
    }

    @Test
    @DisplayName("th:utext 输出原始 <script>，不被 HTML 转义，且与 embed.script() 同源")
    void rendersRawScriptViaUtext() {
        // 裸 TemplateEngine 默认按「模板名」解析；StringTemplateResolver 把传入串当模板正文，用于内联渲染测试
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.HTML);
        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addDialect(dialect);

        String out = engine.process(
                "<div th:utext=\"${#forgeSummary.summary}\"></div>", new Context());

        assertThat(out)
                .as("内嵌的 <script> 必须原样出现，绝不能被转义成 &lt;")
                .contains("<script>")
                .contains(SummaryRenderer.GLOBAL_KEY)
                .doesNotContain("&lt;");
        assertThat(out).contains(embed.script());
    }
}
