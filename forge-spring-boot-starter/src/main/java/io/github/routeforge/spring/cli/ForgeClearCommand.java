package io.github.routeforge.spring.cli;

import io.github.routeforge.core.repository.RouteRepository;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code --forge:clear}：清除路由元信息缓存。逐段对齐 Laravel 的 {@code RouteForgeClearCommand}。
 *
 * <p>带 {@code --level} 时走 {@link ForgeRouteRegistry#clearLevelCache(String)}——它经
 * {@code RouteCache.forgetLevel} 封装了「层级失效连带失效 summary」的不变量，<b>禁止</b>直接 forget 单键。
 * 不带 {@code --level} 时清空全部（含摘要）。
 *
 * <p>本命令无产物，状态信息走 stdout（对齐 artisan 的 {@code info}/{@code error}），不必像 types 那样把
 * 反馈挪到 stderr。
 */
public final class ForgeClearCommand {

    private final ForgeRouteRegistry registry;

    public ForgeClearCommand(ForgeRouteRegistry registry) {
        this.registry = registry;
    }

    /** @return 进程退出码（0 成功；1 未知层级） */
    public int execute(CliOptions options, PrintWriter out, PrintWriter err) {
        String level = options.hasLevel() ? options.level() : null;

        if (level == null) {
            registry.clearAllCache();
            out.println("Route Forge cache cleared successfully.");
            return 0;
        }

        List<String> allowed = new ArrayList<>(registry.levelNames());
        allowed.add(RouteRepository.UNASSIGNED_LEVEL); // unassigned 端点缓存独立存储，同样可清
        if (!allowed.contains(level)) {
            out.println("Unknown level: " + level);
            out.println("Available levels: " + String.join(", ", allowed));
            return 1;
        }

        registry.clearLevelCache(level);
        out.println("Route Forge cache cleared for level: " + level + " (summary cache invalidated as well)");
        return 0;
    }
}
