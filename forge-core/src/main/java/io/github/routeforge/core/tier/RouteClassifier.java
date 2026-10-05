package io.github.routeforge.core.tier;

import io.github.routeforge.core.dto.RouteInfo;

/**
 * 宿主自定义分类回调（优先级链第 3 级）。
 *
 * <p>返回<b>非空字符串</b>即视为表态并归入该层级；返回 {@code null}、空串或非字符串一律视为「未表态」，
 * 继续走配置 match 规则——参数与返回值刻意用 {@code Object} 而非 {@code String}，是为了与 PHP 侧
 * {@code is_string($result) && $result !== ''} 的判据完全一致：强类型签名会把「回调返回了别的东西」
 * 这种情况在编译期消灭掉，而它恰恰是既有语义的一部分（跨语言对等阶段会拿它做用例）。
 *
 * <p>回调抛出的异常由 {@link TierResolver} 包装为 {@code RF_BE_004}（{@code resolve()} 路径）
 * 或降级为 {@code classifier-error} 来源标记（{@code probe()} 路径）。
 */
@FunctionalInterface
public interface RouteClassifier {

    Object classify(RouteInfo route) throws Exception;
}
