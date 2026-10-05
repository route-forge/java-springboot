package io.github.routeforge.core.tier;

import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.exception.ClassifierException;
import io.github.routeforge.core.exception.RouteMissingNameException;
import io.github.routeforge.core.exception.RouteTierNotAssignedException;
import io.github.routeforge.core.exception.UnknownClassifierTierException;
import io.github.routeforge.core.exception.UnknownLevelException;
import io.github.routeforge.core.support.WarningSink;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 层级分配器：按 SPEC §3.1.4 的优先级决定一条路由最终归属的层级。
 *
 * <p>优先级（高 → 低）：
 * <ol>
 *   <li>显式 tier 标注（适配层提取的 {@code RouteInfo.tier}，含注解与类/包级继承）</li>
 *   <li>{@link RouteClassifier} 回调返回非空字符串</li>
 *   <li>配置 match 规则（prefix / middleware，{@code middleware_match} 支持 any/all/DNF；
 *       多层级同时命中取最后一个 = last-wins）</li>
 *   <li>未命中：非严格模式归入 {@code unassigned}（返回 {@code null}）；严格模式抛
 *       {@link RouteTierNotAssignedException}</li>
 * </ol>
 *
 * <p>逐分支与 PHP 侧 {@code RouteForge\Common\Tier\TierResolver} 对齐，包括几处容易被「顺手改掉」的细节：
 * <ul>
 *   <li>{@code prefix !== ''} 是严格比较——只有空字符串前缀被跳过，数字/布尔前缀按 PHP 字符串化后参与比较；</li>
 *   <li>{@code in_array(..., true)} 是严格类型比较——配置写成数字的中间件永不命中字符串中间件；</li>
 *   <li>{@code middleware_match} 的类型守卫在 prefix 循环<b>之前</b>执行，因此无效类型的告警按
 *       「路由 × 层级」出现，且该层级没配 middleware 也一样告警。</li>
 * </ul>
 */
public final class TierResolver {

    private final LevelsConfig levels;
    private final RouteClassifier classifier;
    private final boolean strictMode;
    private final WarningSink warnings;

    public TierResolver(LevelsConfig levels) {
        this(levels, null, false, WarningSink.NOOP);
    }

    /**
     * @param levels     层级配置表（声明顺序即 last-wins 优先级）
     * @param classifier 自定义分类回调，可为 null
     * @param strictMode 严格模式：未归级与「有 tier 无 name」都判配置错误
     * @param warnings   非致命问题出口，null 等价 {@link WarningSink#NOOP}
     */
    public TierResolver(LevelsConfig levels, RouteClassifier classifier, boolean strictMode, WarningSink warnings) {
        this.levels = levels;
        this.classifier = classifier;
        this.strictMode = strictMode;
        this.warnings = warnings == null ? WarningSink.NOOP : warnings;
    }

    /**
     * 解析一条路由的最终层级。
     *
     * @return 层级名；{@code null} 表示归入 {@code unassigned} 特殊层级
     * @throws RouteMissingNameException      严格模式 + 设了 tier 但没有路由名（RF_BE_005）
     * @throws UnknownLevelException         显式 tier 值不在 levels 配置中（RF_BE_002）
     * @throws ClassifierException            classifier 回调自身抛错（RF_BE_004）
     * @throws UnknownClassifierTierException classifier 返回的层级名不在 levels 配置中（RF_BE_006）
     * @throws RouteTierNotAssignedException  严格模式且未命中任何层级（RF_BE_001）
     */
    public String resolve(RouteInfo route) {
        String explicit = route.tier();

        // 前置守卫：有 tier 无 name —— 无名路由进不了任何元信息，严格模式下算配置错误
        if (hasText(explicit) && route.name() == null) {
            if (strictMode) {
                throw new RouteMissingNameException("Route (" + route.uri() + ") has tier [" + explicit
                        + "] but no route name assigned. Route Forge requires a route name when tier is set. "
                        + "Add ->name(...) to the route or remove the tier.");
            }
            // 非严格模式：记 warning。否则该路由在层级端点、unassigned、list/types 里全部消失，
            // 而 RF_BE_005 只在严格模式抛——这条路径是它唯一的信号。
            warnings.warning("Route (" + route.uri() + ") has tier [" + explicit
                    + "] but no route name assigned; it will not appear in any forge endpoint or command output. "
                    + "Add ->name(...) to the route or remove the tier.");
            return null;
        }

        // 1：显式 tier 标注
        if (hasText(explicit)) {
            if (!levels.containsLevel(explicit)) {
                throw new UnknownLevelException("Route " + displayName(route) + " has tier [" + explicit
                        + "] which is not defined in levels config. Available levels: "
                        + String.join(", ", levels.names()));
            }
            return explicit;
        }

        // 2：classifier 回调
        if (classifier != null) {
            String result = classifierTierOrThrow(route);
            if (result != null) {
                if (!levels.containsLevel(result)) {
                    throw new UnknownClassifierTierException("Classifier returned unknown tier [" + result
                            + "] for route " + displayName(route) + ". Available tiers: "
                            + String.join(", ", levels.names()));
                }
                return result;
            }
        }

        // 3：配置 match 规则（last-wins）
        String matched = matchedTier(route);
        if (matched != null) {
            return matched;
        }

        // 4：兜底
        if (strictMode) {
            // 名字位刻意保留 PHP 的 null 插值形态（未命名时是「Route  (uri)」两个空格）：
            // 该文案会被命令行与 HTTP 错误体共用，跨语言对等测试按整句比对。
            throw new RouteTierNotAssignedException("Route " + (route.name() == null ? "" : route.name())
                    + " (" + route.uri() + ") has no tier assigned. "
                    + "Add ->tier(...) to the route or a match rule in config/forge.php, "
                    + "or disable strict_mode.");
        }
        return null;
    }

    /**
     * 只读层级探测：与 {@link #resolve} 同一套优先级求值，但<b>任何情况下都不抛异常</b>。
     *
     * <p>用途是「未命名路由」的可见性归类。这类路由不会进 rows / tier_counts / 端点元信息 / d.ts
     * （无名即无法被 {@code url()} 解析），只服务于 {@code list} 的告警与 {@code --unnamed} 视图。
     * 若沿用 {@code resolve()}，严格模式下会因 RF_BE_001/002/004/005/006 让命令直接以退出码 1 崩掉，
     * 恰好把用户最想看的表格一起带走——所以所有失败在这里降级为 {@link TierProbe#source()} 标记，
     * 不新增任何失败模式。
     *
     * <p>一致性约定（由测试断言）：{@code resolve()} 未抛异常时，本方法给出的 level 必须与之完全相同。
     *
     * <p>本方法不含「有 tier 无 name」守卫：层级归属与是否命名是两件独立的事，无名是调用方已知前提，
     * 文案由命令层组合。
     */
    public TierProbe probe(RouteInfo route) {
        String explicit = route.tier();

        if (hasText(explicit)) {
            return levels.containsLevel(explicit)
                    ? new TierProbe(explicit, TierProbe.Source.EXPLICIT, null, null)
                    : new TierProbe(null, TierProbe.Source.EXPLICIT_UNKNOWN_LEVEL, explicit, null);
        }

        if (classifier != null) {
            String result;
            try {
                result = classifierTier(classifier.classify(route));
            } catch (Throwable t) {
                return new TierProbe(null, TierProbe.Source.CLASSIFIER_ERROR, null,
                        t.getClass().getName() + ": " + t.getMessage());
            }

            if (result != null) {
                return levels.containsLevel(result)
                        ? new TierProbe(result, TierProbe.Source.CLASSIFIER, null, null)
                        : new TierProbe(null, TierProbe.Source.CLASSIFIER_UNKNOWN_TIER, result, null);
            }
        }

        String matched = matchedTier(route);
        return matched != null
                ? new TierProbe(matched, TierProbe.Source.MATCH, null, null)
                : new TierProbe(null, TierProbe.Source.NONE, null, null);
    }

    /** 已配置层级名（声明顺序）。命令层与分析器共用，避免各处自读配置导致排序口径漂移。 */
    public List<String> configuredLevels() {
        return levels.names();
    }

    /**
     * 是否启用严格模式。仓库与分析器据此决定是否跑违规预扫描——它们不该再各自读一遍配置，
     * 否则会出现「解析器按 A 口径抛错、命令层按 B 口径展示」的分叉。
     */
    public boolean isStrict() {
        return strictMode;
    }

    // ---------------------------------------------------------------- 内部实现

    private String classifierTierOrThrow(RouteInfo route) {
        try {
            return classifierTier(classifier.classify(route));
        } catch (UnknownClassifierTierException e) {
            // 回调自己抛出的精确层级错误不被重包装：它比 RF_BE_004 更有指错价值
            throw e;
        } catch (Throwable e) {
            throw new ClassifierException("Classifier callback threw: " + e.getMessage(), e);
        }
    }

    /** 回调结果只在「非空字符串」时视为表态；null、空串、非字符串一律算未表态。 */
    private static String classifierTier(Object rawResult) {
        return rawResult instanceof String s && !s.isEmpty() ? s : null;
    }

    /** 按配置顺序逐层判定，多层命中取最后一个（last-wins）。 */
    private String matchedTier(RouteInfo route) {
        String matched = null;
        for (String level : levels.names()) {
            if (matchesLevel(route, levels.matchOf(level))) {
                matched = level;
            }
        }
        return matched;
    }

    /** 单个层级的 match 判定：prefix 与 middleware 之间是 OR；两者皆空则不命中。 */
    private boolean matchesLevel(RouteInfo route, Map<String, Object> match) {
        List<Object> prefixes = toList(match.get("prefix"));
        List<Object> middlewares = toList(match.get("middleware"));
        Object middlewareMatch = normalizeMiddlewareMatch(route, match.get("middleware_match"));

        boolean prefixHit = false;
        for (Object prefix : prefixes) {
            if ("".equals(prefix)) {
                continue;
            }
            String text = phpString(prefix);
            boolean exact = prefix instanceof String && route.uri().equals(prefix);
            if (exact || route.uri().startsWith(text + "/")) {
                prefixHit = true;
                break;
            }
        }

        boolean middlewareHit = !middlewares.isEmpty()
                && matchesMiddleware(route.middleware(), middlewares, middlewareMatch);

        if (prefixes.isEmpty() && middlewares.isEmpty()) {
            return false;
        }
        return prefixHit || middlewareHit;
    }

    /**
     * {@code middleware_match} 类型守卫：只接受字符串（any/all，含未知字符串的既有降级路径）与
     * 数组（DNF）；缺键与 null 走默认 {@code any}（PHP 的 {@code ??} 同判据）；其余类型回落 any 并告警。
     */
    private Object normalizeMiddlewareMatch(RouteInfo route, Object value) {
        if (value == null) {
            return "any";
        }
        if (value instanceof String || value instanceof Collection || value.getClass().isArray()) {
            return value;
        }
        warnings.warning("Route (" + route.uri() + ") matched a level whose middleware_match rule has invalid type ["
                + phpDebugType(value) + "]; expected string (\"any\"/\"all\") or DNF array. Falling back to \"any\".");
        return "any";
    }

    /** 中间件匹配模式实现（any / all / DNF）。 */
    private boolean matchesMiddleware(List<String> routeMiddlewares, List<Object> middlewares, Object middlewareMatch) {
        if ("any".equals(middlewareMatch)) {
            return middlewares.stream().anyMatch(routeMiddlewares::contains);
        }

        if ("all".equals(middlewareMatch)) {
            return !middlewares.isEmpty() && routeMiddlewares.containsAll(middlewares);
        }

        Collection<?> clauses = asClauses(middlewareMatch);
        if (clauses != null) {
            return clauses.stream().anyMatch(clause -> dnfClauseHit(routeMiddlewares, middlewares, clause));
        }

        // 未知字符串等值降级为 any
        return matchesMiddleware(routeMiddlewares, middlewares, "any");
    }

    private static Collection<?> asClauses(Object middlewareMatch) {
        if (middlewareMatch instanceof Collection<?> c) {
            return c;
        }
        if (middlewareMatch instanceof Object[] array) {
            return List.of(array);
        }
        return null;
    }

    /** 单个 DNF 子句（AND 组）：非数组与空子句按不表态跳过；越界索引令该子句不满足。 */
    private static boolean dnfClauseHit(List<String> routeMiddlewares, List<Object> middlewares, Object clause) {
        Collection<?> conjunction = asClauses(clause);
        if (conjunction == null || conjunction.isEmpty()) {
            return false;
        }
        for (Object rawIndex : conjunction) {
            int index = phpInt(rawIndex);
            // PHP isset($middlewares[$idx]) 同判据：索引不存在或值为 null 都算缺
            if (index < 0 || index >= middlewares.size()) {
                return false;
            }
            Object configured = middlewares.get(index);
            if (configured == null || !routeMiddlewares.contains(configured)) {
                return false;
            }
        }
        return true;
    }

    /**
     * PHP {@code (array)} casts：null → 空表；集合/数组原样保序；标量 → 单元素表。
     *
     * <p>用 {@link Collections#unmodifiableList} 而不是 {@code List.copyOf}：配置里写 {@code prefix: [null]}
     * 是 PHP 侧允许的畸形输入（且 null 元素有自己的匹配语义），{@code List.copyOf} 会直接 NPE——
     * 这会把「宽容归一 + 告警」的行为换成「崩在解析阶段」，正是跨语言对等要避免的静默分叉。
     */
    private static List<Object> toList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> c) {
            return Collections.unmodifiableList(new ArrayList<>(c));
        }
        if (value instanceof Object[] array) {
            return Collections.unmodifiableList(Arrays.asList(array));
        }
        return List.of(value);
    }

    /** PHP 字符串化语义：null → ""、true → "1"、false → ""。 */
    private static String phpString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Boolean b) {
            return b ? "1" : "";
        }
        return String.valueOf(value);
    }

    /** PHP {@code (int)} casts：null/非数字串 → 0、布尔 → 0/1、浮点向零截断、字符串取前导数字。 */
    private static int phpInt(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1 : 0;
        }
        String text = String.valueOf(value).trim();
        int start = 0;
        if (!text.isEmpty() && (text.charAt(0) == '+' || text.charAt(0) == '-')) {
            start = 1;
        }
        int end = start;
        while (end < text.length() && Character.isDigit(text.charAt(end))) {
            end++;
        }
        if (end == start) {
            return 0;
        }
        try {
            return Integer.parseInt(text.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 近似 PHP {@code get_debug_type()}：标量给类型名，对象给 FQCN。 */
    private static String phpDebugType(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            return "int";
        }
        if (value instanceof Double || value instanceof Float) {
            return "float";
        }
        return value.getClass().getName();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static String displayName(RouteInfo route) {
        return route.name() != null ? route.name() : "(" + route.uri() + ")";
    }
}
