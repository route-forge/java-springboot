package io.github.routeforge.spring.scan;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.annotation.MergedAnnotation;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.web.method.HandlerMethod;

/**
 * 第四通道：从 handler 上<b>已有的</b>守卫注解派生 {@code middleware} 标签，让宿主不必把同一件事抄第二遍。
 *
 * <p>为什么要有这条通道：Spring 侧守卫的主流写法只有两种（实测命中文件数并列，见 SPEC §4.4），
 * 按 URL 配的与在方法/类上写注解的各占一半。前者天然与 {@code match.prefix} 同构，零声明即可归类；
 * 后者的名字已经写在 handler 上了，再要求 {@code @ForgeRoute(middleware = {...})} 抄一遍就是重复声明。
 *
 * <p>三条不可动摇的边界（SPEC §4.4）：
 * <ul>
 *   <li><b>标签只做层级归类，永远不构成安全边界</b>——本类不校验 method security 是否真被
 *       {@code @EnableMethodSecurity} 启用，宿主把注解写成装饰物时派生结果会跟着装饰；</li>
 *   <li><b>不猜读不懂的东西</b>：凡不能无损降成名字标签的声明，一律 {@code expression:} + 原文
 *       （见 {@link #EXPRESSION_PREFIX}），绝不产出比原声明更宽或更窄的标签——归类被放宽就是读错；</li>
 *   <li><b>零 Security 依赖</b>：注解一律按<b>类型全名</b>匹配，因此 main 侧不引 {@code spring-security-*}、
 *       也不 import 任何 Security 类。并且是<b>迭代</b>在场的注解再比名字，而不是按名字去查——
 *       按名字查要先解析那个类，classpath 上没有就会把整轮扫描带崩。</li>
 * </ul>
 *
 * <p>就近语义与 Spring Security 一致：<b>方法级覆盖类级</b>（含父类与接口，组合注解也算——
 * 宿主自建的 {@code @AdminOnly} 只要 meta-annotate 了 {@code @PreAuthorize} 就会被读到）。
 * 多种守卫注解同时在场时标签取并集：每种注解有各自独立的拦截器，运行期是<b>全部通过</b>才放行。
 *
 * <p>一条实测事实（曾经按直觉写错过，{@code GuardLabelsTest} 钉住）：{@code SearchStrategy.TYPE_HIERARCHY}
 * 作用在 Method 元素上时<b>不</b>连带声明类的注解，类上的守卫要单独查一次——所以入口是
 * {@link #derive(HandlerMethod)}（方法 + bean 类型两轮合并），只按方法元素看的 {@code labelsOf} 仅供规则表测试。
 */
public final class GuardLabels {

    /** 读不懂的守卫声明以此为前缀原样下发：宁可难看，不许读错。 */
    public static final String EXPRESSION_PREFIX = "expression:";

    /** 只要求登录、没有权限名的谓词派生出的标签；{@code __} 前缀是本包保留字面量。 */
    static final String AUTHENTICATED = "__authenticated";
    static final String PERMIT_ALL = "__permit_all";
    static final String DENY_ALL = "__deny_all";

    private static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";
    private static final String SECURED = "org.springframework.security.access.annotation.Secured";
    /** JSR-250 在 Boot 2 存量里是 {@code javax} 包名，两种都认。 */
    private static final String ROLES_ALLOWED = "jakarta.annotation.security.RolesAllowed";
    private static final String ROLES_ALLOWED_JAVAX = "javax.annotation.security.RolesAllowed";

    /** 带角色名数组的守卫，按此顺序产出标签（顺序固定，产物才可复现）。 */
    private static final List<String> ROLE_ARRAY_GUARDS = List.of(SECURED, ROLES_ALLOWED, ROLES_ALLOWED_JAVAX);

    /** 无值标记注解全名 → 标签。必须按插入序遍历，产物顺序才可复现，所以不能交给 {@code Map.copyOf}。 */
    private static final Map<String, String> MARKERS = markerMap();

    private static final Set<String> KNOWN = knownNames();

    private GuardLabels() {
    }

    /**
     * 取一个 handler 方法上的守卫标签。
     *
     * <p>先看方法元素（{@link SearchStrategy#TYPE_HIERARCHY} 会连带其声明类与接口），再补 bean 类型上的
     * 类级注解——handler 方法可能声明在抽象基类里，那时具体 controller 类上的守卫不在第一轮的类层级里，
     * 只看方法就会漏。同一种注解两处都有时以方法为准（就近）。
     */
    public static List<String> derive(HandlerMethod handlerMethod) {
        Map<String, MergedAnnotation<?>> closest = closestKnown(handlerMethod.getMethod());
        closestKnown(handlerMethod.getBeanType()).forEach(closest::putIfAbsent);
        return labelsOf(closest);
    }

    static List<String> labelsOf(AnnotatedElement element) {
        return labelsOf(closestKnown(element));
    }

    private static List<String> labelsOf(Map<String, MergedAnnotation<?>> closest) {
        Set<String> labels = new LinkedHashSet<>();

        MergedAnnotation<?> preAuthorize = closest.get(PRE_AUTHORIZE);
        if (preAuthorize != null) {
            addExpression(labels, preAuthorize.getString(MergedAnnotation.VALUE));
        }
        for (String guard : ROLE_ARRAY_GUARDS) {
            addRoleNames(labels, closest.get(guard), simpleName(guard));
        }
        for (Map.Entry<String, String> marker : MARKERS.entrySet()) {
            if (closest.containsKey(marker.getKey())) {
                labels.add(marker.getValue());
            }
        }
        return List.copyOf(labels);
    }

    /**
     * 每种守卫注解只取「离元素最近的那一份」。
     *
     * <p>{@link SearchStrategy#TYPE_HIERARCHY} 的迭代顺序就是由近及远，于是 {@code putIfAbsent}
     * 天然实现了「方法级覆盖类级」，与 Spring Security 的解析口径一致。
     */
    private static Map<String, MergedAnnotation<?>> closestKnown(AnnotatedElement element) {
        Map<String, MergedAnnotation<?>> closest = new LinkedHashMap<>();
        for (MergedAnnotation<Annotation> annotation :
                MergedAnnotations.from(element, SearchStrategy.TYPE_HIERARCHY)) {
            String type = annotation.getType().getName();
            if (KNOWN.contains(type)) {
                closest.putIfAbsent(type, annotation);
            }
        }
        return closest;
    }

    /**
     * 解析一条 {@code @PreAuthorize} 表达式。
     *
     * <p>规则是「要么全懂，要么不猜」：顶层含 {@code or} / {@code ||} → 整条原样；{@code and} 的合取项里
     * 有任何一项不认识 → 整条原样。只有全部合取项都认识时才逐个出标签（合取＝都要求，并集不误放宽）。
     */
    static void addExpression(Set<String> labels, String expression) {
        String raw = expression == null ? "" : expression.trim();
        if (raw.isEmpty() || splitTopLevel(raw, true).size() > 1) {
            labels.add(EXPRESSION_PREFIX + raw);
            return;
        }
        List<String> conjuncts = splitTopLevel(raw, false);
        List<String> derived = new ArrayList<>(conjuncts.size());
        for (String conjunct : conjuncts) {
            String label = labelOfPredicate(conjunct.trim());
            if (label == null) {
                labels.add(EXPRESSION_PREFIX + raw);
                return;
            }
            derived.add(label);
        }
        labels.addAll(derived);
    }

    /** 单个白名单谓词 → 标签；不认识返回 {@code null}，由调用方决定整条兜底。 */
    private static String labelOfPredicate(String predicate) {
        int open = predicate.indexOf('(');
        if (open <= 0 || !predicate.endsWith(")")) {
            return null;
        }
        String method = predicate.substring(0, open).trim();
        String argument = predicate.substring(open + 1, predicate.length() - 1).trim();
        return switch (method) {
            case "authenticated", "isAuthenticated" -> argument.isEmpty() ? AUTHENTICATED : null;
            case "permitAll" -> argument.isEmpty() ? PERMIT_ALL : null;
            case "denyAll" -> argument.isEmpty() ? DENY_ALL : null;
            // hasRole 与 hasAuthority 一律给注解里的字面值：加工前缀会把 hasRole('ADMIN') 与
            // hasRole('ROLE_ADMIN') 这两种不同要求悄悄归一，那是读错。
            // hasAnyRole / hasAnyAuthority 是或语义，拆开会放宽归类，落到 default 走整条兜底。
            case "hasRole", "hasAuthority" -> labelFromLiteral(unquote(argument));
            default -> null;
        };
    }

    /** {@code @Secured} / {@code @RolesAllowed} 的值是角色名数组；多值时不猜它是或还是与，原样兜底。 */
    private static void addRoleNames(Set<String> labels, MergedAnnotation<?> annotation, String displayName) {
        if (annotation == null) {
            return;
        }
        String[] values = annotation.getStringArray(MergedAnnotation.VALUE);
        if (values.length == 1) {
            String label = labelFromLiteral(values[0].trim());
            if (label != null) {
                labels.add(label);
                return;
            }
        }
        List<String> rendered = new ArrayList<>(values.length);
        for (String value : values) {
            rendered.add(value.trim());
        }
        labels.add(EXPRESSION_PREFIX + "@" + displayName + "({" + String.join(", ", rendered) + "})");
    }

    private static String labelFromLiteral(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 只接受「一整个带引号的字面量」；拼接、嵌套引号、方法调用一律不算。 */
    private static String unquote(String argument) {
        if (argument.length() < 2) {
            return null;
        }
        char quote = argument.charAt(0);
        if ((quote != '\'' && quote != '"') || argument.charAt(argument.length() - 1) != quote) {
            return null;
        }
        String inner = argument.substring(1, argument.length() - 1);
        return inner.indexOf(quote) >= 0 ? null : inner.trim();
    }

    /**
     * 按顶层（引号外）的 {@code &&}/{@code and} 或 {@code ||}/{@code or} 切分。
     *
     * <p>引号里的分隔符不能算：{@code hasAuthority('a and b')} 是一个权限名，不是两个合取项。
     * 引号不闭合时整串留在一个分段里，最终会因为不认识而走兜底，不会切错。
     */
    private static List<String> splitTopLevel(String expression, boolean orMode) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        char quote = 0;
        for (int index = 0; index < expression.length(); index++) {
            char current = expression.charAt(index);
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            int length = separatorLength(expression, index, orMode);
            if (length > 0) {
                parts.add(expression.substring(start, index));
                index += length - 1;
                start = index + 1;
            }
        }
        parts.add(expression.substring(start));
        return parts;
    }

    /** 命中分隔符则返回其长度，否则返回 0。单词形式必须两侧都是空白，避免咬到 {@code standard()} 这类标识符。 */
    private static int separatorLength(String expression, int at, boolean orMode) {
        String symbol = orMode ? "||" : "&&";
        if (expression.startsWith(symbol, at)) {
            return symbol.length();
        }
        String word = orMode ? "or" : "and";
        int end = at + word.length();
        if (end > expression.length() || !expression.regionMatches(true, at, word, 0, word.length())) {
            return 0;
        }
        boolean leftBounded = at == 0 || Character.isWhitespace(expression.charAt(at - 1));
        boolean rightBounded = end == expression.length() || Character.isWhitespace(expression.charAt(end));
        return leftBounded && rightBounded ? word.length() : 0;
    }

    private static Map<String, String> markerMap() {
        Map<String, String> markers = new LinkedHashMap<>();
        markers.put("jakarta.annotation.security.PermitAll", PERMIT_ALL);
        markers.put("javax.annotation.security.PermitAll", PERMIT_ALL);
        markers.put("jakarta.annotation.security.DenyAll", DENY_ALL);
        markers.put("javax.annotation.security.DenyAll", DENY_ALL);
        return Collections.unmodifiableMap(markers);
    }

    private static Set<String> knownNames() {
        Set<String> names = new LinkedHashSet<>();
        names.add(PRE_AUTHORIZE);
        names.addAll(ROLE_ARRAY_GUARDS);
        names.addAll(MARKERS.keySet());
        return Set.copyOf(names);
    }

    private static String simpleName(String annotationType) {
        int dot = annotationType.lastIndexOf('.');
        return dot < 0 ? annotationType : annotationType.substring(dot + 1);
    }
}
