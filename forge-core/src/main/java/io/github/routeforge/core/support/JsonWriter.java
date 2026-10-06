package io.github.routeforge.core.support;

import java.util.List;
import java.util.Map;

/**
 * 与 PHP {@code json_encode} 同形态的 JSON 写出器（核心层零依赖，不引 Jackson）。
 *
 * <p>为什么核心层要自己写：{@code --forge:types --json} 的产物与内嵌摘要的载荷都是<b>对外契约文本</b>，
 * 必须与 Laravel 侧逐字节可比。Jackson 的默认美化输出在冒号前多一个空格、缩进与转义规则也不同，
 * 直接拿来用会得到一份「语义相同、字节不同」的文本，跨语言对等就失去意义。
 * 端点响应的序列化仍交给适配层的 Jackson，只有这两处产物走本类。
 *
 * <p>三种形态与 PHP flags 逐项对应（细节已用本机 PHP 8.5.11 实测校准：控制字符与非 ASCII 走
 * <b>小写</b>十六进制、增补字符拆成代理对、美化形态冒号后带空格）：
 * <ul>
 *   <li>{@link Style#PHP_PRETTY} = {@code JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES}：4 空格缩进、
 *       冒号后带空格、斜杠不转义、非 ASCII 转义；</li>
 *   <li>{@link Style#PHP_COMPACT} = 默认 {@code json_encode}：紧凑、斜杠转义、非 ASCII 转义；</li>
 *   <li>{@link Style#PHP_HEX_COMPACT} = {@code JSON_HEX_TAG|HEX_APOS|HEX_AMP|HEX_QUOT|UNESCAPED_UNICODE}：
 *       紧凑、斜杠转义、字符串<b>内容</b>里的 {@code " < > & '} 转 {@code &#92;uXXXX}（结构引号不动）、
 *       非 ASCII 原样。这是内嵌摘要的第一层，产物再交 {@link JsSafeEncoder} 做第二层。</li>
 * </ul>
 */
public final class JsonWriter {

    /** 写出形态：排版与三类转义开关的组合。开关逐位声明，避免「形态名暗示的 flags」与实际不符。 */
    public enum Style {
        PHP_PRETTY(true, false, true, false),
        PHP_COMPACT(false, true, true, false),
        PHP_HEX_COMPACT(false, true, false, true);

        final boolean pretty;
        final boolean escapeSlash;
        final boolean escapeUnicode;
        final boolean hexQuotes;

        Style(boolean pretty, boolean escapeSlash, boolean escapeUnicode, boolean hexQuotes) {
            this.pretty = pretty;
            this.escapeSlash = escapeSlash;
            this.escapeUnicode = escapeUnicode;
            this.hexQuotes = hexQuotes;
        }
    }

    private JsonWriter() {
    }

    /** PHP 默认（紧凑、转义斜杠与非 ASCII）形态。 */
    public static String compact(Object value) {
        return write(value, Style.PHP_COMPACT);
    }

    /** {@code JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES} 形态。 */
    public static String pretty(Object value) {
        return write(value, Style.PHP_PRETTY);
    }

    /** 内嵌摘要第一层：紧凑 + HEX 转义 + 非 ASCII 原样。 */
    public static String hexCompact(Object value) {
        return write(value, Style.PHP_HEX_COMPACT);
    }

    public static String write(Object value, Style style) {
        StringBuilder out = new StringBuilder();
        emit(value, style, 0, out);
        return out.toString();
    }

    private static void emit(Object value, Style style, int depth, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(text, style, out);
        } else if (value instanceof Boolean flag) {
            out.append(flag ? "true" : "false");
        } else if (value instanceof Number number) {
            out.append(number);
        } else if (value instanceof Map<?, ?> map) {
            emitObject(map, style, depth, out);
        } else if (value instanceof List<?> list) {
            emitArray(list, style, depth, out);
        } else {
            throw new IllegalArgumentException("不支持的值类型：" + value.getClass().getName());
        }
    }

    private static void emitObject(Map<?, ?> map, Style style, int depth, StringBuilder out) {
        if (map.isEmpty()) {
            out.append("{}");
            return;
        }
        out.append('{');
        int next = depth + 1;
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            newlineIndent(out, style, next);
            quote(String.valueOf(entry.getKey()), style, out);
            out.append(style.pretty ? ": " : ":");
            emit(entry.getValue(), style, next, out);
        }
        newlineIndent(out, style, depth);
        out.append('}');
    }

    private static void emitArray(List<?> list, Style style, int depth, StringBuilder out) {
        if (list.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append('[');
        int next = depth + 1;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            newlineIndent(out, style, next);
            emit(list.get(i), style, next, out);
        }
        newlineIndent(out, style, depth);
        out.append(']');
    }

    private static void newlineIndent(StringBuilder out, Style style, int depth) {
        if (style.pretty) {
            out.append('\n').append("    ".repeat(depth));
        }
    }

    private static void quote(String text, Style style, StringBuilder out) {
        out.append('"');
        text.codePoints().forEach(codePoint -> escapeCodePoint(codePoint, style, out));
        out.append('"');
    }

    private static void escapeCodePoint(int codePoint, Style style, StringBuilder out) {
        switch (codePoint) {
            case '"' -> out.append(style.hexQuotes ? "\\u0022" : "\\\"");
            case '\\' -> out.append("\\\\");
            case '/' -> out.append(style.escapeSlash ? "\\/" : "/");
            case '<' -> out.append(style.hexQuotes ? "\\u003C" : "<");
            case '>' -> out.append(style.hexQuotes ? "\\u003E" : ">");
            case '&' -> out.append(style.hexQuotes ? "\\u0026" : "&");
            case '\'' -> out.append(style.hexQuotes ? "\\u0027" : "'");
            case '\n' -> out.append("\\n");
            case '\r' -> out.append("\\r");
            case '\t' -> out.append("\\t");
            case 8 -> out.append("\\b");
            case 12 -> out.append("\\f");
            default -> {
                if (codePoint < 0x20 || (style.escapeUnicode && codePoint > 0x7E)) {
                    appendUnicodeEscape(codePoint, out);
                } else {
                    out.appendCodePoint(codePoint);
                }
            }
        }
    }

    /**
     * PHP 风格的小写十六进制 {@code &#92;uXXXX}（格式串必须用小写：PHP 输出的是 {@code \u001f}）；
     * 增补字符拆成一对代理码位——PHP 对 emoji 输出两个转义而不是一个码位。
     *
     * <p>代理项必须显式转 int：{@code Character} 喂给 {@code %x} 会抛 IllegalFormatConversionException。
     */
    private static void appendUnicodeEscape(int codePoint, StringBuilder out) {
        if (Character.isSupplementaryCodePoint(codePoint)) {
            out.append(String.format("\\u%04x\\u%04x",
                    (int) Character.highSurrogate(codePoint), (int) Character.lowSurrogate(codePoint)));
            return;
        }
        out.append(String.format("\\u%04x", codePoint));
    }
}
