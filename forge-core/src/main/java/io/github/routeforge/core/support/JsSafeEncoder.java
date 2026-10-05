package io.github.routeforge.core.support;

/**
 * 内嵌 JS 的安全 JSON 编码器。
 *
 * <p>语义等价 {@code Illuminate\Support\Js::from($data)->toHtml()}（Laravel 端 {@code @forgeSummary}
 * 指令依赖该转义），此处为框架无关重实现，转义表已用 PHP 8.5 实跑输出逐字节校准。
 *
 * <p>双层转义是必须的：
 *
 * <ol>
 *   <li><b>第一层</b>：数据 → JSON 文本，由带 PHP {@code JSON_HEX_*} 等价转义的序列化器完成
 *       （Java 侧由 starter 配好 Jackson 的 {@code CharacterEscapes}）。非 ASCII 保持原样
 *       （对应 {@code JSON_UNESCAPED_UNICODE}），否则中文层级描述会让内嵌载荷按字符数成倍膨胀。</li>
 *   <li><b>第二层</b>（本类）：把整段 JSON 文本再当作字符串转义一次，产出 {@code JSON.parse('…')} 形态。
 *       只做第一层时，结构引号会破坏单引号 JS 字符串。</li>
 * </ol>
 *
 * <p>红线：内嵌 JSON 必须经本编码器，禁止裸拼字符串（XSS 逃逸风险）。
 */
public final class JsSafeEncoder {

    private JsSafeEncoder() {
    }

    /**
     * 第二层转义：输入是第一层产出的 JSON 文本，输出可安全放进 JS 单引号字符串。
     *
     * <p>逐字符规则与 PHP {@code json_encode($s, JSON_HEX_TAG|HEX_APOS|HEX_AMP|HEX_QUOT)} 一致：
     * 反斜杠翻倍，正斜杠转义，四类字符走 {@code &#92;uXXXX}。第一层已经转义过的内容在这里只会被整体加倍，
     * 解析后仍还原为原文，因此 {@code </script>} 无法截断脚本块。
     */
    public static String escapeForSingleQuotedJsString(String json) {
        StringBuilder out = new StringBuilder(json.length() + 32);
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '/' -> out.append("\\/");
                case '"' -> out.append("\\u0022");
                case '<' -> out.append("\\u003C");
                case '>' -> out.append("\\u003E");
                case '&' -> out.append("\\u0026");
                case '\'' -> out.append("\\u0027");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** 把 JSON 文本包成 {@code JSON.parse('…')} 表达式。 */
    public static String asJsonParseExpression(String json) {
        return "JSON.parse('" + escapeForSingleQuotedJsString(json) + "')";
    }
}
