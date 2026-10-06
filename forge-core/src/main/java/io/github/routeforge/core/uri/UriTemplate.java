package io.github.routeforge.core.uri;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * URI 模板归一化：把 Spring 的映射模板变成 forge 端点要下发的 URI 形态。
 *
 * <p><b>这是 Java 侧独有的部件，PHP 家族没有对等实现</b>（SPEC §4.3 已注明）：Laravel 的
 * {@code Route::uri()} 里从来不带正则约束（约束走 {@code ->where()}），也没有 {@code {*path}}
 * 捕获段语法，因此没有可参照的产物，本类只按前端消费契约自证。
 *
 * <p>三件事，缺一不可：
 * <ol>
 *   <li><b>剥离约束</b>：{@code {user:\d+}} → {@code {user}}。前端的占位符正则是
 *       {@code /\{([^{}]+)\}/g}，带冒号的占位符它替换不了，会原样留在拼出来的 URL 里；</li>
 *   <li><b>取参数名</b>：Framework 7 的 {@code PathPattern} 已不公开变量名，只能自己解析模板；
 *       且必须按<b>花括号深度</b>配对——{@code {id:\d{4}}} 里的 {@code {4}} 是正则量词，
 *       按「第一个右花括号」切会把名字读成 {@code id:\d}；</li>
 *   <li><b>可选段标记</b>：Spring 语法直接拒绝 {@code {page?}}，所以可选性由注解声明，
 *       再经 {@link #withOptional} 拼回 Laravel 形态的 {@code {page?}}。</li>
 * </ol>
 *
 * <p>{@code {*path}} 这类<b>前端填不了</b>的占位段原样保留、但不计为参数：把它们报进
 * {@code parameters} 等于承诺「传这个键就能拼出 URL」，实际拼出来是坏 URL。
 */
public final class UriTemplate {

    /** 合法参数名形态：够窄才不会把正则片段当成名字。 */
    private static final Pattern VARIABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final List<Segment> segments;
    private final String normalized;

    private UriTemplate(List<Segment> segments) {
        this.segments = List.copyOf(segments);
        this.normalized = render(this.segments);
    }

    /** 模板片段种类。字面量与「原样保留但不可参数化」的占位段分开，避免用 null 名字间接表达语义。 */
    private enum Kind {
        LITERAL,
        VARIABLE,
        OPAQUE
    }

    /**
     * 单个片段。
     *
     * @param text     渲染该片段时使用的文本（字面量与不可参数化段直接用；变量段由 name/optional 重拼）
     * @param name     参数名；非变量片段为 {@code null}
     * @param optional 是否带 Laravel 的可选标记
     */
    private record Segment(Kind kind, String text, String name, boolean optional) {

        static Segment literal(String text) {
            return new Segment(Kind.LITERAL, text, null, false);
        }

        static Segment opaque(String text) {
            return new Segment(Kind.OPAQUE, text, null, false);
        }

        static Segment variable(String name, boolean optional) {
            return new Segment(Kind.VARIABLE, null, name, optional);
        }
    }

    /**
     * 解析 URI 模板（Spring 或 Laravel 形态都收）。
     *
     * @throws IllegalArgumentException 花括号未闭合或多出右括号。正常情况下 Spring 注册阶段就拒绝了
     *                                  这类模板，走到这里说明喂进来的不是映射模板——宁可炸，
     *                                  也不要静默产出一个错名字让前端去拼坏 URL。
     */
    public static UriTemplate parse(String pattern) {
        Objects.requireNonNull(pattern, "pattern");

        List<Segment> segments = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        StringBuilder body = new StringBuilder();
        int depth = 0;

        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (depth > 0 && c == '\\' && i + 1 < pattern.length()) {
                // 转义序列整体吞掉：Spring 接受 {code:[a-z\}]+}（要匹配字面右花括号必须转义），
                // 不吞就会把 \} 当成占位段结束，名字切坏、尾巴掉进字面量（变异检验已验证此覆盖）
                body.append(c).append(pattern.charAt(++i));
                continue;
            }
            if (c == '{') {
                if (depth++ == 0) {
                    flushLiteral(segments, literal);
                } else {
                    body.append(c); // 嵌套左括号：属于正则量词（如 \d{2,4}）
                }
                continue;
            }
            if (c == '}') {
                if (depth == 0) {
                    throw new IllegalArgumentException("URI 模板存在多余的右花括号: " + pattern);
                }
                depth--;
                if (depth == 0) {
                    addVariable(segments, body.toString());
                    body.setLength(0);
                } else {
                    body.append(c);
                }
                continue;
            }
            if (depth == 0) {
                literal.append(c);
            } else {
                body.append(c);
            }
        }

        if (depth != 0) {
            throw new IllegalArgumentException("URI 模板花括号未闭合: " + pattern);
        }
        flushLiteral(segments, literal);
        return new UriTemplate(segments);
    }

    /** 下发给前端的 URI 模板（约束已剥离、可选段带 {@code ?}）。 */
    public String normalized() {
        return normalized;
    }

    /** 路径参数名，按出现顺序去重。 */
    public List<String> parameters() {
        return names(false);
    }

    /** 其中带可选标记的参数名（产物 URI 里渲染为 {@code {name?}}）。 */
    public List<String> optionalParameters() {
        return names(true);
    }

    /**
     * 把指定参数标记为可选，并重新渲染模板。
     *
     * <p>名字不在模板参数里就抛异常：静默忽略会让宿主以为「这个参数可以省」，而前端拿到的仍是
     * 必选占位符，缺值时拼出 {@code /posts/{page}} 这种坏 URL。
     */
    public UriTemplate withOptional(Collection<String> names) {
        Set<String> wanted = new LinkedHashSet<>(names);
        List<String> known = parameters();
        for (String name : wanted) {
            if (!known.contains(name)) {
                throw new IllegalArgumentException("声明的可选参数 [" + name + "] 不在 URI 模板 " + normalized
                        + " 中；模板里的参数是: " + (known.isEmpty() ? "（无）" : known));
            }
        }

        List<Segment> updated = new ArrayList<>(segments.size());
        for (Segment segment : segments) {
            updated.add(segment.kind() == Kind.VARIABLE && wanted.contains(segment.name())
                    ? Segment.variable(segment.name(), true)
                    : segment);
        }
        return new UriTemplate(updated);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof UriTemplate uriTemplate && normalized.equals(uriTemplate.normalized);
    }

    @Override
    public int hashCode() {
        return normalized.hashCode();
    }

    @Override
    public String toString() {
        return normalized;
    }

    private List<String> names(boolean optionalOnly) {
        List<String> out = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment.kind() != Kind.VARIABLE || (optionalOnly && !segment.optional())) {
                continue;
            }
            if (!out.contains(segment.name())) {
                out.add(segment.name());
            }
        }
        return List.copyOf(out);
    }

    private static void flushLiteral(List<Segment> segments, StringBuilder literal) {
        if (literal.length() > 0) {
            segments.add(Segment.literal(literal.toString()));
            literal.setLength(0);
        }
    }

    private static void addVariable(List<Segment> segments, String body) {
        if (body.isEmpty() || body.charAt(0) == '*') {
            segments.add(Segment.opaque("{" + body + "}"));
            return;
        }
        int colon = body.indexOf(':');
        String declaredName = colon < 0 ? body : body.substring(0, colon);
        boolean optional = declaredName.endsWith("?");
        if (optional) {
            declaredName = declaredName.substring(0, declaredName.length() - 1);
        }
        if (!VARIABLE_NAME.matcher(declaredName).matches()) {
            segments.add(Segment.opaque("{" + body + "}"));
            return;
        }
        segments.add(Segment.variable(declaredName, optional));
    }

    private static String render(List<Segment> segments) {
        StringBuilder out = new StringBuilder();
        for (Segment segment : segments) {
            switch (segment.kind()) {
                case LITERAL, OPAQUE -> out.append(segment.text());
                case VARIABLE -> out.append('{').append(segment.name())
                        .append(segment.optional() ? "?" : "")
                        .append('}');
            }
        }
        return out.toString();
    }
}
