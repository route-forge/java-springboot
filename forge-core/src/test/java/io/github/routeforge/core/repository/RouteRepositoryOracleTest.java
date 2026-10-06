package io.github.routeforge.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.routeforge.core.PhpFixtures;
import io.github.routeforge.core.cache.RouteCache;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.contract.CacheStore;
import io.github.routeforge.core.exception.ForgeRuntimeException;
import io.github.routeforge.core.filter.RouteNameFilter;
import io.github.routeforge.core.support.WarningSink;
import io.github.routeforge.core.tier.TierResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 仓库层的跨语言对等测试：逐操作比对<b>序列化后的 JSON 文本</b>，外加缓存调用序列与落库键集合。
 *
 * <p>比 JSON 文本而不是比结构化对象，是因为这条契约最容易坏在形态细节上：空层级必须是
 * {@code {"routes":{}}} 而不能是 {@code []}，{@code parameter_defaults} 同理。这些差异在
 * Map/POJO 层面看是等价的，只有落到字节上才分得清——而前端读的正是字节。
 *
 * <p>缓存调用序列一起比对：它验证的是「谁在什么时候碰了缓存、以什么 TTL 写入」，
 * 只看产物是否相等无法发现「每次都重扫」或「0 被当成不缓存」这类退化。
 */
class RouteRepositoryOracleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> matchesPhpOracle() {
        JsonNode cases = PhpFixtures.read("repository.json").get("cases");
        List<JsonNode> entries = new ArrayList<>();
        cases.forEach(entries::add);
        assertThat(entries).as("仓库层用例表不应静默变空").isNotEmpty();

        return entries.stream().map(entry -> DynamicTest.dynamicTest(
                entry.get("input").get("name").asText(), () -> runCase(entry)));
    }

    private static void runCase(JsonNode entry) {
        JsonNode input = entry.get("input");
        JsonNode expected = entry.get("expected");

        RecordingStore store = new RecordingStore();
        RouteCache cache = new RouteCache(store, false, integer(input.get("ttl")));
        LevelsConfig levels = PhpFixtures.toLevels(input.get("levels"));
        boolean strict = input.get("strict").asBoolean();
        TierResolver resolver = new TierResolver(levels, null, strict, WarningSink.NOOP);
        RouteNameFilter filter = new RouteNameFilter(
                RouteNameFilter.FORGE_PREFIXES, PhpFixtures.strings(input.get("uriPrefixes")));
        Map<String, Object> aliases = PhpFixtures.toMap(input.get("aliases"));

        RouteRepository repository = new RouteRepository(
                () -> PhpFixtures.toRoutes(input.get("infos")),
                resolver,
                cache,
                levels,
                aliases,
                toConfig(input.get("runtime"), strict),
                filter);

        JsonNode expectedResults = expected.get("results");
        for (JsonNode op : input.get("ops")) {
            String kind = op.get(0).asText();
            String argument = op.size() > 1 && !op.get(1).isNull() ? op.get(1).asText() : null;
            String label = "level".equals(kind) ? "level:" + argument : kind;
            JsonNode expect = expectedResults.get(label);

            if (expect.get("ok").asBoolean()) {
                Map<String, Object> actual = execute(repository, kind, argument);

                assertThat(writeJson(actual))
                        .as("操作 %s 的产物 JSON 必须与 PHP 逐字节一致", label)
                        .isEqualTo(expect.get("json").asText());
            } else {
                assertThatThrownBy(() -> execute(repository, kind, argument))
                        .isInstanceOfSatisfying(ForgeRuntimeException.class, error -> {
                            assertThat(error.code()).isEqualTo(expect.get("code").asText());
                            assertThat(error.httpStatus()).isEqualTo(expect.get("httpStatus").asInt());
                            assertThat(error.getMessage()).isEqualTo(expect.get("message").asText());
                        });
            }
        }

        assertThat(store.calls).as("缓存调用序列").isEqualTo(PhpFixtures.strings(expected.get("cacheCalls")));
        assertThat(store.values.keySet()).as("落库键集合与顺序").containsExactlyElementsOf(
                PhpFixtures.strings(expected.get("storedKeys")));
    }

    private static Map<String, Object> execute(RouteRepository repository, String kind, String argument) {
        return switch (kind) {
            case "level" -> repository.routesForLevel(argument);
            case "summary" -> repository.summary();
            case "manager" -> repository.allRoutesWithTiers();
            case "unassigned" -> asPayload(repository.unassignedRoutes());
            default -> throw new IllegalStateException("未知操作：" + kind);
        };
    }

    private static Map<String, Object> asPayload(Map<String, Object> routes) {
        return Map.of("routes", routes);
    }

    private static RepositoryConfig toConfig(JsonNode runtime, boolean strict) {
        JsonNode urlPrefix = runtime.get("url_prefix");
        JsonNode cacheTtl = runtime.get("cache_ttl");
        JsonNode schemeVersion = runtime.get("scheme_version");
        return new RepositoryConfig(
                schemeVersion == null || schemeVersion.isNull() ? null : schemeVersion.asInt(),
                runtime.has("strict_mode") ? runtime.get("strict_mode").asBoolean() : strict,
                runtime.get("endpoint_prefix").asText(),
                urlPrefix == null || urlPrefix.isNull() ? null : urlPrefix.asText(),
                cacheTtl == null || cacheTtl.isNull() ? null : cacheTtl.asInt());
    }

    private static Integer integer(JsonNode node) {
        return node == null || node.isNull() ? null : node.asInt();
    }

    /** 与 PHP {@code json_encode(JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES)} 同形态：紧凑、不转义斜杠与非 ASCII。 */
    private static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 与 PHP 侧 RecordingCache 同款：记录调用序列，键序即写入序。 */
    private static final class RecordingStore implements CacheStore {
        final List<String> calls = new ArrayList<>();
        final Map<String, Object> values = new LinkedHashMap<>();

        @Override
        public Object get(String key) {
            calls.add("get:" + key);
            return values.get(key);
        }

        @Override
        public void put(String key, Object value, Long seconds) {
            calls.add("put:" + key + ":" + (seconds == null ? "null" : seconds));
            values.put(key, value);
        }

        @Override
        public void forget(String key) {
            calls.add("forget:" + key);
            values.remove(key);
        }
    }
}
