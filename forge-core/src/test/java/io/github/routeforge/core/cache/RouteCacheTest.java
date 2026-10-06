package io.github.routeforge.core.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.routeforge.core.contract.CacheStore;
import io.github.routeforge.core.exception.CacheDriverException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 缓存语义守卫。TTL 的三态（不缓存 / 永久 / 定时）与 keys 索引的维护是跨语言契约的一部分：
 * Laravel 的 {@code 0} 表示永久（不是 HTTP max-age=0），Spring 侧必须复刻同一口径，
 * 否则同一份配置在两个后端上的缓存行为不同。
 */
class RouteCacheTest {

    /** 记录调用序列的内存实现，兼作「哪些操作真的落到了底层」的断言面。 */
    private static final class RecordingStore implements CacheStore {
        final Map<String, Object> values = new LinkedHashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean failNext;

        @Override
        public Object get(String key) {
            calls.add("get:" + key);
            checkFailure();
            return values.get(key);
        }

        @Override
        public void put(String key, Object value, Long seconds) {
            calls.add("put:" + key + ":" + seconds);
            checkFailure();
            values.put(key, value);
        }

        @Override
        public void forget(String key) {
            calls.add("forget:" + key);
            checkFailure();
            values.remove(key);
        }

        private void checkFailure() {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("boom");
            }
        }

        @SuppressWarnings("unchecked")
        List<Object> index() {
            return (List<Object>) values.get(RouteCache.KEYS_INDEX);
        }
    }

    private static Map<String, Object> payload(String level) {
        return Map.of("level", level, "routes", Map.of());
    }

    @Nested
    @DisplayName("TTL 三态")
    class TtlSemantics {

        @Test
        @DisplayName("ttl=null 不缓存：set 完全不碰底层")
        void nullTtlMeansNoCaching() {
            RecordingStore store = new RecordingStore();

            new RouteCache(store, false, null).set("admin", payload("admin"));

            assertThat(store.calls).isEmpty();
            assertThat(store.values).isEmpty();
        }

        @Test
        @DisplayName("ttl=0 永久缓存：下发给底层的是 null，不是 0")
        void zeroTtlIsForever() {
            RecordingStore store = new RecordingStore();

            new RouteCache(store, false, 0).set("admin", payload("admin"));

            assertThat(store.calls).contains("put:route-forge:admin:null");
        }

        @Test
        @DisplayName("正 TTL 原样下发")
        void positiveTtlPassesThrough() {
            RecordingStore store = new RecordingStore();

            new RouteCache(store, false, 3600).set("admin", payload("admin"));

            assertThat(store.calls).contains("put:route-forge:admin:3600");
        }

        @Test
        @DisplayName("负 TTL 归一为不缓存，且归一结果可被诊断读出")
        void negativeTtlNormalizesToNull() {
            RecordingStore store = new RecordingStore();
            RouteCache cache = new RouteCache(store, false, -5);

            cache.set("admin", payload("admin"));

            assertThat(cache.effectiveTtl()).isNull();
            assertThat(store.calls).isEmpty();
        }
    }

    @Nested
    @DisplayName("debug 旁路")
    class DebugBypass {

        @Test
        @DisplayName("debug 模式跳过读写，但 clear 不跳过（否则关回 debug 时旧缓存复活）")
        void debugSkipsReadsWritesButNotClear() {
            RecordingStore store = new RecordingStore();
            store.values.put(RouteCache.KEYS_INDEX, new ArrayList<>(List.of("route-forge:admin")));
            store.values.put("route-forge:admin", payload("admin"));
            RouteCache cache = new RouteCache(store, true, 3600);

            assertThat(cache.get("admin", Map.class)).isNull();
            cache.set("client", payload("client"));
            cache.forget("admin");
            cache.clear();

            assertThat(store.calls).containsExactly("get:" + RouteCache.KEYS_INDEX,
                    "forget:route-forge:admin", "forget:" + RouteCache.KEYS_INDEX);
        }

        @Test
        @DisplayName("无 store 时整体禁用，clear 也静默")
        void nullStoreDisablesEverything() {
            RouteCache cache = new RouteCache(null, false, 3600);

            assertThat(cache.disabled()).isTrue();
            cache.set("admin", payload("admin"));
            cache.clear();
            assertThat(cache.get("admin", Map.class)).isNull();
        }
    }

    @Nested
    @DisplayName("keys 索引与失效不变量")
    class KeysIndex {

        @Test
        @DisplayName("set 追加索引且去重；索引永久存放（seconds=null）")
        void setRegistersKeyOnce() {
            RecordingStore store = new RecordingStore();
            RouteCache cache = new RouteCache(store, false, 60);

            cache.set("admin", payload("admin"));
            cache.set("admin", payload("admin"));
            cache.set("client", payload("client"));

            assertThat(store.index()).containsExactly("route-forge:admin", "route-forge:client");
            assertThat(store.calls).contains("put:" + RouteCache.KEYS_INDEX + ":null");
        }

        @Test
        @DisplayName("forget 同步从索引移除")
        void forgetUnregistersKey() {
            RecordingStore store = new RecordingStore();
            RouteCache cache = new RouteCache(store, false, 60);
            cache.set("admin", payload("admin"));

            cache.forget("admin");

            assertThat(store.index()).isEmpty();
            assertThat(store.values).doesNotContainKey("route-forge:admin");
        }

        @Test
        @DisplayName("forgetLevel 连带失效摘要：计数与明细不得漂移")
        void forgetLevelAlsoForgetsSummary() {
            RecordingStore store = new RecordingStore();
            RouteCache cache = new RouteCache(store, false, 60);
            cache.set("admin", payload("admin"));
            cache.set(RouteCache.SUMMARY_LEVEL, payload(RouteCache.SUMMARY_LEVEL));

            cache.forgetLevel("admin");

            assertThat(store.calls).contains("forget:route-forge:admin", "forget:route-forge:summary");
            assertThat(store.index()).doesNotContain("route-forge:admin", "route-forge:summary");
        }

        @Test
        @DisplayName("失效摘要本身不会二次失效摘要")
        void forgetLevelOnSummaryIsIdempotent() {
            RecordingStore store = new RecordingStore();
            RouteCache cache = new RouteCache(store, false, 60);
            cache.set(RouteCache.SUMMARY_LEVEL, payload(RouteCache.SUMMARY_LEVEL));

            cache.forgetLevel(RouteCache.SUMMARY_LEVEL);

            assertThat(store.calls.stream().filter(call -> call.startsWith("forget:")).toList())
                    .containsExactly("forget:route-forge:summary");
        }

        @Test
        @DisplayName("clear 按索引精确删除，不依赖通配符")
        void clearDrainsIndex() {
            RecordingStore store = new RecordingStore();
            RouteCache cache = new RouteCache(store, false, 60);
            cache.set("admin", payload("admin"));
            cache.set("client", payload("client"));

            cache.clear();

            assertThat(store.values).isEmpty();
            assertThat(store.calls).contains("forget:route-forge:admin", "forget:route-forge:client",
                    "forget:" + RouteCache.KEYS_INDEX);
        }

        @Test
        @DisplayName("索引被外部破坏（非列表）时按缺失处理，不抛")
        void corruptIndexIsTolerated() {
            RecordingStore store = new RecordingStore();
            store.values.put(RouteCache.KEYS_INDEX, "not-a-list");

            new RouteCache(store, false, 60).clear();

            assertThat(store.calls).containsExactly("get:" + RouteCache.KEYS_INDEX);
        }
    }

    @Nested
    @DisplayName("值形态与故障包装")
    class ValuesAndFailures {

        @Test
        @DisplayName("底层返回非条目结构按未命中处理")
        void nonMapValueIsMiss() {
            RecordingStore store = new RecordingStore();
            store.values.put("route-forge:admin", "garbage");

            assertThat(new RouteCache(store, false, 60).get("admin", Map.class)).isNull();
        }

        @Test
        @DisplayName("底层异常包装为 RF_BE_003 并保留 cause")
        void storeErrorsBecomeCacheDriverException() {
            RecordingStore store = new RecordingStore();
            store.failNext = true;
            RouteCache cache = new RouteCache(store, false, 60);

            assertThatThrownBy(() -> cache.get("admin", Map.class))
                    .isInstanceOfSatisfying(CacheDriverException.class, error -> {
                        assertThat(error.code()).isEqualTo("RF_BE_003");
                        assertThat(error.httpStatus()).isEqualTo(500);
                        assertThat(error).hasMessage("Cache driver error: boom");
                        assertThat(error.getCause()).isInstanceOf(IllegalStateException.class);
                    });
        }
    }
}
