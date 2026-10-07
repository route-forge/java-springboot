package io.github.routeforge.spring.cli;

/**
 * 命令行三命令共用的已解析选项（对应 SPEC §5.1 的选项面，形态对齐 artisan）。
 *
 * <p>命令对象只吃这个不可变值，不碰 {@code ApplicationArguments}：解析留在
 * {@link ForgeCliRunner}（Spring 侧），渲染与退出码逻辑因此可脱离 Spring、喂 {@code StringWriter}
 * 直接单测——本仓「整文断言」的纪律要求产物不绑定启动方式。
 *
 * @param level      {@code --level=} 的值；未给或空串视为不过滤（用 {@link #hasLevel()} 判）
 * @param json       {@code --json}
 * @param unassigned {@code --unassigned}（仅 list）
 * @param aliases    {@code --aliases}（仅 list）
 * @param unnamed    {@code --unnamed}（仅 list，独立视图）
 * @param out        {@code --out=} 的目标文件；null 表示产物走 stdout（仅 types）
 */
public record CliOptions(String level, boolean json, boolean unassigned, boolean aliases, boolean unnamed,
        String out) {

    /** 无任何选项（{@code --forge:clear} 全清、{@code --forge:list} 全表都走这个默认）。 */
    public static CliOptions none() {
        return new CliOptions(null, false, false, false, false, null);
    }

    /** 是否带了有效的 {@code --level} 过滤值。 */
    public boolean hasLevel() {
        return level != null && !level.isEmpty();
    }
}
