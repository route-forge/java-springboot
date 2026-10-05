package io.github.routeforge.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 转义表已用本机 PHP 8.5.11 的 {@code json_encode(.., JSON_HEX_*)} 输出校准过简单载荷；
 * 复杂载荷的逐字节对等放在 P5（由 PHP 生成 fixture、测试读文件比对）——
 * 把 PHP 的多层转义结果手算成 Java 字面量本身就是易错源，不做。
 */
class JsSafeEncoderTest {

    @Test
    @DisplayName("简单载荷与 PHP 第二层输出逐字节一致")
    void matchesPhpForSimplePayload() {
        // PHP: json_encode('{"simple":1}', JSON_HEX_TAG|HEX_APOS|HEX_AMP|HEX_QUOT) 去外层引号
        assertThat(JsSafeEncoder.asJsonParseExpression("{\"simple\":1}"))
                .isEqualTo("JSON.parse('{\\u0022simple\\u0022:1}')");
    }

    @Test
    @DisplayName("逐字符转义表：反斜杠翻倍、正斜杠转义、四类字符走 \\uXXXX")
    void escapesEachCharByTable() {
        assertThat(JsSafeEncoder.escapeForSingleQuotedJsString("\\")).isEqualTo("\\\\");
        assertThat(JsSafeEncoder.escapeForSingleQuotedJsString("/")).isEqualTo("\\/");
        assertThat(JsSafeEncoder.escapeForSingleQuotedJsString("\"")).isEqualTo("\\u0022");
        assertThat(JsSafeEncoder.escapeForSingleQuotedJsString("<")).isEqualTo("\\u003C");
        assertThat(JsSafeEncoder.escapeForSingleQuotedJsString(">")).isEqualTo("\\u003E");
        assertThat(JsSafeEncoder.escapeForSingleQuotedJsString("&")).isEqualTo("\\u0026");
        assertThat(JsSafeEncoder.escapeForSingleQuotedJsString("'")).isEqualTo("\\u0027");
    }

    @Test
    @DisplayName("非 ASCII 原样保留：转成 \\uXXXX 会让中文层级描述体积翻数倍")
    void keepsNonAsciiAsIs() {
        assertThat(JsSafeEncoder.escapeForSingleQuotedJsString("运营管理接口 😀"))
                .isEqualTo("运营管理接口 😀");
    }

    @Test
    @DisplayName("载荷内不残留任何能截断脚本块或闭合 JS 字符串的裸字符")
    void neutralizesScriptTerminator() {
        String json = "{\"description\":\"</script><img src=x onerror=alert(1)>&&''\"}";

        String payload = JsSafeEncoder.escapeForSingleQuotedJsString(json);

        assertThat(payload)
                .doesNotContain("<")
                .doesNotContain(">")
                .doesNotContain("\"")
                .doesNotContain("'")
                .doesNotContain("&");
        assertThat(JsSafeEncoder.asJsonParseExpression(json)).startsWith("JSON.parse('").endsWith("')");
    }

    @Test
    @DisplayName("往返性质：按转义表解码回去必须等于原文（挡住加倍漏写与漏转义）")
    void roundTripsThroughDecoder() {
        List<String> payloads = List.of(
                "{\"simple\":1}",
                "{\"a\":\"x\\/y \\\\ z\"}",
                "{\"d\":\"\\u003C/script\\u003E \\u0022q\\u0022 \\\\ \\u0027a\\u0027 中文 \\n\\t\"}",
                "{\"url_prefix\":\"https:\\/\\/api.example.com\\/v1?a=1\\u0026b=2\"}",
                "{\"strict_mode\":false,\"cache_ttl\":null}");

        for (String payload : payloads) {
            assertThat(decodeSingleQuotedJsString(JsSafeEncoder.escapeForSingleQuotedJsString(payload)))
                    .as("载荷 %s 转义后必须可无损还原", payload)
                    .isEqualTo(payload);
        }
    }

    /**
     * 仅测试用的反向解码器：把 {@code &#92;&#92;}、{@code &#92;/} 与四类 {@code &#92;uXXXX} 还原。
     *
     * <p>刻意独立于生产实现（不复用同一张表），否则实现写错时测试会跟着一起错。
     */
    private static String decodeSingleQuotedJsString(String escaped) {
        StringBuilder out = new StringBuilder(escaped.length());
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char next = escaped.charAt(++i);
            switch (next) {
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'u' -> {
                    String hex = escaped.substring(i + 1, i + 5);
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                }
                default -> throw new IllegalStateException("未知转义序列: \\" + next);
            }
        }
        return out.toString();
    }
}
