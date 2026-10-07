package io.github.routeforge.spring.cache;

import io.github.routeforge.core.contract.CacheStore;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存缓存实现（{@code forge.cache-driver=memory}，默认）。
 *
 * <p>只保证跨请求复用与按时失效，不做容量上限与 LRU：forge 的键数量等于层级数 + 摘要（个位数到十几），
 * 加淘汰策略是负收益。过期采用<b>读时发现</b>而非后台线程——多一个线程就多一个生命周期问题，
 * 而这里没有「必须准时回收」的诉求（下一次读就会回收，且 clear 会显式清）。
 */
public final class InMemoryCacheStore implements CacheStore {

    private record Entry(Object value, long expireAtNanos) {

        boolean expired() {
            return expireAtNanos != Long.MAX_VALUE && System.nanoTime() > expireAtNanos;
        }
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Object get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired()) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    /**
     * @param seconds {@code null} 表示永久（{@code RouteCache} 已把「TTL 0 = 永久」归一为 null 传下来）
     */
    @Override
    public void put(String key, Object value, Long seconds) {
        long expireAt = seconds == null || seconds <= 0
                ? Long.MAX_VALUE
                : System.nanoTime() + seconds * 1_000_000_000L;
        entries.put(key, new Entry(value, expireAt));
    }

    @Override
    public void forget(String key) {
        entries.remove(key);
    }
}
