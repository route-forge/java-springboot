package io.github.routeforge.core.cache;

import io.github.routeforge.core.contract.CacheStore;
import io.github.routeforge.core.exception.CacheDriverException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 路由元信息缓存：按层级独立存放，互不污染。缓存键形如 {@code route-forge:{level}}。
 *
 * <p>TTL 语义（构造时统一传入，对应 {@code forge.cache-ttl}）：
 * <ul>
 *   <li>{@code null}：不缓存（每次扫描）</li>
 *   <li>{@code 0}：永久缓存（下发给 {@link CacheStore} 时归一为 {@code null}）</li>
 *   <li>正整数：TTL 秒</li>
 *   <li>负值：归一化为 {@code null}（不缓存）——负 TTL 无意义，与「不缓存」取同一行为</li>
 * </ul>
 *
 * <p><b>keys 索引</b>：为支持 {@link #clear()} 一次性清空所有层级（不依赖底层通配符能力），
 * 维护独立的 {@value #KEYS_INDEX} 列表：{@link #set} 追加、{@link #forget} 移除、{@link #clear()} 遍历删除。
 * 索引本身永久存放，不随单个层级的 TTL 过期。
 *
 * <p><b>debug 旁路</b>：开发模式下跳过读写，保证路由与配置变更即时生效；
 * 但 {@link #clear()} <b>不旁路</b>——否则「切了 debug 又关回去」时旧缓存会复活。
 *
 * <p>框架无关：只依赖 {@link CacheStore}。
 */
public class RouteCache {

    private static final String KEY_PREFIX = "route-forge:";

    /** keys 索引键名（诊断与测试断言用）。 */
    public static final String KEYS_INDEX = "route-forge:_keys";

    /**
     * 摘要端点的缓存「层级名」。
     *
     * <p>摘要与层级条目同表存放：摘要的 {@code route_count} 依赖各层级路由数据，
     * 任何层级失效都必须同步失效摘要，否则计数与明细漂移。该不变量由 {@link #forgetLevel} 封装，
     * 各框架的 clear 命令一律经它，禁止直接 {@link #forget}。
     */
    public static final String SUMMARY_LEVEL = "summary";

    private final CacheStore store;
    private final boolean debugMode;
    private final Integer ttl;

    /**
     * @param store     底层缓存；{@code null} 等价「不缓存」
     * @param debugMode 开发模式（Spring 侧即 {@code debug=true}）：跳过缓存读写
     * @param ttl       统一 TTL 秒；{@code null} 不缓存、{@code 0} 永久、负值归一为不缓存
     */
    public RouteCache(CacheStore store, boolean debugMode, Integer ttl) {
        this.store = store;
        this.debugMode = debugMode;
        this.ttl = ttl != null && ttl < 0 ? null : ttl;
    }

    /**
     * 取某层级的缓存条目。
     *
     * <p>类型不符（底层被别的组件写过脏数据、或序列化实现返回了非预期结构）按未命中处理，
     * 与 PHP 侧 {@code is_array($value) ?: null} 同判据——绝不让脏缓存条目变成端点 500。
     */
    public <T> T get(String level, Class<T> type) {
        if (store == null || debugMode) {
            return null;
        }
        try {
            Object value = store.get(key(level));
            return type.isInstance(value) ? type.cast(value) : null;
        } catch (RuntimeException e) {
            throw wrap(e);
        }
    }

    /** 写入某层级条目；未启用缓存、不缓存模式或 debug 模式下静默跳过。 */
    public void set(String level, Object payload) {
        if (store == null || debugMode || ttl == null) {
            return;
        }
        try {
            String key = key(level);
            // ttl=0 的语义是永久缓存：以 null 传给底层（Laravel forever / 等价实现）
            store.put(key, payload, ttl == 0 ? null : Long.valueOf(ttl));
            registerKey(key);
        } catch (RuntimeException e) {
            throw wrap(e);
        }
    }

    public void forget(String level) {
        if (store == null || debugMode) {
            return;
        }
        try {
            String key = key(level);
            store.forget(key);
            unregisterKey(key);
        } catch (RuntimeException e) {
            throw wrap(e);
        }
    }

    /**
     * 失效单个层级，并同步失效摘要缓存。
     *
     * <p>不变量（勿绕过）：摘要的 {@code route_count} 依赖各层级路由数据，层级失效后摘要必须一并失效。
     * 各框架的 {@code clear --level} 一律走本方法。
     */
    public void forgetLevel(String level) {
        forget(level);
        if (!SUMMARY_LEVEL.equals(level)) {
            forget(SUMMARY_LEVEL);
        }
    }

    /** 按 keys 索引清空全部 forge 缓存键（含摘要）。debug 模式不旁路。 */
    public void clear() {
        if (store == null) {
            return;
        }
        try {
            List<Object> keys = indexOf(store.get(KEYS_INDEX));
            if (keys == null) {
                return;
            }
            for (Object key : keys) {
                store.forget(String.valueOf(key));
            }
            store.forget(KEYS_INDEX);
        } catch (RuntimeException e) {
            throw wrap(e);
        }
    }

    /** 生效中的 TTL（负值已归一），供诊断输出与测试断言。 */
    public Integer effectiveTtl() {
        return ttl;
    }

    /** 是否处于「不碰缓存」状态（无 store 或 debug 模式）。 */
    public boolean disabled() {
        return store == null || debugMode;
    }

    private static String key(String level) {
        return KEY_PREFIX + level;
    }

    private void registerKey(String key) {
        List<Object> keys = indexOf(store.get(KEYS_INDEX));
        if (keys == null) {
            keys = new ArrayList<>();
        }
        if (!keys.contains(key)) {
            keys.add(key);
            // 索引本身永久存放：不随单个层级的 TTL 一起过期
            store.put(KEYS_INDEX, keys, null);
        }
    }

    private void unregisterKey(String key) {
        List<Object> keys = indexOf(store.get(KEYS_INDEX));
        if (keys == null) {
            return;
        }
        keys.removeIf(candidate -> String.valueOf(candidate).equals(key));
        store.put(KEYS_INDEX, keys, null);
    }

    /** 索引值只有「列表」与「缺失」两种合法形态；其余按缺失处理（与 PHP 的 is_array 判定同）。 */
    private static List<Object> indexOf(Object raw) {
        if (raw instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        if (raw instanceof Object[] array) {
            return new ArrayList<>(Arrays.asList(array));
        }
        return null;
    }

    private static CacheDriverException wrap(RuntimeException cause) {
        return new CacheDriverException("Cache driver error: " + cause.getMessage(), cause);
    }
}
