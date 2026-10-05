package io.github.routeforge.core.support;

import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.filter.RouteNameFilter;
import io.github.routeforge.core.tier.TierProbe;
import io.github.routeforge.core.tier.TierResolver;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 严格模式违规扫描：一次遍历把「本该进入 forge 元信息却进不去」的路由分成三类收集，
 * 供 HTTP 端点（聚合抛 RF_BE_009）与命令行（红色清单）共用<b>同一份</b>判定与同一套措辞。
 *
 * <p>三类：
 * <ul>
 *   <li>{@code missing_name}：会被某一级优先级判入层级（显式 / classifier / config match）却没有路由名
 *       ——无名即无法被 {@code url()} 引用，它会从层级端点、摘要计数、d.ts 与 list 表格里凭空消失，
 *       是最难排查的一类；</li>
 *   <li>{@code unassigned}：命名路由不命中任何层级规则——严格模式要求每条命名路由必须归级；</li>
 *   <li>{@code unresolved}：层级名拼错 / classifier 抛错等无法判定的路由。<b>仅作信息附录</b>，
 *       不参与 code 决策，它们仍由 {@link TierResolver#resolve} 抛出各自的 RF_BE_002 / 004 / 006。</li>
 * </ul>
 *
 * <p>未命名且不命中任何层级的路由不属于任何一类：它压根不在 forge 管辖范围内
 * （否则框架内部路由会把严格模式报错刷满，「warnings 非空即失败」的 CI 门禁会被永久打断）。
 *
 * <p>只消费 {@link RouteInfo} 与 {@link TierProbe}，不接触任何框架类。结果按实例记忆：
 * 一个扫描器绑定一套路由集合（仓库实例每请求一个），同一次请求内多处重复扫描不重复计算。
 */
public final class StrictViolationScanner {

    private final TierResolver tierResolver;
    private final RouteNameFilter filter;

    /** 结果记忆：一个扫描器绑定一套路由集合（仓库实例每请求一个），同请求内重复扫描不重复计算。 */
    private Violations memo;

    public StrictViolationScanner(TierResolver tierResolver) {
        this(tierResolver, new RouteNameFilter());
    }

    /**
     * @param tierResolver 层级解析器（复用其 {@code probe()} 与配置层级顺序，不各自再读一遍配置）
     * @param filter       路由名 / URI 排除过滤器——包自身层级端点是未命名路由，必须按 URI 排除，
     *                     否则宿主一配 {@code endpoint_middleware}，包就会把自己报成宿主的配置错误
     */
    public StrictViolationScanner(TierResolver tierResolver, RouteNameFilter filter) {
        this.tierResolver = tierResolver;
        this.filter = filter == null ? new RouteNameFilter() : filter;
    }

    /** 严格模式违规清单：三组恒定存在（可为空列表），顺序即渲染顺序。 */
    public record Violations(List<MissingName> missingName, List<Unassigned> unassigned, List<Unresolved> unresolved) {

        public Violations {
            missingName = List.copyOf(missingName);
            unassigned = List.copyOf(unassigned);
            unresolved = List.copyOf(unresolved);
        }

        /** 参与严格模式判定的违规数：<b>不含</b> unresolved（那类由各自精确错误码负责）。 */
        public int count() {
            return missingName.size() + unassigned.size();
        }

        public boolean isEmpty() {
            return count() == 0;
        }

        /** 人读清单（HTTP 错误 message 与命令行红色清单同源同措辞，不各写一份）。 */
        public List<String> format() {
            List<String> lines = new ArrayList<>();
            lines.add("Route Forge strict_mode found " + count() + " route configuration problem(s):");

            if (!missingName.isEmpty()) {
                lines.add("  " + missingName.size() + " route(s) are assigned to a level but have no name,"
                        + " so they cannot be referenced by url() and appear in no endpoint, type map or list row:");
                for (MissingName entry : missingName) {
                    lines.add("    - " + HttpMethods.displayJoin(entry.methods()) + " " + entry.uri()
                            + " -> level [" + entry.level() + "] via " + sourceLabel(entry.source()));
                }
            }

            if (!unassigned.isEmpty()) {
                lines.add("  " + unassigned.size() + " named route(s) are not matched by any level rule"
                        + " (add ->tier(...), a match rule in config, or let the classifier return a level):");
                for (Unassigned entry : unassigned) {
                    lines.add("    - " + entry.name() + " (" + HttpMethods.displayJoin(entry.methods())
                            + " " + entry.uri() + ")");
                }
            }

            if (!unresolved.isEmpty()) {
                lines.add("  " + unresolved.size() + " route(s) could not be resolved at all"
                        + " (invalid tier name or classifier failure — reported by their own error code):");
                for (Unresolved entry : unresolved) {
                    lines.add("    - " + (entry.name() != null ? entry.name() + " " : "") + "(" + entry.uri() + ") "
                            + entry.reason());
                }
            }
            return List.copyOf(lines);
        }
    }

    /** @param source 层级命中来源（{@link io.github.routeforge.core.tier.TierProbe.Source} 的 wire 名） */
    public record MissingName(String uri, List<String> methods, String level, String source) {

        public MissingName {
            methods = List.copyOf(methods);
        }
    }

    public record Unassigned(String name, String uri, List<String> methods, List<String> middleware) {

        public Unassigned {
            methods = List.copyOf(methods);
            middleware = List.copyOf(middleware);
        }
    }

    public record Unresolved(String name, String uri, String reason) {
    }

    /** 扫描整套路由的严格模式违规（结果按实例记忆）。 */
    public Violations scan(List<RouteInfo> infos) {
        if (memo != null) {
            return memo;
        }
        List<MissingName> missingName = new ArrayList<>();
        List<Unassigned> unassigned = new ArrayList<>();
        List<Unresolved> unresolved = new ArrayList<>();

        for (RouteInfo info : infos) {
            String name = info.name();
            TierProbe probe = tierResolver.probe(info);

            if (name == null || name.isEmpty()) {
                // 未命名路由没有名字可判，只能按 URI 排除（forge 自身层级端点等），与分析器同口径
                if (filter.isUriExcluded(info.uri())) {
                    continue;
                }
                if (probe.matched()) {
                    missingName.add(new MissingName(info.uri(), info.methods(), probe.level(),
                            probe.source().wireName()));
                } else if (probe.source() != TierProbe.Source.NONE) {
                    unresolved.add(new Unresolved(null, info.uri(), reason(probe)));
                }
                continue;
            }

            if (filter.isNameExcluded(name) || filter.isUriExcluded(info.uri())) {
                continue;
            }

            if (probe.matched()) {
                continue;
            }
            if (probe.source() == TierProbe.Source.NONE) {
                unassigned.add(new Unassigned(name, info.uri(), info.methods(), info.middleware()));
            } else {
                unresolved.add(new Unresolved(name, info.uri(), reason(probe)));
            }
        }

        // 排序口径固定：missing_name 按 (level, uri)、unassigned 按 name、unresolved 按 (uri, name)
        missingName.sort(Comparator.comparing(MissingName::level, Comparator.nullsFirst(String::compareTo))
                .thenComparing(MissingName::uri));
        unassigned.sort(Comparator.comparing(Unassigned::name));
        unresolved.sort(Comparator.comparing(Unresolved::uri).thenComparing(Unresolved::name,
                Comparator.nullsFirst(String::compareTo)));

        memo = new Violations(missingName, unassigned, unresolved);
        return memo;
    }

    /** 层级命中来源的人话说法（与未命名路由告警措辞保持一致）。 */
    public static String sourceLabel(String source) {
        return switch (source) {
            case "explicit" -> "explicit ->tier()";
            case "classifier" -> "the classifier callback";
            case "match" -> "a config match rule";
            case "explicit-unknown-level" -> "a tier name missing from levels config";
            case "classifier-unknown-tier" -> "a classifier result missing from levels config";
            case "classifier-error" -> "a throwing classifier callback";
            default -> "no rule";
        };
    }

    private static String reason(TierProbe probe) {
        return switch (probe.source()) {
            case EXPLICIT_UNKNOWN_LEVEL ->
                    "declares tier [" + probe.requested() + "] which is not defined in levels config";
            case CLASSIFIER_UNKNOWN_TIER -> "gets tier [" + probe.requested()
                    + "] from the classifier, which is not defined in levels config";
            case CLASSIFIER_ERROR -> "classifier callback threw: " + probe.error();
            default -> "could not be resolved";
        };
    }
}
