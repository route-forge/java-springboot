package io.github.routeforge.spring.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.routeforge.core.contract.CacheStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ValueOperations;

/**
 * {@link RedisCacheStore} 的驱动逻辑测试（对 mock 的 {@link RedisOperations} 断言调用形态）。
 * 本机无 Redis 服务，故覆盖「读写/过期/失效的调用契约 + TTL 归一」，真实连通性不在单测范围（见 PROGRESS 覆盖边界）。
 */
@SuppressWarnings("unchecked")
class RedisCacheStoreTest {

    private final RedisOperations<String, Object> redis = mock(RedisOperations.class);
    private final ValueOperations<String, Object> valueOps = mock(ValueOperations.class);

    @Test
    @DisplayName("get 走 opsForValue().get 并原样回还缓存值（Map 类型自持）")
    void getDelegatesToValueGet() {
        when(redis.opsForValue()).thenReturn(valueOps);
        Map<String, Object> cached = Map.of("admin", 1);
        when(valueOps.get("route-forge:admin")).thenReturn(cached);

        CacheStore store = new RedisCacheStore(redis);

        assertThat(store.get("route-forge:admin")).isSameAs(cached);
        verify(valueOps).get("route-forge:admin");
    }

    @Test
    @DisplayName("keys 索引（List）同样原样回还")
    void getListRoundTrips() {
        when(redis.opsForValue()).thenReturn(valueOps);
        List<Object> keys = List.of("route-forge:admin");
        when(valueOps.get("route-forge:_keys")).thenReturn(keys);

        assertThat(new RedisCacheStore(redis).get("route-forge:_keys")).isSameAs(keys);
    }

    @Test
    @DisplayName("seconds=null → 永久 set（无 TTL），不调带过期的重载")
    void nullSecondsIsForever() {
        when(redis.opsForValue()).thenReturn(valueOps);

        new RedisCacheStore(redis).put("k", "v", null);

        verify(valueOps).set("k", "v");
        verify(valueOps, never()).set(anyString(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("seconds 正数 → set 带 EXPIRE 秒")
    void positiveSecondsSetsTtl() {
        when(redis.opsForValue()).thenReturn(valueOps);

        new RedisCacheStore(redis).put("k", "v", 30L);

        verify(valueOps).set(eq("k"), eq("v"), eq(30L), eq(TimeUnit.SECONDS));
        verify(valueOps, never()).set(eq("k"), eq("v"));
    }

    @Test
    @DisplayName("forget → delete(key)")
    void forgetDeletes() {
        new RedisCacheStore(redis).forget("route-forge:admin");

        verify(redis).delete("route-forge:admin");
    }
}
