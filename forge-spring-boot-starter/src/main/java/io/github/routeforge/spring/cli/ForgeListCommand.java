package io.github.routeforge.spring.cli;

import io.github.routeforge.core.analyzer.Row;
import io.github.routeforge.core.analyzer.RouteAnalyzer;
import io.github.routeforge.core.exception.ForgeRuntimeException;
import io.github.routeforge.core.repository.RouteRepository;
import io.github.routeforge.core.support.HttpMethods;
import io.github.routeforge.core.support.JsonWriter;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code --forge:list}：列出全部命名路由的层级归属（含别名行）。逐段对齐 Laravel 的
 * {@code RouteForgeListCommand}，数据一律来自 {@link ForgeRouteRegistry#analyze()}（走注册表，不另扫）。
 *
 * <p><b>与 {@code --forge:types} 刻意相反的一点</b>：有严格模式违规时，list <b>照常出全表</b>、末尾追加
 * 红色清单、退出码 1——排查场景下命令本身必须还能跑出全貌，退出码交给 CI 拦、清单交给人定位。
 * types 则拒绝产出（d.ts 会被 commit，宁可不出）。
 *
 * <p>流口径（SPEC §5.1，Java 侧统一约定）：产物（表格 / {@code --json}）走 {@code out}（stdout），
 * 诊断走 {@code err}。据此有两处与 PHP 的分歧，都是「让 stdout 可安全重定向」这一条换来的：
 * {@code --json} 的违规红色清单走 stderr（同 PHP），但 Forge 异常的 {@code [code] 消息}也改走 stderr
 * （PHP 走 stdout，会让 {@code list --json > x.json} 里混进错误文本）。
 *
 * <p>着色：Laravel 表格有品红/黄/绿/红 ANSI 着色，仅存在于表格模式、非跨语言 golden 产物；
 * Java 侧不落 ANSI（表格渲染为纯文本对齐框），层级/别名/撞车语义改由列文字表达。
 */
public final class ForgeListCommand {

    /** {@code --unnamed} 视图里「层级名拼错 / classifier 抛错」条目的虚拟分组；非真实层级。 */
    private static final String UNRESOLVED_GROUP = "unresolved";

    private final ForgeRouteRegistry registry;

    public ForgeListCommand(ForgeRouteRegistry registry) {
        this.registry = registry;
    }

    /** @return 进程退出码（0 成功；1 参数冲突 / 未知层级 / Forge 异常 / 严格模式违规） */
    @SuppressWarnings("unchecked")
    public int execute(CliOptions options, PrintWriter out, PrintWriter err) {
        List<String> levels = registry.levelNames();
        boolean asJson = options.json();
        boolean onlyUnassigned = options.unassigned();
        boolean onlyAliases = options.aliases();
        boolean onlyUnnamed = options.unnamed();
        String filterLevel = options.hasLevel() ? options.level() : null;

        // --unnamed 是独立视图：数据来自 analyze 的 unnamed 键，与表格/warnings 同屏即同一事实双写，
        // 与 --json 同屏还要为此新造一份 JSON 形态——显式判冲突，不悄悄丢一边。
        if (onlyUnnamed && (asJson || onlyUnassigned || onlyAliases)) {
            err.println("--unnamed is a standalone view and cannot be combined with --json, --unassigned or --aliases.");
            err.println("Run it on its own: --forge:list --unnamed [--level=<level>]");
            return 1;
        }

        // level 校验：unassigned 是合法特殊层级；--unnamed 视图额外接受虚拟分组 unresolved
        List<String> allowed = new ArrayList<>(levels);
        allowed.add(RouteRepository.UNASSIGNED_LEVEL);
        if (onlyUnnamed) {
            allowed.add(UNRESOLVED_GROUP);
        }
        if (filterLevel != null && !allowed.contains(filterLevel)) {
            err.println("Unknown level: " + filterLevel);
            err.println("Available levels: " + (levels.isEmpty() ? "(none)" : String.join(", ", levels)));
            return 1;
        }

        RouteAnalyzer.Analysis analysis;
        try {
            analysis = registry.analyze();
        } catch (ForgeRuntimeException e) {
            // 悬空别名 / resolve 抛出的 RF_BE_002/004/006/008：打 [错误码] 消息，不抛裸堆栈
            err.println("[" + e.code() + "] " + e.getMessage());
            return 1;
        }

        if (onlyUnnamed) {
            RouteAnalyzer.formatUnnamed(analysis.unnamed(), levels, filterLevel).forEach(out::println);
            return 0;
        }

        List<String> violationLines = analysis.violations().count() > 0 ? analysis.violations().format() : List.of();
        Map<String, Row> rowByName = new LinkedHashMap<>();
        for (Row row : analysis.rows()) {
            rowByName.put(row.name(), row); // 同名多次注册：末次为准（对齐 PHP array_column）
        }

        List<Row> rows = RouteAnalyzer.filterRows(analysis.rows(), filterLevel, onlyUnassigned, onlyAliases);
        Map<String, Object> payload = RouteAnalyzer.listPayload(levels, rows, analysis.tierCounts(),
                analysis.warnings(), filterLevel, onlyUnassigned, onlyAliases);

        if (asJson) {
            out.println(JsonWriter.pretty(payload));
            if (!violationLines.isEmpty()) {
                violationLines.forEach(err::println); // stdout 是纯 JSON 产物：清单改走 stderr
                return 1;
            }
            return 0;
        }

        // 表格模式
        Map<String, Integer> orderedCounts = (Map<String, Integer>) payload.get("tier_counts");
        int unassignedCount = orderedCounts.getOrDefault(RouteRepository.UNASSIGNED_LEVEL, 0);
        out.println("Tier counts: " + orderedCounts.entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .collect(Collectors.joining(" | ")));
        if (unassignedCount > 0) {
            out.println(unassignedCount + " route(s) are unassigned and only available via the 'unassigned' tier."
                    + " Check match rules in forge levels config or add explicit tier markers.");
        }
        analysis.warnings().forEach(out::println); // 配置问题与当次过滤无关，任何过滤结果下都输出

        if (rows.isEmpty()) {
            out.println("No routes found matching the filter.");
            return finishWithViolations(out, violationLines); // 空表 + 一堆违规正是最需要开口的组合
        }

        List<String[]> tableRows = new ArrayList<>();
        for (Row row : rows) {
            tableRows.add(new String[] {
                    row.name(), row.level(), joinMethods(row.methods()), row.uri(),
                    row.aliasOf() == null ? "—" : row.aliasOf()});
        }
        // 撞车声明红行（仅表格）：被忽略的别名声明，配置问题需肉眼可见，但不进 --json 的 routes
        for (Map.Entry<String, String> collision : analysis.collisions().entrySet()) {
            Row target = rowByName.get(collision.getValue());
            tableRows.add(new String[] {
                    collision.getKey(),
                    target != null ? target.level() : "—",
                    target != null ? joinMethods(target.methods()) : "—",
                    target != null ? target.uri() : "—",
                    collision.getValue()});
        }
        out.println(renderTable(new String[] {"Name/Alias", "Level", "Methods", "URI", "Alias Of"}, tableRows));
        return finishWithViolations(out, violationLines);
    }

    /** 表格收尾：红色清单紧邻表格追加，有违规即退 1（表格模式无产物污染问题，清单留在 stdout 便于对照）。 */
    private static int finishWithViolations(PrintWriter out, List<String> violationLines) {
        violationLines.forEach(out::println);
        return violationLines.isEmpty() ? 0 : 1;
    }

    private static String joinMethods(List<String> methods) {
        return String.join("|", HttpMethods.withoutHead(methods));
    }

    /** 纯文本对齐框表（无 ANSI）；列宽取表头与各行的最大显示宽度，产物稳定可断言。 */
    private static String renderTable(String[] headers, List<String[]> rows) {
        int[] widths = new int[headers.length];
        for (int c = 0; c < headers.length; c++) {
            widths[c] = headers[c].length();
        }
        for (String[] row : rows) {
            for (int c = 0; c < row.length; c++) {
                widths[c] = Math.max(widths[c], row[c].length());
            }
        }
        String separator = "+" + borders(widths);
        StringBuilder out = new StringBuilder();
        out.append(separator).append('\n');
        out.append(row(headers, widths)).append('\n');
        out.append(separator).append('\n');
        for (String[] row : rows) {
            out.append(row(row, widths)).append('\n');
        }
        out.append(separator);
        return out.toString();
    }

    private static String borders(int[] widths) {
        StringBuilder line = new StringBuilder();
        for (int width : widths) {
            line.append("-".repeat(width + 2)).append('+');
        }
        return line.toString();
    }

    private static String row(String[] cells, int[] widths) {
        StringBuilder line = new StringBuilder("|");
        for (int c = 0; c < cells.length; c++) {
            line.append(' ').append(pad(cells[c], widths[c])).append(" |");
        }
        return line.toString();
    }

    private static String pad(String value, int width) {
        StringBuilder cell = new StringBuilder(value);
        while (cell.length() < width) {
            cell.append(' ');
        }
        return cell.toString();
    }
}
