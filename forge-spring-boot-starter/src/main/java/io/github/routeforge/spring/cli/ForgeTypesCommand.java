package io.github.routeforge.spring.cli;

import io.github.routeforge.core.analyzer.RouteAnalyzer;
import io.github.routeforge.core.exception.ForgeRuntimeException;
import io.github.routeforge.core.type.TypeGenerator;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code --forge:types}：从路由表生成 TS 类型声明或 JSON 结构。逐段对齐 Laravel 的
 * {@code RouteForgeTypesCommand}，产物由核心层 {@link TypeGenerator} 生成（与端点、内嵌摘要同一生成器）。
 *
 * <p><b>有严格模式违规即拒绝产出</b>：d.ts 会被前端 commit 进仓库，缺名字或漏归级的路由一旦写进去，
 * 就固化成「看起来是对的」的错契约，比一条报错难查得多。此时红色清单与 warnings 全走 stderr，
 * stdout 不落任何产物，退出码 1。这与 {@code --forge:list}「有违规照常出全表」<b>刻意相反</b>。
 *
 * <p>流口径（SPEC §5.1）：只有产物（无 {@code --out} 时的 d.ts / JSON）走 stdout；一切反馈
 * （未知层级、Forge 异常、warnings、红色清单、{@code Written to} 确认）都走 stderr。这是对 PHP 的一处
 * 有意收紧——PHP 把这些发到 stdout，会让 {@code route:forge:types > x.d.ts} 混入诊断文本；Java 侧
 * stdout 恒为纯产物。产物干净的主路径仍是 {@code --out=}（Boot 的 banner 与启动日志默认写 stdout，
 * 重定向仍会被污染，故 {@code --out} 为推荐用法，SPEC 已注明）。
 *
 * @param timestampProvider d.ts 文件头时间戳来源；生产注入 {@link TypeGenerator#currentTimestamp()}，
 *                          测试注入固定值以整文断言产物
 */
public final class ForgeTypesCommand {

    private final ForgeRouteRegistry registry;
    private final Supplier<String> timestampProvider;

    public ForgeTypesCommand(ForgeRouteRegistry registry) {
        this(registry, TypeGenerator::currentTimestamp);
    }

    public ForgeTypesCommand(ForgeRouteRegistry registry, Supplier<String> timestampProvider) {
        this.registry = registry;
        this.timestampProvider = timestampProvider;
    }

    /** @return 进程退出码（0 成功产出；1 未知层级 / Forge 异常 / 严格模式违规） */
    public int execute(CliOptions options, PrintWriter out, PrintWriter err) {
        List<String> levels = registry.levelNames();
        String filterLevel = options.hasLevel() ? options.level() : null;

        // types 的 level 过滤只认已配置层级（不含 unassigned——d.ts 里没有 unassigned 联合成员）
        if (filterLevel != null && !levels.contains(filterLevel)) {
            err.println("Unknown level: " + filterLevel);
            err.println("Available levels: " + (levels.isEmpty() ? "(none)" : String.join(", ", levels)));
            return 1;
        }

        RouteAnalyzer.Analysis analysis;
        try {
            analysis = registry.analyze();
        } catch (ForgeRuntimeException e) {
            err.println("[" + e.code() + "] " + e.getMessage());
            return 1;
        }

        if (analysis.violations().count() > 0) {
            analysis.warnings().forEach(err::println);
            analysis.violations().format().forEach(err::println);
            return 1; // 拒绝产出任何 d.ts / JSON
        }

        TypeGenerator generator = new TypeGenerator();
        List<String> targets = filterLevel != null ? List.of(filterLevel) : levels;
        var routesByLevel = generator.collectTargets(analysis.rows(), targets);
        String output = options.json()
                ? generator.generateJson(routesByLevel)
                : generator.generateDts(routesByLevel, registry.normalizedEndpointPrefix(), timestampProvider.get());

        String outFile = options.out();
        if (outFile != null && !outFile.isEmpty()) {
            try {
                writeProduct(outFile, output);
            } catch (IOException e) {
                err.println("Failed to write " + outFile + ": " + e.getMessage());
                return 1;
            }
            err.println("Written to: " + outFile);
            analysis.warnings().forEach(err::println);
            return 0;
        }

        analysis.warnings().forEach(err::println);
        out.println(output); // 不带 --out：stdout 即产物本身，诊断已在 stderr
        return 0;
    }

    private static void writeProduct(String outFile, String output) throws IOException {
        Path path = Paths.get(outFile);
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, output, StandardCharsets.UTF_8);
    }
}
