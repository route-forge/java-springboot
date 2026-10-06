package io.github.routeforge.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.routeforge.core.PhpFixtures;
import io.github.routeforge.core.summary.SummaryRenderer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JSON 写出形态与内嵌摘要脚本的跨语言对等测试。
 *
 * <p>这两样东西是「差一个反斜杠就出事」的典型：内嵌摘要的转义表漏一项，宿主页面就是一个可执行的 XSS；
 * 而美化形态的冒号空格、缩进宽度、斜杠是否转义，决定了 {@code --json} 产物能不能逐字节比对。
 * 所以期望值取 PHP 真实 {@code json_encode} 输出，输入结构两侧共用同一份 JSON 文本重建。
 */
class PhpJsonFormsOracleTest {

    /** 压测用结构：ASCII、斜杠、三类引号、控制字符、非 ASCII 与 emoji、空映射、空列表、嵌套、特殊键名。 */
    private static Object nastyStructure() {
        // fixture 里 jsonInput 是一段 JSON 文本（不是内嵌对象），先取文本再解析，形态才无歧义
        return parseInput(PhpFixtures.read("types.json").get("jsonInput").asText());
    }

    @Test
    @DisplayName("三种形态与 PHP json_encode 逐字节一致")
    void matchesPhpJsonEncode() {
        JsonNode forms = PhpFixtures.read("types.json").get("jsonForms");
        Object value = nastyStructure();

        assertThat(JsonWriter.pretty(value)).isEqualTo(forms.get("pretty").asText());
        assertThat(JsonWriter.compact(value)).isEqualTo(forms.get("compact").asText());
        assertThat(JsonWriter.hexCompact(value)).isEqualTo(forms.get("hexCompact").asText());
    }

    @Test
    @DisplayName("内嵌摘要脚本与 PHP SummaryRenderer 逐字节一致（含 XSS 探针载荷）")
    void matchesSummaryRenderer() {
        List<JsonNode> cases = new java.util.ArrayList<>();
        PhpFixtures.read("types.json").get("summaryCases").forEach(cases::add);
        assertThat(cases).isNotEmpty();

        for (JsonNode entry : cases) {
            String name = entry.get("name").asText();
            Map<String, Object> summary = parseInput(entry.get("inputJson").asText());

            assertThat(JsonWriter.hexCompact(summary))
                    .as("用例 %s 的第一层 HEX 转义", name)
                    .isEqualTo(entry.get("expected").get("firstLayer").asText());
            // render() 内部即「hexCompact + 第二层字符串转义」，整段脚本一起比对
            assertThat(SummaryRenderer.render(summary))
                    .as("用例 %s 的整段 <script>", name)
                    .isEqualTo(entry.get("expected").get("html").asText());
        }
    }

    @Test
    @DisplayName("内嵌脚本载荷里不存在能截断脚本块的裸字符")
    void embeddedScriptCannotBeTerminatedEarly() {
        Map<String, Object> payload = Map.of(
                "levels", Map.of("a", Map.of("description", "</script><img src=x onerror=alert(1)>")),
                "config", Map.of("url_prefix", "https://x/?a=1&b=2"));

        String html = SummaryRenderer.render(payload);
        // 只取载荷表达式那一行：脚本本身的骨架（JSON.parse('…') 的引号、结尾分号）不参与断言
        String expression = html.lines()
                .filter(line -> line.stripLeading().startsWith("var v = "))
                .findFirst()
                .orElseThrow()
                .strip()
                .substring("var v = ".length())
                .replaceFirst(";$", "");

        assertThat(expression.startsWith("JSON.parse('") && expression.endsWith("')")).isTrue();
        String body = expression.substring("JSON.parse('".length(), expression.length() - 2);
        assertThat(body)
                .as("载荷内不得残留可截断脚本块或闭合 JS 字符串的裸字符")
                .doesNotContain("</script")
                .doesNotContain("<")
                .doesNotContain(">")
                .doesNotContain("\"")
                .doesNotContain("'")
                .doesNotContain("&");
        // 结构引号必须转义成 \u0022，否则单引号 JS 字符串会被提前闭合
        assertThat(body).contains("\\u0022");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInput(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
