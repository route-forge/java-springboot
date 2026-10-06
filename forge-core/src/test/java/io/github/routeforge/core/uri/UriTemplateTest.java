package io.github.routeforge.core.uri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * {@link UriTemplate} 的行为守卫。
 *
 * <p>这里没有 PHP 对等物可比（Laravel 的 URI 不带正则约束、也没有捕获段），所以断言按
 * <b>前端消费契约</b>写：前端占位符正则是 {@code /\{([^{}]+)\}/g}，凡它替换不了的形态都不该出现在
 * 下发的 URI 里，也不该被报进 {@code parameters}。
 */
class UriTemplateTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        /admin/users/{user}|/admin/users/{user}|user
        /admin/users/{user:\\d+}|/admin/users/{user}|user
        /x/{id:\\d{4}}|/x/{id}|id
        /x/{id:\\d{2,4}}|/x/{id}|id
        /x/{code:[a-z\\}]+}|/x/{code}|code
        /x/{id:\\d\\{2\\}}|/x/{id}|id
        /posts/{page?}|/posts/{page?}|page
        /posts/{page?:\\d+}|/posts/{page?}|page
        /a/{b}/c/{b}|/a/{b}/c/{b}|b
        /v{version}/users/{user}|/v{version}/users/{user}|version;user
        /admin|/admin|none
        /|/|none
        """)
    @DisplayName("按花括号深度配对解析：约束剥离干净，参数名按出现顺序去重")
    void parsesByBraceDepth(String pattern, String expectedUri, String params) {
        UriTemplate parsed = UriTemplate.parse(pattern);

        assertThat(parsed.normalized()).isEqualTo(expectedUri);
        assertThat(parsed.parameters())
                .containsExactly("none".equals(params) ? new String[0] : params.split(";"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        /files/{*path}|/files/{*path}
        /static/{*segments:\\w+}|/static/{*segments:\\w+}
        /x/{a+b}|/x/{a+b}
        /legacy/**|/legacy/**
        /x/{9lives}|/x/{9lives}
        """)
    @DisplayName("不可参数化的占位段：原样保留、不进取参数表")
    void opaqueSegmentsAreNotParameters(String pattern, String expectedUri) {
        UriTemplate parsed = UriTemplate.parse(pattern);

        assertThat(parsed.normalized()).isEqualTo(expectedUri);
        assertThat(parsed.parameters()).isEmpty();
    }

    @Test
    @DisplayName("可选性由声明拼回：Spring 拒绝 {page?}，产物却要带 ? 才与 Laravel 同形")
    void optionalIsRenderedFromDeclaration() {
        UriTemplate parsed = UriTemplate.parse("/posts/{page}");

        UriTemplate optional = parsed.withOptional(List.of("page"));

        assertThat(optional.normalized()).isEqualTo("/posts/{page?}");
        assertThat(optional.optionalParameters()).containsExactly("page");
        assertThat(optional.parameters()).containsExactly("page");
        // 原实例不受影响：归一化结果可安全共享
        assertThat(parsed.normalized()).isEqualTo("/posts/{page}");
        assertThat(parsed.optionalParameters()).isEmpty();
    }

    @Test
    @DisplayName("多参数只标其一")
    void optionalMarksOnlyDeclaredNames() {
        UriTemplate parsed = UriTemplate.parse("/u/{user}/posts/{page}");

        UriTemplate optional = parsed.withOptional(List.of("page"));

        assertThat(optional.normalized()).isEqualTo("/u/{user}/posts/{page?}");
        assertThat(optional.optionalParameters()).containsExactly("page");
    }

    @Test
    @DisplayName("声明的可选名不在模板里 → 直接拒绝，不静默忽略")
    void rejectsUnknownOptionalName() {
        UriTemplate parsed = UriTemplate.parse("/posts/{page}");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> parsed.withOptional(List.of("sort")))
                .withMessageContaining("[sort]")
                .withMessageContaining("[page]");
    }

    @Test
    @DisplayName("模板没有参数时声明可选 → 报「（无）」而不是空列表读起来像 bug")
    void rejectsOptionalWhenNoParameters() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> UriTemplate.parse("/admin/dashboard").withOptional(List.of("page")))
                .withMessageContaining("（无）");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        /x/{unclosed
        /x/{a:\\d{2}
        /x/a}
        """)
    @DisplayName("花括号不配对时炸掉，不静默产出错名字")
    void rejectsUnbalancedBraces(String pattern) {
        assertThatIllegalArgumentException().isThrownBy(() -> UriTemplate.parse(pattern));
    }

    @Test
    @DisplayName("字面量段合并后仍逐字符保真：不吞斜杠、不补斜杠")
    void preservesLiteralText() {
        UriTemplate parsed = UriTemplate.parse("/api/v1.2/a_b-c/{id}/");

        assertThat(parsed.normalized()).isEqualTo("/api/v1.2/a_b-c/{id}/");
        assertThat(parsed.parameters()).containsExactly("id");
    }

    @Test
    @DisplayName("相等性按归一化结果比较（不同写法的同一模板可互相断言）")
    void equalityIsOnNormalized() {
        assertThat(UriTemplate.parse("/x/{id:\\d+}")).isEqualTo(UriTemplate.parse("/x/{id}"));
        assertThat(UriTemplate.parse("/x/{id}")).hasSameHashCodeAs(UriTemplate.parse("/x/{id:\\d+}"));
        assertThat(UriTemplate.parse("/x/{id}")).isNotEqualTo(UriTemplate.parse("/x/{id?}"));
    }
}
