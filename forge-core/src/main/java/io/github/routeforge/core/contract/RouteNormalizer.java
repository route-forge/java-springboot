package io.github.routeforge.core.contract;

import io.github.routeforge.core.dto.RouteInfo;

/**
 * 框架路由 → 统一 {@link RouteInfo} 的转换契约。
 *
 * <p>每个后端适配包实现本接口，把框架原生路由对象转成核心层消费的 DTO，
 * 使层级解析、别名、仓库、类型生成等业务逻辑完全框架无关。
 *
 * <p>{@link RouteInfo#source()} 保留原始对象引用，供 classifier 回调按宿主类型取回使用。
 *
 * @param <R> 框架原生路由对象类型（Spring 侧为 {@code HandlerMethod}）
 */
public interface RouteNormalizer<R> {

    RouteInfo normalize(R route);
}
