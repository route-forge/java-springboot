package io.github.routeforge.core.contract;

/**
 * 路由元信息缓存的框架无关契约。
 *
 * <p>由各框架适配层桥接到自身缓存组件（Spring 侧为内存实现或 Redis）。核心层的
 * {@code RouteCache} 只依赖本接口完成层级元信息与摘要的读写。
 *
 * <p>{@link #put} 的 {@code seconds} 语义（与 SPEC 缓存策略对齐）：
 * <ul>
 *   <li>{@code null}：永久缓存（对应 Laravel {@code forever}）</li>
 *   <li>正数：TTL 秒</li>
 * </ul>
 * 注意 {@code 0} 不会传到这里——{@code RouteCache} 已把「0 = 永久」归一为 {@code null} 再下发。
 */
public interface CacheStore {

    /** 读取；不存在返回 {@code null}。实现抛出的异常会被 {@code RouteCache} 包装为 RF_BE_003。 */
    Object get(String key);

    void put(String key, Object value, Long seconds);

    void forget(String key);
}
