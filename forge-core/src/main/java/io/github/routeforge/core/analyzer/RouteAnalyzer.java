package io.github.routeforge.core.analyzer;

import io.github.routeforge.core.alias.AliasResolution;
import io.github.routeforge.core.alias.AliasResolver;
import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.exception.RouteTierNotAssignedException;
import io.github.routeforge.core.filter.RouteNameFilter;
import io.github.routeforge.core.repository.RouteRepository;
import io.github.routeforge.core.support.HttpMethods;
import io.github.routeforge.core.support.StrictViolationScanner;
import io.github.routeforge.core.support.StrictViolationScanner.Violations;
import io.github.routeforge.core.tier.TierProbe;
import io.github.routeforge.core.tier.TierResolver;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 路由表分析器：一次遍历把全量命名路由（含别名行）归一化为结构化结果，
 * 供 {@code --forge:list} / {@code --forge:types} 等命令消费——命令层只负责渲染。
 *
 * <p>输出契约：
 * <ul>
 *   <li>{@code rows}：命名路由行 + 别名行。别名跟随目标层级；目标以同名多次注册命中多个层级时
 *       每层级各铺一行（与层级端点的别名注入严格一致，否则 d.ts 会漏掉别名类型）；</li>
 *   <li>{@code tierCounts}：<b>过滤前</b>统计，含 unassigned；别名计入目标层级且只计一次（末次注册层级），
 *       与摘要 {@code route_count} 同口径；</li>
 *   <li>{@code warnings}：非致命配置问题（别名撞车 / 未命名路由被层级命中 / 同名跨层级重复注册）。
 *       <b>未命名文案的单点在此</b>，两个适配包共用一份，不各写一份；</li>
 *   <li>{@code unnamed}：全量未命名路由（含未命中任何层级者）。它不进 warnings——
 *       否则「warnings 非空即失败」的 CI 门禁会被与 forge 无关的路由长期打断；只由 {@code --unnamed}
 *       视图消费；</li>
 *   <li>{@code violations}：严格模式违规全量清单；宽松模式下恒为空（未归级在宽松模式是合法状态）。</li>
 * </ul>
 *
 * <p>与 PHP 侧的两处形态差异（均为 Java 类型系统带来的简化，不改语义）：
 * 命令层传入的层级名一律是 {@code List<String>}，不需要 PHP {@code levelNames()} 那种
 * 「既收 list 又收 map」的双形态兼容；输入一律是已归一化的 {@link RouteInfo} 列表，
 * 不需要 {@code analyzeRoutes()} 便捷入口。
 */
public final class RouteAnalyzer {

    /**
     * 未命名路由的两个虚拟分组键：只用于分组排序与标签，不是真实层级名。
     * NUL 前缀保证任何真实层级名都不可能撞上；展示时分别映射为 unassigned / unresolved。
     */
    private static final String GROUP_NONE = "\0none";
    private static final String GROUP_INVALID = "\0invalid";

    private final TierResolver tierResolver;
    private final AliasResolver aliasResolver;
    private final RouteNameFilter filter;

    public RouteAnalyzer(TierResolver tierResolver, AliasResolver aliasResolver, RouteNameFilter filter) {
        this.tierResolver = tierResolver;
        this.aliasResolver = aliasResolver;
        this.filter = filter;
    }

    /**
     * 一条未命名路由的结构化数据。
     *
     * @param level     探测到的层级归属；无归属为 null
     * @param source    归级来源（{@link TierProbe.Source} 的 wire 名）
     * @param tier      路由自身的层级标记原值
     * @param requested 层级名校验失败时的原始值
     * @param error     classifier 抛错时的原始消息
     */
    public record Unnamed(String uri, List<String> methods, List<String> middleware, String level, String source,
            String tier, String requested, String error) {

        public Unnamed {
            methods = List.copyOf(methods);
            middleware = List.copyOf(middleware);
        }

        /** 虚拟分组键：未命中任何层级 → GROUP_NONE；层级名无效/classifier 抛错 → GROUP_INVALID。 */
        String groupKey() {
            if (level != null) {
                return level;
            }
            return "none".equals(source) ? GROUP_NONE : GROUP_INVALID;
        }

        /** 与 PHP 侧 unnamed 条目的键序一致（命令行 --json 之外，--unnamed 视图与管理器也共用这一份形态）。 */
        public Map<String, Object> asMap() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("uri", uri);
            row.put("methods", methods);
            row.put("middleware", middleware);
            row.put("level", level);
            row.put("source", source);
            row.put("tier", tier);
            row.put("requested", requested);
            row.put("error", error);
            return row;
        }
    }

    /** 分析结果。 */
    public record Analysis(List<Row> rows, Map<String, Integer> tierCounts, List<String> warnings,
            Map<String, String> aliases, Map<String, String> collisions, List<Unnamed> unnamed,
            Violations violations) {

        public Analysis {
            // 一律用 LinkedHashMap 保序：Map.copyOf 返回的是「不保证顺序」的不可变映射，
            // 会把 tier_counts / aliases 的插入顺序打散——而键顺序正是命令行与管理器产物的契约部分
            // （tier_counts 按路由注册顺序、aliases 按声明顺序）。
            rows = List.copyOf(rows);
            tierCounts = preserveOrder(tierCounts);
            warnings = List.copyOf(warnings);
            aliases = preserveOrder(aliases);
            collisions = preserveOrder(collisions);
            unnamed = List.copyOf(unnamed);
        }

        private static <K, V> Map<K, V> preserveOrder(Map<K, V> source) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }

    /**
     * 分析全量路由信息。
     *
     * <p>{@code TierResolver.resolve()} 抛出的 {@code RF_BE_002/004/006} 原样向上传播（配置歧义必须中断）；
     * 只有严格模式的「未归级」被就地吞下并按 unassigned 落位——它进 violations 全量列出，
     * 逐条 fail-fast 会让人修一条刷一条。
     */
    public Analysis analyze(List<RouteInfo> infos) {
        List<Row> rows = new ArrayList<>();
        Map<String, Integer> tierCounts = new LinkedHashMap<>();
        List<String> ambiguityWarnings = new ArrayList<>();
        List<Unnamed> unnamed = new ArrayList<>();
        // 路由名 → 该名的全部注册行：同名可多次注册且分属不同层级，必须全部保留
        Map<String, List<Row>> rowsByName = new LinkedHashMap<>();

        for (RouteInfo info : infos) {
            String name = info.name();

            if (name == null || name.isEmpty()) {
                // 未命名路由只能按 URI 排除：forge 自身层级端点（如 _forge/routes/manage 带 manage 中间件）
                // 会被 match 规则命中，不排除就是包把自己报成配置错误。
                if (filter.isUriExcluded(info.uri())) {
                    continue;
                }
                TierProbe probed = tierResolver.probe(info);
                unnamed.add(new Unnamed(info.uri(), HttpMethods.withoutHead(info.methods()), info.middleware(),
                        probed.level(), probed.source().wireName(), info.tier(), probed.requested(), probed.error()));
                continue;
            }
            if (filter.isNameExcluded(name)) {
                continue;
            }

            String resolved;
            try {
                resolved = tierResolver.resolve(info);
            } catch (RouteTierNotAssignedException e) {
                resolved = null;
            }
            String level = resolved == null ? RouteRepository.UNASSIGNED_LEVEL : resolved;
            tierCounts.merge(level, 1, Integer::sum);

            Row row = Row.ofReal(name, level, info.uri(), info.methods(), info.parameters(),
                    info.parameterDefaults(), info.middleware(), resolved);
            rows.add(row);
            rowsByName.computeIfAbsent(name, key -> new ArrayList<>()).add(row);
        }

        // 同名跨层级重复注册是配置歧义：url() 只解析到末次注册，而别名会出现在它解析到的每个层级
        rowsByName.forEach((name, sameName) -> {
            if (sameName.size() < 2) {
                return;
            }
            List<String> levels = new ArrayList<>(new LinkedHashSet<>(sameName.stream().map(Row::level).toList()));
            if (levels.size() < 2) {
                return; // 层级相同的同名覆盖不产生别名归属歧义
            }
            ambiguityWarnings.add("Route name [" + name + "] is registered " + sameName.size()
                    + " times across tiers (" + String.join(", ", levels) + "); url() resolves to the last "
                    + "registration, while aliases pointing to it appear in every tier it resolves to.");
        });

        AliasResolution aliases = aliasResolver.resolve(infos);
        aliases.aliases().forEach((alias, target) -> {
            List<Row> targetRows = rowsByName.get(target);
            if (targetRows == null || targetRows.isEmpty()) {
                return; // resolver 已保证目标是真实命名路由；防御性跳过
            }
            Row lastRow = targetRows.get(targetRows.size() - 1);
            tierCounts.merge(lastRow.level(), 1, Integer::sum);

            List<String> emittedLevels = new ArrayList<>();
            for (Row targetRow : targetRows) {
                if (emittedLevels.contains(targetRow.level())) {
                    continue;
                }
                emittedLevels.add(targetRow.level());
                rows.add(targetRow.asAliasOf(alias, target));
            }
        });

        // 宽松模式不计算 violations：unassigned 是合法状态，非空清单会被误读成配置错误
        Violations violations = tierResolver.isStrict()
                ? new StrictViolationScanner(tierResolver, filter).scan(infos)
                : new Violations(List.of(), List.of(), List.of());

        List<String> warnings = new ArrayList<>(aliases.warnings());
        warnings.addAll(unnamedWarnings(unnamed, tierResolver.configuredLevels()));
        warnings.addAll(ambiguityWarnings);

        return new Analysis(rows, tierCounts, List.copyOf(warnings), aliases.aliases(), aliases.collisions(),
                unnamed, violations);
    }

    /**
     * 未命名路由的告警文案（进 warnings 通道：{@code --json} 的 warnings、table 模式表格前的警告行、
     * types 的 stderr）。
     *
     * <p>只收「被层级规则命中却没有路由名」的条目——这类才是真实配置错误：它本该出现在端点元信息与
     * d.ts 里，却因无名凭空消失。未命中任何层级的未命名路由本就不需要名字，不进 warnings。
     */
    public static List<String> unnamedWarnings(List<Unnamed> unnamed, List<String> levels) {
        List<String> out = new ArrayList<>();
        for (List<Unnamed> entries : groupByLevel(unnamed, levels).values()) {
            for (Unnamed entry : entries) {
                if (entry.level() == null && "none".equals(entry.source())) {
                    continue;
                }
                out.add(describeUnnamed(entry));
            }
        }
        return out;
    }

    /**
     * {@code --unnamed} 视图：全量未命名路由（含未命中任何层级者）按层级分组的可读清单。
     *
     * <p>命令层约定：走本视图时不再打印表格与 warnings，避免同一事实双写。
     *
     * @param levelFilter 仅看该分组（{@code --level} 组合）；也接受虚拟分组 unassigned / unresolved
     */
    public static List<String> formatUnnamed(List<Unnamed> unnamed, List<String> levels, String levelFilter) {
        if (unnamed.isEmpty()) {
            return List.of("No unnamed routes: every route in the table has a name.");
        }

        List<String> out = new ArrayList<>();
        int total = 0;
        for (Map.Entry<String, List<Unnamed>> group : groupByLevel(unnamed, levels).entrySet()) {
            if (levelFilter != null && !levelFilter.isEmpty() && !groupLabel(group.getKey()).equals(levelFilter)) {
                continue;
            }
            out.add(group.getValue().size() + " unnamed route(s) in group [" + groupLabel(group.getKey()) + "]:");
            group.getValue().forEach(entry -> out.add("  - " + describeUnnamed(entry)));
            total += group.getValue().size();
        }
        out.add(0, total + " unnamed route(s) total — they cannot be referenced by url() "
                + "and appear in no forge endpoint, type map or route list.");
        return out;
    }

    /** 按命令行条件筛行。 */
    public static List<Row> filterRows(List<Row> rows, String level, boolean onlyUnassigned, boolean onlyAliases) {
        List<Row> out = new ArrayList<>();
        for (Row row : rows) {
            if (level != null && !level.isEmpty() && !level.equals(row.level())) {
                continue;
            }
            // --unassigned 判定看 tier 而非 level：level 已被兜底成字符串，看不出真实归属
            if (onlyUnassigned && row.tier() != null) {
                continue;
            }
            if (onlyAliases && row.aliasOf() == null) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 组装 {@code --forge:list --json} 的产物（SPEC §3.2 契约）。
     *
     * <p>结构、键序与口径变化只允许发生在本方法：命令层只做序列化与打印。
     */
    public static Map<String, Object> listPayload(List<String> levels, List<Row> rows, Map<String, Integer> tierCounts,
            List<String> warnings, String level, boolean onlyUnassigned, boolean onlyAliases) {
        Map<String, Object> filterDesc = new LinkedHashMap<>();
        if (level != null && !level.isEmpty()) {
            filterDesc.put("level", level);
        }
        if (onlyUnassigned) {
            filterDesc.put("unassigned", true);
        }
        if (onlyAliases) {
            filterDesc.put("aliases", true);
        }

        // 层级汇总：全部已配置层级 + unassigned（0 也列出），顺序 = 配置顺序
        Map<String, Integer> orderedCounts = new LinkedHashMap<>();
        for (String name : levels) {
            orderedCounts.put(name, tierCounts.getOrDefault(name, 0));
        }
        orderedCounts.put(RouteRepository.UNASSIGNED_LEVEL, tierCounts.getOrDefault(RouteRepository.UNASSIGNED_LEVEL, 0));

        List<Map<String, Object>> routeViews = new ArrayList<>();
        for (Row row : rows) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", row.name());
            view.put("level", row.level());
            view.put("methods", HttpMethods.withoutHead(row.methods()));
            view.put("uri", row.uri());
            view.put("alias_of", row.aliasOf());
            routeViews.add(view);
        }

        List<String> allLevels = new ArrayList<>(levels);
        allLevels.add(RouteRepository.UNASSIGNED_LEVEL);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("levels", allLevels);
        payload.put("filter", filterDesc.isEmpty() ? null : filterDesc);
        payload.put("count", rows.size());
        payload.put("tier_counts", orderedCounts);
        payload.put("warnings", warnings);
        payload.put("routes", routeViews);
        return payload;
    }

    // ---------------------------------------------------------------- 分组与文案

    /**
     * 未命名路由按层级分组：配置层级顺序优先，然后两个虚拟分组（invalid 先于 none），
     * 最后才是未列出的真实层级（按名排序）；组内按 (uri, source) 字典序，保证输出稳定可断言。
     *
     * <p>末位「未列出的真实层级」在实践中取不到值（探测给出的层级必然来自配置，未归级走 GROUP_NONE），
     * 但顺序按 PHP 实现的真实行为保留——PHP 侧那里的注释写的是「配置 → 其余层级 → 虚拟分组」，
     * 代码实际是「配置 → 虚拟分组 → 其余层级」，本次照代码不照注释。
     */
    private static Map<String, List<Unnamed>> groupByLevel(List<Unnamed> unnamed, List<String> levels) {
        Map<String, List<Unnamed>> groups = new LinkedHashMap<>();
        for (Unnamed entry : unnamed) {
            groups.computeIfAbsent(entry.groupKey(), key -> new ArrayList<>()).add(entry);
        }
        Comparator<Unnamed> byUriThenSource = Comparator.comparing(Unnamed::uri).thenComparing(Unnamed::source);
        groups.values().forEach(members -> members.sort(byUriThenSource));

        Map<String, List<Unnamed>> ordered = new LinkedHashMap<>();
        for (String name : levels) {
            if (groups.containsKey(name)) {
                ordered.put(name, groups.remove(name));
            }
        }
        for (String virtual : List.of(GROUP_INVALID, GROUP_NONE)) {
            if (groups.containsKey(virtual)) {
                ordered.put(virtual, groups.remove(virtual));
            }
        }
        new ArrayList<>(groups.keySet()).stream().sorted().forEach(key -> ordered.put(key, groups.remove(key)));
        return ordered;
    }

    private static String groupLabel(String group) {
        return switch (group) {
            case GROUP_NONE -> RouteRepository.UNASSIGNED_LEVEL;
            case GROUP_INVALID -> "unresolved";
            default -> group;
        };
    }

    /**
     * 单条未命名路由的描述。
     *
     * <p>主体必须是「{@code Route (uri) …} + 层级归属 + 修法指路」，HTTP 方法与中间件作为<b>句尾标签</b>
     * 追加——主体要与历史文案逐字连续，下游按整句 grep / substring 断言的脚本才不会失效；
     * 精确整行比对的脚本会看到多出的方法列（PHP 侧同行为）。
     */
    private static String describeUnnamed(Unnamed entry) {
        String subject = "Route (" + entry.uri() + ")";
        String line = switch (entry.source()) {
            case "explicit" -> subject + " has tier [" + entry.level() + "] but no route name assigned; "
                    + "it will not appear in any forge endpoint or command output. "
                    + "Add ->name(...) to the route or remove the tier.";
            case "match" -> subject + " is assigned to level [" + entry.level() + "] by a config match rule "
                    + "but has no route name assigned; it will not appear in any forge endpoint or command output. "
                    + "Add ->name(...) to the route or narrow level [" + entry.level() + "]'s match rule.";
            case "classifier" -> subject + " is assigned to level [" + entry.level() + "] by the classifier callback "
                    + "but has no route name assigned; it will not appear in any forge endpoint or command output. "
                    + "Add ->name(...) to the route or make the classifier return null for it.";
            case "explicit-unknown-level" -> subject + " declares tier [" + entry.requested() + "] which is not "
                    + "defined in levels config, and has no route name assigned; it is invisible to every "
                    + "forge output. Fix the tier name and add ->name(...), or remove the tier.";
            case "classifier-unknown-tier" -> subject + " gets tier [" + entry.requested() + "] from the classifier "
                    + "callback, which is not defined in levels config, and has no route name assigned; it is "
                    + "invisible to every forge output. Fix the classifier result and add ->name(...).";
            case "classifier-error" -> subject + " could not be resolved because the classifier callback threw: "
                    + entry.error() + " — and it has no route name, so it appears in no forge output. "
                    + "Fix the classifier or add ->name(...).";
            default -> subject + " is not matched by any tier rule and has no route name; naming is "
                    + "optional while it stays outside every level.";
        };

        List<String> tags = new ArrayList<>();
        if (!entry.methods().isEmpty()) {
            tags.add(String.join("|", entry.methods()));
        }
        if (!entry.middleware().isEmpty()) {
            tags.add("middleware: " + String.join(", ", entry.middleware()));
        }
        return tags.isEmpty() ? line : line + " [" + String.join("; ", tags) + "]";
    }
}
