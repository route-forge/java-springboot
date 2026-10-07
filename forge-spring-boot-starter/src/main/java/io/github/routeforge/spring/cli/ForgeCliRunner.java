package io.github.routeforge.spring.cli;

import java.io.BufferedWriter;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * CLI 入口：把 {@code --forge:list} / {@code --forge:types} / {@code --forge:clear} 参数式命令接到三个命令对象上。
 *
 * <p><b>为什么是 {@link ApplicationRunner} 而不是别的</b>：路由表来自 {@code RequestMappingHandlerMapping}，
 * 只能在 web 上下文里拿到（这是硬约束）；因此复用宿主已装配好的
 * {@link io.github.routeforge.spring.registry.ForgeRouteRegistry} 与缓存，而不是另起一个非-web 引导重扫一遍。
 * 命令行是宿主的运维入口，命令跑完即结束进程，用 {@link System#exit(int)} 落地退出码（CI 据此拦下违规）。
 *
 * <p><b>flag 缺席即完全 no-op</b>：{@code run()} 只在检测到某个 {@code --forge:*} 时才动作并退出，
 * 正常启动、{@code @SpringBootTest}（不传这些参数）都不受影响。这也意味着：宿主若误带 {@code --forge:list}
 * 正常启服务，会被当成「跑完命令就退出」——这是命令式 CLI 的应有语义（与 artisan 一致），SPEC 已注明。
 *
 * <p>退出路径与命令逻辑分离：{@link #executeIfRequested(ApplicationArguments)} 只做「解析 + 分派 + 返回退出码」，
 * 不 {@code System.exit}，因此可脱离真实进程单测；{@link #run} 是薄壳，唯一职责是刷新流并退出。
 * 产物走 UTF-8 写出（Windows 默认 GBK 会污染中文产物并造成假失败，AGENTS 编码铁律）。
 */
public final class ForgeCliRunner implements ApplicationRunner {

    /** 三个命令 flag（{@code --forge:xxx}）；出现任一即进入命令行分支。 */
    public static final String OPTION_LIST = "forge:list";
    public static final String OPTION_TYPES = "forge:types";
    public static final String OPTION_CLEAR = "forge:clear";

    private final ForgeListCommand listCommand;
    private final ForgeTypesCommand typesCommand;
    private final ForgeClearCommand clearCommand;
    private final PrintWriter out;
    private final PrintWriter err;

    public ForgeCliRunner(ForgeListCommand listCommand, ForgeTypesCommand typesCommand,
            ForgeClearCommand clearCommand) {
        this(listCommand, typesCommand, clearCommand, consoleWriter(System.out), consoleWriter(System.err));
    }

    ForgeCliRunner(ForgeListCommand listCommand, ForgeTypesCommand typesCommand, ForgeClearCommand clearCommand,
            PrintWriter out, PrintWriter err) {
        this.listCommand = listCommand;
        this.typesCommand = typesCommand;
        this.clearCommand = clearCommand;
        this.out = out;
        this.err = err;
    }

    @Override
    public void run(ApplicationArguments args) {
        Integer exitCode = executeIfRequested(args);
        if (exitCode != null) {
            out.flush();
            err.flush();
            System.exit(exitCode);
        }
    }

    /**
     * 解析参数并分派；没有 forge 命令 flag 时返回 {@code null}（表示不进入命令行、不退出）。
     *
     * <p>多个 flag 同时出现时按 list → types → clear 固定顺序取第一个，与「一次只跑一条命令」的直觉一致。
     */
    Integer executeIfRequested(ApplicationArguments args) {
        boolean list = args.containsOption(OPTION_LIST);
        boolean types = args.containsOption(OPTION_TYPES);
        boolean clear = args.containsOption(OPTION_CLEAR);
        if (!list && !types && !clear) {
            return null;
        }
        CliOptions options = parseOptions(args);
        if (list) {
            return listCommand.execute(options, out, err);
        }
        if (types) {
            return typesCommand.execute(options, out, err);
        }
        return clearCommand.execute(options, out, err);
    }

    /** {@code --x=y} 取值、{@code --x} 视为开关；切分口径由 Spring 的 {@link ApplicationArguments} 决定。 */
    private static CliOptions parseOptions(ApplicationArguments args) {
        return new CliOptions(
                firstValue(args, "level"),
                args.containsOption("json"),
                args.containsOption("unassigned"),
                args.containsOption("aliases"),
                args.containsOption("unnamed"),
                firstValue(args, "out"));
    }

    private static String firstValue(ApplicationArguments args, String name) {
        List<String> values = args.getOptionValues(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static PrintWriter consoleWriter(OutputStream stream) {
        // autoflush=true 逐行即时可见；编码显式 UTF-8，不吃 Windows 默认字符集
        return new PrintWriter(new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8)), true);
    }
}
