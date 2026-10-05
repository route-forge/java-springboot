/**
 * Route Forge 框架无关核心。
 *
 * <p>本包承载 route-forge 家族的全部后端语义——层级解析、路由别名、严格模式违规聚合、
 * TS 类型声明生成、以及跨层级共享的缓存契约——与任何 Web 框架无关，因此不依赖 Spring。
 * 框架侧只需提供两样东西：把自身路由表归一成 {@code RouteInfo} 序列，以及一个缓存实现。
 *
 * <p>行为口径的唯一事实源是 Laravel 适配包的 SPEC；本包对其做逐字段等价移植，
 * 差异只在 {@code .docs/SPEC.md} 明确列出的 Java 专属条目（如双注解通道冲突）。
 */
package io.github.routeforge.core;
