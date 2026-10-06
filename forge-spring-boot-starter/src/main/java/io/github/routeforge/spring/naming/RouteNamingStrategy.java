package io.github.routeforge.spring.naming;

/**
 * 命名通道之一（第三通道）：按 handler 的类与方法派生路由名。
 *
 * <p>默认<b>不注册</b>（容器里没有本接口的 bean 即等于关闭）。它的定位是「存量项目的批量接入捷径」：
 * 不想逐条补注解时，用一个 bean 把 {@code AdminUserController#index} 这类形态映射成
 * {@code admin.user.index}。派生规则一旦发布就是长期契约（改名即断前端），因此包本身不提供默认实现。
 *
 * <p>优先级最低：显式注解（{@code @ForgeRoute} / {@code @Forge} / Spring 原生 {@code name}）先表态，
 * 本策略只在它们都没给名字时被调用。返回 {@code null} 表示不表态，该路由按未命名处理
 * （未命名路由进不了任何元信息，严格模式下计入 {@code RF_BE_009} 的 {@code missing_name}）。
 */
@FunctionalInterface
public interface RouteNamingStrategy {

    /**
     * 为一条候选路由派生名字。
     *
     * @param candidate 扫描期已知的全部事实（不保证 pattern 已剥正则约束，实现方按需处理）
     * @return 派生出的路由名；不表态时返回 {@code null}
     */
    String nameOf(RouteCandidate candidate);
}
