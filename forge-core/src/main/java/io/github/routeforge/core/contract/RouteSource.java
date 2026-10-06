package io.github.routeforge.core.contract;

import io.github.routeforge.core.dto.RouteInfo;
import java.util.List;

/**
 * 路由来源：给出<b>已归一化</b>的全量路由信息。
 *
 * <p>为什么是「已归一化」而不是 PHP 那样的「原生路由 + normalizer」：归一化本身是框架特定的
 * （Laravel 读 {@code Route::getRoutes()}、Spring 读 {@code RequestMappingHandlerMapping}），
 * 核心层拿到的就应当是统一的 {@link RouteInfo} 序列。适配层把
 * {@link RouteNormalizer} 包在自己的 {@code RouteSource} 实现里，核心层因此完全不需要
 * 「路由对象类型」这个参数。
 *
 * <p>调用约定：每次调用都会真正去取（PHP 侧各公开方法内部都会重跑一次 {@code infos()}），
 * 跨请求复用由缓存层负责，本接口不做记忆。
 */
public interface RouteSource {

    List<RouteInfo> routes();
}
